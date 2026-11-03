// libnest_preload.so — in-process fast path of the Nest engine.
//
// Loaded into every guest process (LD_PRELOAD, injected by the tracer on each execve). It wraps
// the libc file functions, translates *absolute* guest paths itself with the same vfs code as the
// tracer, and issues the syscall tagged with fastPathTag(nr) so seccomp lets it through without a
// ptrace stop (~1 us instead of hundreds of us on a phone).
//
// Anything it does not handle — relative paths, /proc magic links, execve, static binaries — still
// reaches the tracer untagged, so correctness never depends on this library.
//
// SPDX-License-Identifier: Apache-2.0
#include <cerrno>
#include <dlfcn.h>
#include <cstdio>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "fastpath.h"
#include "netlink_emu.h"
#include "vbinder_client.h"
#include "vfs.h"
#include "vfs_share.h"

#include <mutex>

using nest::taggedSyscall;

namespace {

nest::Vfs* g_vfs = nullptr;

int fastLstat(const char* p, struct stat* st) {
    // The kernel writes its 64-bit stat layout; vfs only needs st_mode, so use a buffer that
    // fits that layout on every ABI.
    alignas(8) unsigned char buf[256];
    long r = taggedSyscall(nest::kSysFstatat, AT_FDCWD, reinterpret_cast<long>(p), reinterpret_cast<long>(buf),
                           AT_SYMLINK_NOFOLLOW);
    if (r < 0) return -1;
    struct stat64 k;
    memcpy(&k, buf, sizeof(k));
    memset(st, 0, sizeof(*st));
    st->st_mode = k.st_mode;
    return 0;
}

long fastReadlink(const char* p, char* buf, unsigned long n) {
    return taggedSyscall(SYS_readlinkat, AT_FDCWD, reinterpret_cast<long>(p), reinterpret_cast<long>(buf),
                         static_cast<long>(n));
}

const nest::HostFs kFastFs{fastLstat, fastReadlink};

nest::VfsShareReader g_share;
std::mutex g_reloadMu;

// Rebuilds the in-process mount table from the live shared table (the guest mounts things at any
// time). Old tables are intentionally leaked: other threads may still be using them.
void reload() {
    std::lock_guard<std::mutex> g(g_reloadMu);
    if (!g_share.changed()) return;
    std::string text;
    if (!g_share.read(text)) return;
    auto* v = new nest::Vfs();
    if (!v->deserialize(text) || v->mounts().empty()) {
        delete v;
        return;
    }
    v->setHostFs(&kFastFs);
    __atomic_store_n(&g_vfs, v, __ATOMIC_RELEASE);
}

// Attaches to the live mount table published by the supervisor (NEST_VFS_FILE).
__attribute__((constructor)) void init() {
    const char* file = getenv("NEST_VFS_FILE");
    if (!file || !*file) return;
    long fd = taggedSyscall(SYS_openat, AT_FDCWD, reinterpret_cast<long>(file), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    bool ok = g_share.attach(static_cast<int>(fd));
    syscall(SYS_close, static_cast<int>(fd));
    if (ok) reload();
}

nest::Vfs* currentVfs() {
    if (g_share.changed()) reload();
    return __atomic_load_n(&g_vfs, __ATOMIC_ACQUIRE);
}

// Absolute paths outside /proc are translated here; everything else goes to the tracer.
bool translate(const char* path, bool followLast, std::string& out) {
    if (!path || path[0] != '/') return false;
    nest::Vfs* vfs = currentVfs();
    if (!vfs) return false;
    if (strncmp(path, "/proc/", 6) == 0 || strcmp(path, "/proc") == 0) return false;
    if (!vfs->resolve(path, "/", followLast, out)) return false;
    if (path[strlen(path) - 1] == '/' && out.back() != '/') out += '/';
    return true;
}

long ret(long r) {
    if (r < 0 && r > -4096) {
        errno = static_cast<int>(-r);
        return -1;
    }
    return r;
}

// One helper per syscall shape: translate path(s) or fall back to an untagged (traced) syscall.
long pathCall(long nr, long dirfd, const char* path, long a2, long a3, long a4, bool follow) {
    if (nr == SYS_openat) {
        // /dev/binder, /dev/hwbinder, /dev/vndbinder are served by the vbinder broker.
        int b = nest::vbinder::clientOpen(path, static_cast<int>(a2));
        if (b != -2) return b;
    }
    std::string host;
    if (translate(path, follow, host)) {
        if (nr == SYS_openat && (a2 & O_ACCMODE) != O_RDONLY) {
            nest::Vfs* v = currentVfs();
            if (!(a2 & O_CREAT) && v->isCgroupHost(host)) {
                a2 |= O_CREAT;  // cgroup control file of a new group
                a3 = 0644;
            }
            if (v->isAppendHost(host)) a2 = (a2 | O_APPEND) & ~O_TRUNC;  // record device (/dev/kmsg)
        }
        return ret(taggedSyscall(nr, dirfd, reinterpret_cast<long>(host.c_str()), a2, a3, a4));
    }
    return (syscall(nr, dirfd, path, a2, a3, a4));
}

int openFlagsFollow(int flags) { return !(flags & O_NOFOLLOW); }

}  // namespace

// ---- virtual file ownership
// Guest files all belong to the app's host uid. The guest sees the owner its chown() recorded
// (xattr user.nest.owner = "uid:gid", written by the tracer), and root for everything else the
// app owns: bionic's property area, for one, refuses files not owned by root.
namespace {
long envId(const char* name) {
    const char* v = getenv(name);
    return v && *v ? strtol(v, nullptr, 10) : -1;
}
long hostUid() { static long v = envId("NEST_HOST_UID"); return v; }
long hostGid() { static long v = envId("NEST_HOST_GID"); return v; }

// Virtual attributes of a guest file: xattr user.nest.owner = "uid:gid" or "uid:gid:mode" (octal),
// written on chown/chmod (by these wrappers or the tracer).
struct Attr {
    unsigned uid = 0, gid = 0;
    int mode = -1;  // -1: the host mode is the guest's
    bool have = false;
};

bool readAttr(const char* hostPath, int fd, bool follow, Attr& a) {
    char buf[48];
    long n;
    if (hostPath)
        n = nest::taggedSyscall(follow ? SYS_getxattr : SYS_lgetxattr, reinterpret_cast<long>(hostPath),
                                reinterpret_cast<long>(nest::kOwnerXattr), reinterpret_cast<long>(buf), sizeof(buf) - 1);
    else
        n = syscall(SYS_fgetxattr, fd, nest::kOwnerXattr, buf, sizeof(buf) - 1);
    if (n <= 0) return false;
    buf[n] = 0;
    unsigned m = 0;
    int k = sscanf(buf, "%u:%u:%o", &a.uid, &a.gid, &m);
    if (k < 2) return false;
    a.mode = k == 3 ? static_cast<int>(m & 07777) : -1;
    a.have = true;
    return true;
}

void writeAttr(const char* hostPath, int fd, bool follow, const Attr& a) {
    char buf[48];
    long n;
    if (a.uid == 0 && a.gid == 0 && a.mode < 0) {  // the default: no record
        if (hostPath)
            n = nest::taggedSyscall(follow ? SYS_removexattr : SYS_lremovexattr, reinterpret_cast<long>(hostPath),
                                    reinterpret_cast<long>(nest::kOwnerXattr));
        else
            n = syscall(SYS_fremovexattr, fd, nest::kOwnerXattr);
        (void)n;
        return;
    }
    int len = a.mode >= 0 ? snprintf(buf, sizeof(buf), "%u:%u:%o", a.uid, a.gid, a.mode)
                          : snprintf(buf, sizeof(buf), "%u:%u", a.uid, a.gid);
    if (hostPath)
        n = nest::taggedSyscall(follow ? SYS_setxattr : SYS_lsetxattr, reinterpret_cast<long>(hostPath),
                                reinterpret_cast<long>(nest::kOwnerXattr), reinterpret_cast<long>(buf), len, 0);
    else
        n = nest::taggedSyscall(SYS_fsetxattr, fd, reinterpret_cast<long>(nest::kOwnerXattr), reinterpret_cast<long>(buf),
                                len, 0);
    (void)n;
}

// The host keeps owner access whatever the guest sets (the app *is* the owner on the host): the
// guest's mode lives in the record and is what stat() reports.
unsigned hostModeFor(unsigned mode, bool dir) { return (mode & 07777) | (dir ? 0700u : 0600u); }

template <typename St>
void virtualOwner(St* st, const char* hostPath, int fd, bool follow) {
    if (hostUid() < 0 || !st) return;
    if (static_cast<long>(st->st_uid) != hostUid()) return;
    Attr a;
    if (readAttr(hostPath, fd, follow, a)) {
        st->st_uid = a.uid;
        st->st_gid = a.gid;
        if (a.mode >= 0) st->st_mode = (st->st_mode & S_IFMT) | static_cast<unsigned>(a.mode);
        return;
    }
    st->st_uid = 0;
    if (static_cast<long>(st->st_gid) == hostGid() || static_cast<long>(st->st_gid) == hostUid()) st->st_gid = 0;
}
}  // namespace


// ---- virtual netlink (netlink_emu.h)
// A guest netlink socket is one end of a datagram socketpair; the engine keeps the other end and
// writes the "kernel's" replies into it, so the guest's recv() gets them.
namespace {
constexpr int kNlMaxFd = 65536;
constexpr int kAfNetlink = 16;
struct NlFd {
    int peer = -1;
    int proto = 0;
};
NlFd g_nl[kNlMaxFd];
int g_nlCount = 0;

inline NlFd* nlOf(int fd) {
    if (__atomic_load_n(&g_nlCount, __ATOMIC_RELAXED) == 0 || fd < 0 || fd >= kNlMaxFd) return nullptr;
    return g_nl[fd].peer >= 0 ? &g_nl[fd] : nullptr;
}

ssize_t nlSend(NlFd* n, const void* buf, size_t len) {
    std::vector<uint8_t> rep = nest::netlinkReplies(n->proto, static_cast<const uint8_t*>(buf), len);
    if (!rep.empty()) syscall(SYS_sendto, n->peer, rep.data(), rep.size(), MSG_DONTWAIT | MSG_NOSIGNAL, nullptr, 0);
    return static_cast<ssize_t>(len);
}

ssize_t nlSendIov(NlFd* n, const struct iovec* iov, int cnt) {
    std::vector<uint8_t> all;
    for (int i = 0; i < cnt; i++) {
        const auto* b = static_cast<const uint8_t*>(iov[i].iov_base);
        all.insert(all.end(), b, b + iov[i].iov_len);
    }
    return nlSend(n, all.data(), all.size());
}

void nlKernelAddr(void* addr, socklen_t* alen) {  // replies come from the kernel: pid 0
    if (!addr || !alen) return;
    unsigned char sa[12] = {};
    uint16_t fam = kAfNetlink;
    memcpy(sa, &fam, 2);
    memcpy(addr, sa, *alen < sizeof(sa) ? *alen : sizeof(sa));
    *alen = sizeof(sa);
}
}  // namespace

extern "C" {

#define NEST_EXPORT __attribute__((visibility("default")))

#if defined(NEST_HAVE_RAW_SYSCALL)
// libc's syscall(): unchanged, except that a bpf() the kernel (or a CPU emulator) does not know
// is handed to the engine through its mount carrier (fastpath.h).
NEST_EXPORT long syscall(long nr, ...) {
    va_list ap;
    va_start(ap, nr);
    long a[6];
    for (long& x : a) x = va_arg(ap, long);
    va_end(ap);
    long r = nest::rawSyscall(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    if (nr == SYS_bpf && r == -ENOSYS && static_cast<unsigned long>(a[2]) < 0x10000)
        r = nest::rawSyscall(SYS_mount, 0, reinterpret_cast<long>("/"), reinterpret_cast<long>(nest::kNestBpfFsType),
                             (a[0] & 0xffff) | (a[2] << nest::kNestBpfSizeShift), a[1], 0);
    return ret(r);
}
#endif

// Tester log: guest opens of /proc files that fail (ActivityManager decides that a process is
// dead when it cannot read /proc/<pid>/stat). Appended to <state>/preload.log, 40 per process.
// /proc/<pid>/status with the guest's virtual ids (published by the tracer, attr/creds-<pid>):
// ActivityManager compares the uid there with the process's (isProcessAliveLocked).
constexpr long kNotStatus = -100000;  // virtualStatus: not a status file, open normally
long virtualStatus(int dirfd, const char* path, int flags) {
    if (!path || (flags & O_ACCMODE) != O_RDONLY || strncmp(path, "/proc/", 6) != 0) return kNotStatus;
    const char* p = path + 6;
    long pid;
    if (!strncmp(p, "self/", 5)) {
        // the real pid (getpid() may be virtual: the guest's init is pid 1)
        char self[24] = {};
        if (syscall(SYS_readlinkat, AT_FDCWD, "/proc/self", self, sizeof(self) - 1) <= 0) return kNotStatus;
        pid = strtol(self, nullptr, 10);
        p += 4;
    } else {
        char* end;
        pid = strtol(p, &end, 10);
        if (end == p || pid <= 0) return kNotStatus;
        p = end;
    }
    if (!strncmp(p, "/task/", 6)) {  // a thread's status: same ids as its process
        p += 6;
        while (*p >= '0' && *p <= '9') p++;
    }
    if (strcmp(p, "/status") != 0) return kNotStatus;
    const char* vfs = getenv("NEST_VFS_FILE");
    const char* slash = vfs ? strrchr(vfs, '/') : nullptr;
    if (!slash) return kNotStatus;
    char credPath[512];
    snprintf(credPath, sizeof(credPath), "%.*s/attr/creds-%ld", static_cast<int>(slash - vfs), vfs, pid);
    unsigned id[8];
    char cbuf[160] = {};
    int cf = static_cast<int>(syscall(SYS_openat, AT_FDCWD, credPath, O_RDONLY | O_CLOEXEC));
    if (cf < 0) return kNotStatus;
    long cn = syscall(SYS_read, cf, cbuf, sizeof(cbuf) - 1);
    syscall(SYS_close, cf);
    if (cn <= 0 || sscanf(cbuf, "%u %u %u %u %u %u %u %u", &id[0], &id[1], &id[2], &id[3], &id[4], &id[5], &id[6], &id[7]) != 8)
        return kNotStatus;
    long real = pathCall(SYS_openat, dirfd, path, O_RDONLY | O_CLOEXEC, 0, 0, true);
    if (real < 0) return -errno;
    std::string text;
    char buf[4096];
    for (;;) {
        long n = syscall(SYS_read, real, buf, sizeof(buf));
        if (n <= 0) break;
        text.append(buf, static_cast<size_t>(n));
    }
    syscall(SYS_close, real);
    std::string out;
    size_t at = 0;
    while (at < text.size()) {
        size_t nl = text.find('\n', at);
        std::string line = text.substr(at, nl == std::string::npos ? std::string::npos : nl - at);
        at = nl == std::string::npos ? text.size() : nl + 1;
        char repl[96];
        if (!line.compare(0, 4, "Uid:")) {
            snprintf(repl, sizeof(repl), "Uid:\t%u\t%u\t%u\t%u", id[0], id[1], id[2], id[3]);
            line = repl;
        } else if (!line.compare(0, 4, "Gid:")) {
            snprintf(repl, sizeof(repl), "Gid:\t%u\t%u\t%u\t%u", id[4], id[5], id[6], id[7]);
            line = repl;
        }
        out += line;
        out += '\n';
    }
    long mfd = syscall(SYS_memfd_create, "status", (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
    if (mfd < 0) {
        long r = pathCall(SYS_openat, dirfd, path, flags, 0, 0, true);
        return r < 0 ? -errno : r;
    }
    syscall(SYS_write, mfd, out.data(), out.size());
    syscall(SYS_lseek, mfd, 0, SEEK_SET);
    return mfd;
}

void noteProcOpen(const char* path, long r) {
    if (r >= 0 || !path || strncmp(path, "/proc/", 6) != 0) return;
    static int budget = 40;
    if (__atomic_sub_fetch(&budget, 1, __ATOMIC_RELAXED) < 0) return;
    const char* vfs = getenv("NEST_VFS_FILE");
    if (!vfs) return;
    const char* slash = strrchr(vfs, '/');
    if (!slash) return;
    char logPath[512];
    snprintf(logPath, sizeof(logPath), "%.*s/preload.log", static_cast<int>(slash - vfs), vfs);
    int f = static_cast<int>(syscall(SYS_openat, AT_FDCWD, logPath, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644));
    if (f < 0) return;
    char line[600];
    int n = snprintf(line, sizeof(line), "pid %d: open %s failed: %s\n", static_cast<int>(syscall(SYS_getpid)),
                     path, strerror(static_cast<int>(-r)));
    syscall(SYS_write, f, line, n);
    syscall(SYS_close, f);
}

// One implementation for every open entry point. Never call the libc names from here: with
// FORTIFY, bionic's headers turn a 3-argument openat() into __openat_2(), which is ours.
int openImpl(int dirfd, const char* path, int flags, mode_t mode) {
    long v = virtualStatus(dirfd, path, flags);
    if (v != kNotStatus) {
        if (v < 0) { errno = static_cast<int>(-v); return -1; }
        return static_cast<int>(v);
    }
    long r = pathCall(SYS_openat, dirfd, path, flags, mode, 0, openFlagsFollow(flags));
    if (r < 0) noteProcOpen(path, -errno);
    return static_cast<int>(r);
}

NEST_EXPORT int openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return openImpl(dirfd, path, flags, mode);
}

NEST_EXPORT int open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return openImpl(AT_FDCWD, path, flags, mode);
}

NEST_EXPORT int open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return openImpl(AT_FDCWD, path, flags, mode);
}

NEST_EXPORT int openat64(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return openImpl(dirfd, path, flags, mode);
}

// Bionic's FORTIFY entry points.
// Fortified open (bionic's 2-argument open() in code built with FORTIFY: most of Android).
NEST_EXPORT int __open_2(const char* path, int flags) { return openImpl(AT_FDCWD, path, flags, 0); }
NEST_EXPORT int __openat_2(int dirfd, const char* path, int flags) { return openImpl(dirfd, path, flags, 0); }

// The *64 variants receive the kernel's stat layout on every ABI.

NEST_EXPORT int fstatat64(int dirfd, const char* path, struct stat64* st, int flags) {
    bool follow = !(flags & AT_SYMLINK_NOFOLLOW);
    std::string host;
    long r;
    if (translate(path, follow, host)) {
        r = ret(taggedSyscall(nest::kSysFstatat, dirfd, reinterpret_cast<long>(host.c_str()), reinterpret_cast<long>(st), flags));
        if (r == 0) virtualOwner(st, host.c_str(), -1, follow);
    } else {
        r = syscall(nest::kSysFstatat, dirfd, path, st, flags);
        if (r == 0) virtualOwner(st, nullptr, (path && path[0]) ? -1 : dirfd, follow);
    }
    return static_cast<int>(r);
}
#if defined(__LP64__) || defined(__BIONIC__)
// fstat: the kernel's 64-bit stat layout on these ABIs (bionic arm32: fstat64).
NEST_EXPORT int fstat(int fd, struct stat* st) {
    // Tagged: untagged fstat is traced (static binaries get the owner fixed by the tracer).
#if defined(SYS_fstat64)
    long r = ret(taggedSyscall(SYS_fstat64, fd, reinterpret_cast<long>(st)));
#else
    long r = ret(taggedSyscall(SYS_fstat, fd, reinterpret_cast<long>(st)));
#endif
    if (r == 0) virtualOwner(st, nullptr, fd, true);
    return static_cast<int>(r);
}
NEST_EXPORT int fstat64(int fd, struct stat64* st) { return fstat(fd, reinterpret_cast<struct stat*>(st)); }
#endif
NEST_EXPORT int stat64(const char* path, struct stat64* st) { return fstatat64(AT_FDCWD, path, st, 0); }
NEST_EXPORT int lstat64(const char* path, struct stat64* st) { return fstatat64(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
#if !(defined(__i386__) && defined(__GLIBC__))
// Plain stat: same layout as stat64 on 64-bit ABIs and on bionic. (32-bit glibc's legacy struct
// stat is smaller, so it is left to the traced slow path there.)
NEST_EXPORT int fstatat(int dirfd, const char* path, struct stat* st, int flags) {
    return fstatat64(dirfd, path, reinterpret_cast<struct stat64*>(st), flags);
}
NEST_EXPORT int stat(const char* path, struct stat* st) { return fstatat(AT_FDCWD, path, st, 0); }
NEST_EXPORT int lstat(const char* path, struct stat* st) { return fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
#endif

NEST_EXPORT int faccessat(int dirfd, const char* path, int mode, int flags) {
    // The kernel's faccessat has no flags argument; libc emulates AT_* flags. Keep it simple: plain call.
    return static_cast<int>(pathCall(SYS_faccessat, dirfd, path, mode, 0, 0, true));
}
NEST_EXPORT int access(const char* path, int mode) { return faccessat(AT_FDCWD, path, mode, 0); }

NEST_EXPORT ssize_t readlinkat(int dirfd, const char* path, char* buf, size_t size) {
    return pathCall(SYS_readlinkat, dirfd, path, reinterpret_cast<long>(buf), static_cast<long>(size), 0, false);
}
NEST_EXPORT ssize_t readlink(const char* path, char* buf, size_t size) { return readlinkat(AT_FDCWD, path, buf, size); }

NEST_EXPORT int mkdirat(int dirfd, const char* path, mode_t mode) {
    return static_cast<int>(pathCall(SYS_mkdirat, dirfd, path, mode, 0, 0, false));
}
NEST_EXPORT int mkdir(const char* path, mode_t mode) { return mkdirat(AT_FDCWD, path, mode); }

NEST_EXPORT int unlinkat(int dirfd, const char* path, int flags) {
    return static_cast<int>(pathCall(SYS_unlinkat, dirfd, path, flags, 0, 0, false));
}
NEST_EXPORT int unlink(const char* path) { return unlinkat(AT_FDCWD, path, 0); }
NEST_EXPORT int rmdir(const char* path) { return unlinkat(AT_FDCWD, path, AT_REMOVEDIR); }

NEST_EXPORT int fchmodat(int dirfd, const char* path, mode_t mode, int flags) {
    bool follow = !(flags & AT_SYMLINK_NOFOLLOW);
    std::string host;
    if (hostUid() < 0 || !translate(path, follow, host))
        return static_cast<int>(syscall(SYS_fchmodat, dirfd, path, mode));  // traced: the tracer does the same
    struct stat st;
    if (ret(taggedSyscall(nest::kSysFstatat, AT_FDCWD, reinterpret_cast<long>(host.c_str()), reinterpret_cast<long>(&st),
                          follow ? 0 : AT_SYMLINK_NOFOLLOW)) != 0)
        return -1;
    unsigned hm = hostModeFor(mode, S_ISDIR(st.st_mode));
    long r = ret(taggedSyscall(SYS_fchmodat, AT_FDCWD, reinterpret_cast<long>(host.c_str()), hm));
    if (r == 0) {
        Attr a;
        readAttr(host.c_str(), -1, follow, a);
        a.mode = hm == (mode & 07777) ? -1 : static_cast<int>(mode & 07777);
        writeAttr(host.c_str(), -1, follow, a);
    }
    return static_cast<int>(r);
}
NEST_EXPORT int fchmod(int fd, mode_t mode) {
    struct stat st;
#if defined(SYS_fstat64)
    long sr = ret(taggedSyscall(SYS_fstat64, fd, reinterpret_cast<long>(&st)));
#else
    long sr = ret(taggedSyscall(SYS_fstat, fd, reinterpret_cast<long>(&st)));
#endif
    if (sr != 0) return -1;
    if (hostUid() < 0 || static_cast<long>(st.st_uid) != hostUid())
        return static_cast<int>(ret(taggedSyscall(SYS_fchmod, fd, mode)));
    unsigned hm = hostModeFor(mode, S_ISDIR(st.st_mode));
    long r = ret(taggedSyscall(SYS_fchmod, fd, hm));
    if (r == 0) {
        Attr a;
        readAttr(nullptr, fd, true, a);
        a.mode = hm == (mode & 07777) ? -1 : static_cast<int>(mode & 07777);
        writeAttr(nullptr, fd, true, a);
    }
    return static_cast<int>(r);
}
NEST_EXPORT int chmod(const char* path, mode_t mode) { return fchmodat(AT_FDCWD, path, mode, 0); }

NEST_EXPORT int renameat(int olddirfd, const char* oldpath, int newdirfd, const char* newpath) {
    std::string a, b;
    if (translate(oldpath, false, a) && translate(newpath, false, b))
        return static_cast<int>(ret(taggedSyscall(SYS_renameat, olddirfd, reinterpret_cast<long>(a.c_str()), newdirfd,
                                                  reinterpret_cast<long>(b.c_str()))));
    return static_cast<int>((syscall(SYS_renameat, olddirfd, oldpath, newdirfd, newpath)));
}
NEST_EXPORT int rename(const char* oldpath, const char* newpath) { return renameat(AT_FDCWD, oldpath, AT_FDCWD, newpath); }

// ---- binder devices: ioctl / mmap / close on vbinder fds go to the broker

#if defined(__BIONIC__)
NEST_EXPORT int ioctl(int fd, int request, ...) {
#else
NEST_EXPORT int ioctl(int fd, unsigned long request, ...) {
#endif
    va_list ap;
    va_start(ap, request);
    void* arg = va_arg(ap, void*);
    va_end(ap);
    if (nest::vbinder::clientIsBinderFd(fd))
        return nest::vbinder::clientIoctl(fd, static_cast<unsigned long>(static_cast<unsigned int>(request)), arg);
    return static_cast<int>((syscall(SYS_ioctl, fd, request, arg)));
}

NEST_EXPORT void* mmap(void* addr, size_t len, int prot, int flags, int fd, off_t off) {
    if (fd >= 0 && nest::vbinder::clientIsBinderFd(fd)) return nest::vbinder::clientMmap(addr, len, prot, flags, fd, off);
    // libc syscall() already returns -1 / sets errno on failure.
#if defined(SYS_mmap2)
    long r = syscall(SYS_mmap2, addr, len, prot, flags, fd, static_cast<long>(off) >> 12);
#else
    long r = syscall(SYS_mmap, addr, len, prot, flags, fd, off);
#endif
    return r == -1 ? MAP_FAILED : reinterpret_cast<void*>(r);
}
NEST_EXPORT void* mmap64(void* addr, size_t len, int prot, int flags, int fd, off64_t off) {
#if defined(SYS_mmap2)
    if (fd >= 0 && nest::vbinder::clientIsBinderFd(fd)) return nest::vbinder::clientMmap(addr, len, prot, flags, fd, 0);
    long r = syscall(SYS_mmap2, addr, len, prot, flags, fd, static_cast<long>(off >> 12));
    return r == -1 ? MAP_FAILED : reinterpret_cast<void*>(r);
#else
    return mmap(addr, len, prot, flags, fd, static_cast<off_t>(off));
#endif
}


NEST_EXPORT int socket(int domain, int type, int protocol) {
    if (domain != kAfNetlink || hostUid() < 0)
        return static_cast<int>(syscall(SYS_socket, domain, type, protocol));
    int sv[2];
    int flags = type & (SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (syscall(SYS_socketpair, AF_UNIX, SOCK_DGRAM | flags, 0, sv) != 0) return -1;
    if (sv[0] >= kNlMaxFd) {
        syscall(SYS_close, sv[0]);
        syscall(SYS_close, sv[1]);
        errno = EMFILE;
        return -1;
    }
    // The "kernel" end is ours: keep it away from fd numbers the guest uses.
    long hi = syscall(SYS_fcntl, sv[1], F_DUPFD_CLOEXEC, 1000);
    if (hi >= 0) {
        syscall(SYS_close, sv[1]);
        sv[1] = static_cast<int>(hi);
    }
    g_nl[sv[0]].proto = protocol;
    g_nl[sv[0]].peer = sv[1];
    __atomic_add_fetch(&g_nlCount, 1, __ATOMIC_RELAXED);
    return sv[0];
}
NEST_EXPORT int bind(int fd, const struct sockaddr* a, socklen_t l) {
    if (nlOf(fd)) return 0;
    return static_cast<int>(syscall(SYS_bind, fd, a, l));
}
NEST_EXPORT int connect(int fd, const struct sockaddr* a, socklen_t l) {
    if (nlOf(fd)) return 0;
    return static_cast<int>(syscall(SYS_connect, fd, a, l));
}
NEST_EXPORT int getsockname(int fd, struct sockaddr* a, socklen_t* l) {
    if (nlOf(fd)) {
        if (!a || !l) { errno = EFAULT; return -1; }
        unsigned char sa[12] = {};
        uint16_t fam = kAfNetlink;
        uint32_t pid = static_cast<uint32_t>(syscall(SYS_getpid));
        memcpy(sa, &fam, 2);
        memcpy(sa + 4, &pid, 4);
        memcpy(a, sa, *l < sizeof(sa) ? *l : sizeof(sa));
        *l = sizeof(sa);
        return 0;
    }
    return static_cast<int>(syscall(SYS_getsockname, fd, a, l));
}
NEST_EXPORT int setsockopt(int fd, int level, int name, const void* v, socklen_t l) {
    if (nlOf(fd)) {
        if (level == SOL_SOCKET) syscall(SYS_setsockopt, fd, level, name, v, l);  // best effort
        return 0;  // SOL_NETLINK options (memberships, ext ack...) are accepted
    }
    return static_cast<int>(syscall(SYS_setsockopt, fd, level, name, v, l));
}
NEST_EXPORT ssize_t send(int fd, const void* b, size_t n, int f) {
    if (NlFd* x = nlOf(fd)) return nlSend(x, b, n);
    return syscall(SYS_sendto, fd, b, n, f, nullptr, 0);
}
NEST_EXPORT ssize_t sendto(int fd, const void* b, size_t n, int f, const struct sockaddr* a, socklen_t l) {
    if (NlFd* x = nlOf(fd)) return nlSend(x, b, n);
    return syscall(SYS_sendto, fd, b, n, f, a, l);
}
NEST_EXPORT ssize_t sendmsg(int fd, const struct msghdr* m, int f) {
    if (NlFd* x = nlOf(fd)) return m ? nlSendIov(x, m->msg_iov, static_cast<int>(m->msg_iovlen)) : 0;
    return syscall(SYS_sendmsg, fd, m, f);
}
NEST_EXPORT ssize_t write(int fd, const void* b, size_t n) {
    if (NlFd* x = nlOf(fd)) return nlSend(x, b, n);
    return syscall(SYS_write, fd, b, n);
}
NEST_EXPORT ssize_t writev(int fd, const struct iovec* iov, int cnt) {
    if (NlFd* x = nlOf(fd)) return nlSendIov(x, iov, cnt);
    return syscall(SYS_writev, fd, iov, cnt);
}
NEST_EXPORT ssize_t recvfrom(int fd, void* b, size_t n, int f, struct sockaddr* a, socklen_t* l) {
    if (nlOf(fd)) {
        ssize_t r = syscall(SYS_recvfrom, fd, b, n, f, nullptr, nullptr);
        if (r >= 0) nlKernelAddr(a, l);
        return r;
    }
    return syscall(SYS_recvfrom, fd, b, n, f, a, l);
}
NEST_EXPORT ssize_t recvmsg(int fd, struct msghdr* m, int f) {
    if (nlOf(fd) && m) {
        void* name = m->msg_name;
        socklen_t nl = m->msg_namelen;
        m->msg_name = nullptr;
        m->msg_namelen = 0;
        ssize_t r = syscall(SYS_recvmsg, fd, m, f);
        m->msg_name = name;
        m->msg_namelen = nl;
        if (r >= 0 && name) nlKernelAddr(name, &m->msg_namelen);
        return r;
    }
    return syscall(SYS_recvmsg, fd, m, f);
}

NEST_EXPORT int close(int fd) {
    if (nest::vbinder::clientIsBinderFd(fd)) nest::vbinder::clientClose(fd);
    if (NlFd* x = nlOf(fd)) {
        syscall(SYS_close, x->peer);
        x->peer = -1;
        __atomic_sub_fetch(&g_nlCount, 1, __ATOMIC_RELAXED);
    }
    long r = syscall(SYS_close, fd);
    if (r == -1 && errno == EINTR) return 0;  // the fd is gone either way (bionic semantics)
    return static_cast<int>(r);
}

}  // extern "C"

// ---- bench only (NEST_HIDE_EMU_THREADS=1, set when a CPU emulator stands in for the phone):
// qemu-user rejects guest seccomp filters itself, before the engine could see them (on a phone the
// tracer accepts them without installing, see GuestSeccomp in tracer.cpp). Zygote's children call
// these helpers (C++, statically linked into libandroid_runtime) and abort on failure, so on the bench they succeed without a filter.
namespace {
bool emulatedCpu() { static bool v = getenv("NEST_HIDE_EMU_THREADS") != nullptr; return v; }
bool callReal(const char* name) {
    auto real = reinterpret_cast<bool (*)()>(dlsym(RTLD_NEXT, name));
    return real && real();
}
}  // namespace
__attribute__((visibility("default"))) bool set_app_seccomp_filter() { return emulatedCpu() || callReal("_Z22set_app_seccomp_filterv"); }
__attribute__((visibility("default"))) bool set_app_zygote_seccomp_filter() { return emulatedCpu() || callReal("_Z29set_app_zygote_seccomp_filterv"); }
__attribute__((visibility("default"))) bool set_system_seccomp_filter() { return emulatedCpu() || callReal("_Z25set_system_seccomp_filterv"); }

__attribute__((visibility("default"))) bool install_setuidgid_seccomp_filter(uint32_t lo, uint32_t hi) {
    if (emulatedCpu()) return true;
    auto real = reinterpret_cast<bool (*)(uint32_t, uint32_t)>(dlsym(RTLD_NEXT, "_Z32install_setuidgid_seccomp_filterjj"));
    return real && real(lo, hi);
}
// minijail (media and HAL services): same, its filter would be refused by the emulator.
extern "C" __attribute__((visibility("default"))) void minijail_use_seccomp_filter(struct minijail* j) {
    if (emulatedCpu()) return;
    if (auto real = reinterpret_cast<void (*)(struct minijail*)>(dlsym(RTLD_NEXT, "minijail_use_seccomp_filter"))) real(j);
}
