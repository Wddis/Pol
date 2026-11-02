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

using nest::taggedSyscall;

namespace {

nest::Vfs* g_vfs = nullptr;

int fastLstat(const char* p, struct stat* st) {
    long r = taggedSyscall(SYS_newfstatat, AT_FDCWD, reinterpret_cast<long>(p), reinterpret_cast<long>(st),
                           AT_SYMLINK_NOFOLLOW);
    return r < 0 ? -1 : 0;
}

long fastReadlink(const char* p, char* buf, unsigned long n) {
    return taggedSyscall(SYS_readlinkat, AT_FDCWD, reinterpret_cast<long>(p), reinterpret_cast<long>(buf),
                         static_cast<long>(n));
}

const nest::HostFs kFastFs{fastLstat, fastReadlink};

// Loads the mount table handed over by the supervisor (NEST_VFS_FILE).
__attribute__((constructor)) void init() {
    const char* file = getenv("NEST_VFS_FILE");
    if (!file || !*file) return;
    long fd = taggedSyscall(SYS_openat, AT_FDCWD, reinterpret_cast<long>(file), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    std::string text;
    char buf[4096];
    for (;;) {
        long n = syscall(SYS_read, static_cast<int>(fd), buf, sizeof(buf));
        if (n <= 0) break;
        text.append(buf, static_cast<size_t>(n));
    }
    syscall(SYS_close, static_cast<int>(fd));
    auto* v = new nest::Vfs();
    if (!v->deserialize(text) || v->mounts().empty()) {
        delete v;
        return;
    }
    v->setHostFs(&kFastFs);
    g_vfs = v;
}

// Absolute paths outside /proc are translated here; everything else goes to the tracer.
bool translate(const char* path, bool followLast, std::string& out) {
    if (!g_vfs || !path || path[0] != '/') return false;
    if (strncmp(path, "/proc/", 6) == 0 || strcmp(path, "/proc") == 0) return false;
    if (!g_vfs->resolve(path, "/", followLast, out)) return false;
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
    if (translate(path, follow, host))
        return ret(taggedSyscall(nr, dirfd, reinterpret_cast<long>(host.c_str()), a2, a3, a4));
    return (syscall(nr, dirfd, path, a2, a3, a4));
}

int openFlagsFollow(int flags) { return !(flags & O_NOFOLLOW); }

}  // namespace

extern "C" {

#define NEST_EXPORT __attribute__((visibility("default")))

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

NEST_EXPORT int fstatat(int dirfd, const char* path, struct stat* st, int flags) {
    return static_cast<int>(pathCall(SYS_newfstatat, dirfd, path, reinterpret_cast<long>(st), flags, 0,
                                     !(flags & AT_SYMLINK_NOFOLLOW)));
}
NEST_EXPORT int stat(const char* path, struct stat* st) { return fstatat(AT_FDCWD, path, st, 0); }
NEST_EXPORT int lstat(const char* path, struct stat* st) { return fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
// On 64-bit glibc and bionic, struct stat64 has the same layout as struct stat.
NEST_EXPORT int fstatat64(int dirfd, const char* path, struct stat64* st, int flags) {
    return fstatat(dirfd, path, reinterpret_cast<struct stat*>(st), flags);
}
NEST_EXPORT int stat64(const char* path, struct stat64* st) { return stat(path, reinterpret_cast<struct stat*>(st)); }
NEST_EXPORT int lstat64(const char* path, struct stat64* st) { return lstat(path, reinterpret_cast<struct stat*>(st)); }

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
    long r = syscall(SYS_mmap, addr, len, prot, flags, fd, off);
    return r == -1 ? MAP_FAILED : reinterpret_cast<void*>(r);
}
NEST_EXPORT void* mmap64(void* addr, size_t len, int prot, int flags, int fd, off64_t off) {
    return mmap(addr, len, prot, flags, fd, static_cast<off_t>(off));
}

NEST_EXPORT int close(int fd) {
    if (nest::vbinder::clientIsBinderFd(fd)) nest::vbinder::clientClose(fd);
    long r = syscall(SYS_close, fd);
    if (r == -1 && errno == EINTR) return 0;  // the fd is gone either way (bionic semantics)
    return static_cast<int>(r);
}

}  // extern "C"
