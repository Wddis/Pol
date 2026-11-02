// libnest_preload.so — in-process fast path of the Nest engine.
//
// Loaded into every guest process (LD_PRELOAD, injected by the tracer on each execve). It wraps
// the libc file functions, translates *absolute* guest paths itself with the same vfs code as the
// tracer, and issues the syscall tagged with kFastPathTag so seccomp lets it through without a
// ptrace stop (~1 us instead of hundreds of us on a phone).
//
// Anything it does not handle — relative paths, /proc magic links, execve, static binaries — still
// reaches the tracer untagged, so correctness never depends on this library.
//
// SPDX-License-Identifier: Apache-2.0
#include <cerrno>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "fastpath.h"
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

NEST_EXPORT int openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return static_cast<int>(pathCall(SYS_openat, dirfd, path, flags, mode, 0, openFlagsFollow(flags)));
}

NEST_EXPORT int open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return static_cast<int>(pathCall(SYS_openat, AT_FDCWD, path, flags, mode, 0, openFlagsFollow(flags)));
}

NEST_EXPORT int open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return static_cast<int>(pathCall(SYS_openat, AT_FDCWD, path, flags, mode, 0, openFlagsFollow(flags)));
}

NEST_EXPORT int openat64(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    return static_cast<int>(pathCall(SYS_openat, dirfd, path, flags, mode, 0, openFlagsFollow(flags)));
}

// Bionic's FORTIFY entry points.
NEST_EXPORT int __open_2(const char* path, int flags) {
    return static_cast<int>(pathCall(SYS_openat, AT_FDCWD, path, flags, 0, 0, openFlagsFollow(flags)));
}
NEST_EXPORT int __openat_2(int dirfd, const char* path, int flags) {
    return static_cast<int>(pathCall(SYS_openat, dirfd, path, flags, 0, 0, openFlagsFollow(flags)));
}

// The *64 variants receive the kernel's stat layout on every ABI.
NEST_EXPORT int fstatat64(int dirfd, const char* path, struct stat64* st, int flags) {
    return static_cast<int>(pathCall(nest::kSysFstatat, dirfd, path, reinterpret_cast<long>(st), flags, 0,
                                     !(flags & AT_SYMLINK_NOFOLLOW)));
}
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
    return static_cast<int>(pathCall(SYS_fchmodat, dirfd, path, mode, 0, 0, true));
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

NEST_EXPORT int close(int fd) {
    if (nest::vbinder::clientIsBinderFd(fd)) nest::vbinder::clientClose(fd);
    long r = syscall(SYS_close, fd);
    if (r == -1 && errno == EINTR) return 0;  // the fd is gone either way (bionic semantics)
    return static_cast<int>(r);
}

}  // extern "C"
