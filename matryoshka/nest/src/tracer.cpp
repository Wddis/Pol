// SPDX-License-Identifier: Apache-2.0
#include "tracer.h"

#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <set>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>

#include "arch.h"
#include "syscalls_compat.h"
#include "fastpath.h"
#include "vfs_share.h"

#ifndef PTRACE_EVENT_SECCOMP
#define PTRACE_EVENT_SECCOMP 7
#endif
#ifndef PTRACE_O_TRACESECCOMP
#define PTRACE_O_TRACESECCOMP (1 << PTRACE_EVENT_SECCOMP)
#endif
#ifndef PTRACE_O_EXITKILL
#define PTRACE_O_EXITKILL (1 << 20)
#endif
#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif

namespace nest {

namespace {

#if defined(__x86_64__)
constexpr uint32_t kAuditArch = AUDIT_ARCH_X86_64;
#else
constexpr uint32_t kAuditArch = AUDIT_ARCH_AARCH64;
#endif

enum class Kind {
    Path,       // dirfd/path pairs to translate
    Exec,
    GetCwd,
    ReadLink,
    Sock,       // connect/bind with AF_UNIX path
    FakeZero,   // skip syscall, return 0 (privileged no-ops)
    Ids,        // uid/gid get/set family, answered from the virtual credentials
    Emulate,    // privileged calls a guest init needs (mount, pivot_root, ...)
    FakeChown,  // translate then skip, return 0
};

constexpr int NONE = -1;
constexpr int CWD = -2;  // path argument is relative to the cwd (no dirfd argument)

struct Spec {
    long nr;
    Kind kind;
    int dirfd1 = NONE, path1 = NONE;
    int dirfd2 = NONE, path2 = NONE;
    // Last component is followed unless: noFollowFlagArg has noFollowMask (or lacks followMask).
    int flagArg = NONE;
    long noFollowMask = 0;
    long followMask = 0;
    bool followDefault = true;
    long logical = -1;  // native number with the same meaning (handlers dispatch on it)
};

std::vector<Spec> buildSpecs() {
    std::vector<Spec> s;
    auto path = [&](long nr, int dfd, int p, bool follow = true, int flagArg = NONE, long nofollow = 0) {
        Spec x{nr, Kind::Path, dfd, p};
        x.followDefault = follow;
        x.flagArg = flagArg;
        x.noFollowMask = nofollow;
        s.push_back(x);
    };
    path(SYS_openat, 0, 1, true, 2, O_NOFOLLOW);
    path(SYS_newfstatat, 0, 1, true, 3, AT_SYMLINK_NOFOLLOW);
    path(SYS_statx, 0, 1, true, 2, AT_SYMLINK_NOFOLLOW);
    path(SYS_faccessat, 0, 1);
#ifdef SYS_faccessat2
    path(SYS_faccessat2, 0, 1, true, 3, AT_SYMLINK_NOFOLLOW);
#endif
    path(SYS_mkdirat, 0, 1, false);
    path(SYS_mknodat, 0, 1, false);
    path(SYS_unlinkat, 0, 1, false);
    path(SYS_fchmodat, 0, 1);
    path(SYS_utimensat, 0, 1, true, 3, AT_SYMLINK_NOFOLLOW);
    path(SYS_chdir, CWD, 0);
    path(SYS_truncate, CWD, 0);
    path(SYS_statfs, CWD, 0);
    path(SYS_getxattr, CWD, 0);
    path(SYS_lgetxattr, CWD, 0, false);
    path(SYS_setxattr, CWD, 0);
    path(SYS_lsetxattr, CWD, 0, false);
    path(SYS_listxattr, CWD, 0);
    path(SYS_llistxattr, CWD, 0, false);
    path(SYS_removexattr, CWD, 0);
    path(SYS_lremovexattr, CWD, 0, false);
    path(SYS_inotify_add_watch, NONE, NONE);  // handled below (arg1 is the path)
    s.back().dirfd1 = CWD;
    s.back().path1 = 1;
    {
        Spec x{SYS_renameat, Kind::Path, 0, 1, 2, 3};
        x.followDefault = false;
        s.push_back(x);
    }
#ifdef SYS_renameat2
    {
        Spec x{SYS_renameat2, Kind::Path, 0, 1, 2, 3};
        x.followDefault = false;
        s.push_back(x);
    }
#endif
    {
        Spec x{SYS_linkat, Kind::Path, 0, 1, 2, 3};
        x.followDefault = false;
        s.push_back(x);
    }
    {
        Spec x{SYS_symlinkat, Kind::Path, NONE, NONE, 1, 2};  // arg0 is link content: untouched
        x.followDefault = false;
        s.push_back(x);
    }
    s.push_back({SYS_readlinkat, Kind::ReadLink, 0, 1});
    s.back().followDefault = false;
    s.push_back({SYS_execve, Kind::Exec, CWD, 0});
    s.push_back({SYS_getcwd, Kind::GetCwd});
    s.push_back({SYS_connect, Kind::Sock});
    s.push_back({SYS_bind, Kind::Sock});
    {
        Spec x{SYS_fchownat, Kind::FakeChown, 0, 1};
        x.flagArg = 4;
        x.noFollowMask = AT_SYMLINK_NOFOLLOW;
        s.push_back(x);
    }
    s.push_back({SYS_fchown, Kind::FakeZero});
    for (long nr : {SYS_mount, SYS_umount2, SYS_pivot_root, SYS_chroot, SYS_sethostname, SYS_setdomainname,
                    SYS_swapon, SYS_swapoff, SYS_acct, SYS_reboot, SYS_unshare, SYS_setns, SYS_init_module,
                    SYS_finit_module, SYS_delete_module})
        s.push_back({nr, Kind::Emulate});
#if defined(__x86_64__)
    path(SYS_open, CWD, 0, true, 1, O_NOFOLLOW);
    path(SYS_creat, CWD, 0);
    path(SYS_stat, CWD, 0);
    path(SYS_lstat, CWD, 0, false);
    path(SYS_access, CWD, 0);
    path(SYS_mkdir, CWD, 0, false);
    path(SYS_rmdir, CWD, 0, false);
    path(SYS_unlink, CWD, 0, false);
    path(SYS_chmod, CWD, 0);
    path(SYS_mknod, CWD, 0, false);
    path(SYS_utimes, CWD, 0);
    path(SYS_utime, CWD, 0);
    {
        Spec x{SYS_rename, Kind::Path, CWD, 0, CWD, 1};
        x.followDefault = false;
        s.push_back(x);
    }
    {
        Spec x{SYS_link, Kind::Path, CWD, 0, CWD, 1};
        x.followDefault = false;
        s.push_back(x);
    }
    {
        Spec x{SYS_symlink, Kind::Path, NONE, NONE, CWD, 1};
        x.followDefault = false;
        s.push_back(x);
    }
    s.push_back({SYS_readlink, Kind::ReadLink, CWD, 0});
    s.back().followDefault = false;
    s.push_back({SYS_chown, Kind::FakeChown, CWD, 0});
    s.push_back({SYS_lchown, Kind::FakeChown, CWD, 0});
    s.back().followDefault = false;
#endif
    for (long nr : {SYS_getuid, SYS_geteuid, SYS_getgid, SYS_getegid, SYS_getresuid, SYS_getresgid,
                    SYS_setuid, SYS_setgid, SYS_setreuid, SYS_setregid, SYS_setresuid, SYS_setresgid,
                    SYS_setfsuid, SYS_setfsgid, SYS_setgroups})
        s.push_back({nr, Kind::Ids});
    return s;
}

#if defined(__aarch64__)
namespace C = sys32::arm;
constexpr uint32_t kAuditArchCompat = AUDIT_ARCH_ARM;
#else
namespace C = sys32::i386;
constexpr uint32_t kAuditArchCompat = AUDIT_ARCH_I386;
#endif

// Same semantics for 32-bit processes. Their syscall numbers differ, some calls have *64 / *32
// variants, and legacy path calls (open, stat64, ...) exist. `logical` maps each to a native
// number the handlers understand (or a private id for legacy calls arm64 lacks).
std::vector<Spec> buildCompatSpecs() {
    std::vector<Spec> s;
    constexpr long kPrivate = 100000;
    auto add = [&](long nr, long logical, Kind k, int dfd1 = NONE, int p1 = NONE, int dfd2 = NONE, int p2 = NONE) {
        if (nr < 0) return;
        Spec x{nr, k, dfd1, p1, dfd2, p2};
        x.logical = logical >= 0 ? logical : kPrivate + nr;
        s.push_back(x);
    };
    auto path = [&](long nr, long logical, int dfd, int p, bool follow = true, int flagArg = NONE, long nofollow = 0) {
        add(nr, logical, Kind::Path, dfd, p);
        if (nr < 0) return;
        s.back().followDefault = follow;
        s.back().flagArg = flagArg;
        s.back().noFollowMask = nofollow;
    };
#if defined(__x86_64__)
    constexpr long kOpen = SYS_open, kCreat = SYS_creat, kStat = SYS_stat, kLstat = SYS_lstat, kAccess = SYS_access,
                   kMkdir = SYS_mkdir, kRmdir = SYS_rmdir, kUnlink = SYS_unlink, kChmod = SYS_chmod,
                   kMknod = SYS_mknod, kUtimes = SYS_utimes, kRename = SYS_rename, kLink = SYS_link,
                   kSymlink = SYS_symlink, kReadlink = SYS_readlink, kChown = SYS_chown, kLchown = SYS_lchown;
#else
    constexpr long kOpen = -1, kCreat = -1, kStat = -1, kLstat = -1, kAccess = -1, kMkdir = -1, kRmdir = -1,
                   kUnlink = -1, kChmod = -1, kMknod = -1, kUtimes = -1, kRename = -1, kLink = -1, kSymlink = -1,
                   kReadlink = -1, kChown = -1, kLchown = -1;
#endif
    path(C::openat, SYS_openat, 0, 1, true, 2, O_NOFOLLOW);
    path(C::fstatat64, SYS_newfstatat, 0, 1, true, 3, AT_SYMLINK_NOFOLLOW);
    path(C::statx, SYS_statx, 0, 1, true, 2, AT_SYMLINK_NOFOLLOW);
    path(C::faccessat, SYS_faccessat, 0, 1);
    path(C::faccessat2, C::faccessat2 >= 0 ? 439 : -1, 0, 1, true, 3, AT_SYMLINK_NOFOLLOW);
    path(C::mkdirat, SYS_mkdirat, 0, 1, false);
    path(C::mknodat, SYS_mknodat, 0, 1, false);
    path(C::unlinkat, SYS_unlinkat, 0, 1, false);
    path(C::fchmodat, SYS_fchmodat, 0, 1);
    path(C::utimensat, SYS_utimensat, 0, 1, true, 3, AT_SYMLINK_NOFOLLOW);
    path(C::chdir, SYS_chdir, CWD, 0);
    path(C::truncate, SYS_truncate, CWD, 0);
    path(C::truncate64, -1, CWD, 0);
    path(C::statfs, SYS_statfs, CWD, 0);
    path(C::statfs64, -1, CWD, 0);
    path(C::getxattr, SYS_getxattr, CWD, 0);
    path(C::lgetxattr, SYS_lgetxattr, CWD, 0, false);
    path(C::setxattr, SYS_setxattr, CWD, 0);
    path(C::lsetxattr, SYS_lsetxattr, CWD, 0, false);
    path(C::listxattr, SYS_listxattr, CWD, 0);
    path(C::llistxattr, SYS_llistxattr, CWD, 0, false);
    path(C::removexattr, SYS_removexattr, CWD, 0);
    path(C::lremovexattr, SYS_lremovexattr, CWD, 0, false);
    path(C::inotify_add_watch, SYS_inotify_add_watch, CWD, 1);
    add(C::renameat, SYS_renameat, Kind::Path, 0, 1, 2, 3);
    if (C::renameat >= 0) s.back().followDefault = false;
    add(C::renameat2, 316 /* any distinct id */, Kind::Path, 0, 1, 2, 3);
    if (C::renameat2 >= 0) s.back().followDefault = false;
    add(C::linkat, SYS_linkat, Kind::Path, 0, 1, 2, 3);
    s.back().followDefault = false;
    add(C::symlinkat, SYS_symlinkat, Kind::Path, NONE, NONE, 1, 2);
    s.back().followDefault = false;
    add(C::readlinkat, SYS_readlinkat, Kind::ReadLink, 0, 1);
    s.back().followDefault = false;
    add(C::execve, SYS_execve, Kind::Exec, CWD, 0);
    add(C::getcwd, SYS_getcwd, Kind::GetCwd);
    add(C::connect, SYS_connect, Kind::Sock);
    add(C::bind, SYS_bind, Kind::Sock);
    add(C::fchownat, SYS_fchownat, Kind::FakeChown, 0, 1);
    s.back().flagArg = 4;
    s.back().noFollowMask = AT_SYMLINK_NOFOLLOW;
    add(C::fchown32, SYS_fchown, Kind::FakeZero);
    // Legacy path calls.
    path(C::open, kOpen, CWD, 0, true, 1, O_NOFOLLOW);
    path(C::creat, kCreat, CWD, 0);
    path(C::stat64, kStat, CWD, 0);
    path(C::lstat64, kLstat, CWD, 0, false);
    path(C::access, kAccess, CWD, 0);
    path(C::mkdir, kMkdir, CWD, 0, false);
    path(C::rmdir, kRmdir, CWD, 0, false);
    path(C::unlink, kUnlink, CWD, 0, false);
    path(C::chmod, kChmod, CWD, 0);
    path(C::mknod, kMknod, CWD, 0, false);
    path(C::utimes, kUtimes, CWD, 0);
    add(C::rename, kRename, Kind::Path, CWD, 0, CWD, 1);
    s.back().followDefault = false;
    add(C::link, kLink, Kind::Path, CWD, 0, CWD, 1);
    s.back().followDefault = false;
    add(C::symlink, kSymlink, Kind::Path, NONE, NONE, CWD, 1);
    s.back().followDefault = false;
    add(C::readlink, kReadlink, Kind::ReadLink, CWD, 0);
    s.back().followDefault = false;
    add(C::chown32, kChown, Kind::FakeChown, CWD, 0);
    add(C::lchown32, kLchown, Kind::FakeChown, CWD, 0);
    s.back().followDefault = false;
    // Privileged / identity calls.
    add(C::mount, SYS_mount, Kind::Emulate);
    add(C::umount2, SYS_umount2, Kind::Emulate);
    add(C::pivot_root, SYS_pivot_root, Kind::Emulate);
    add(C::chroot, SYS_chroot, Kind::Emulate);
    add(C::sethostname, SYS_sethostname, Kind::Emulate);
    add(C::setdomainname, SYS_setdomainname, Kind::Emulate);
    add(C::swapon, SYS_swapon, Kind::Emulate);
    add(C::swapoff, SYS_swapoff, Kind::Emulate);
    add(C::acct, SYS_acct, Kind::Emulate);
    add(C::reboot, SYS_reboot, Kind::Emulate);
    add(C::unshare, SYS_unshare, Kind::Emulate);
    add(C::setns, SYS_setns, Kind::Emulate);
    add(C::init_module, SYS_init_module, Kind::Emulate);
    add(C::finit_module, SYS_finit_module, Kind::Emulate);
    add(C::delete_module, SYS_delete_module, Kind::Emulate);
    add(C::getuid32, SYS_getuid, Kind::Ids);
    add(C::geteuid32, SYS_geteuid, Kind::Ids);
    add(C::getgid32, SYS_getgid, Kind::Ids);
    add(C::getegid32, SYS_getegid, Kind::Ids);
    add(C::getresuid32, SYS_getresuid, Kind::Ids);
    add(C::getresgid32, SYS_getresgid, Kind::Ids);
    add(C::setuid32, SYS_setuid, Kind::Ids);
    add(C::setgid32, SYS_setgid, Kind::Ids);
    add(C::setreuid32, SYS_setreuid, Kind::Ids);
    add(C::setregid32, SYS_setregid, Kind::Ids);
    add(C::setresuid32, SYS_setresuid, Kind::Ids);
    add(C::setresgid32, SYS_setresgid, Kind::Ids);
    add(C::setfsuid32, SYS_setfsuid, Kind::Ids);
    add(C::setfsgid32, SYS_setfsgid, Kind::Ids);
    add(C::setgroups32, SYS_setgroups, Kind::Ids);
    return s;
}

const std::vector<Spec>& specs() {
    static const std::vector<Spec> s = [] {
        auto v = buildSpecs();
        for (auto& x : v) x.logical = x.nr;
        return v;
    }();
    return s;
}

const std::vector<Spec>& compatSpecs() {
    static const std::vector<Spec> s = buildCompatSpecs();
    return s;
}

const Spec* findSpec(long nr, bool compat = false) {
    for (const auto& s : compat ? compatSpecs() : specs())
        if (s.nr == nr) return &s;
    return nullptr;
}

}  // namespace

bool installTraceFilter() {
    std::vector<sock_filter> f;
    auto lo = [](uint64_t v) { return static_cast<uint32_t>(v & 0xffffffffu); };
    auto hi = [](uint64_t v) { return static_cast<uint32_t>(v >> 32); };
    // Block for one ABI: fast-path tag -> allow (except execve), listed syscalls -> trace.
    auto block = [&](const std::vector<Spec>& sp, long execveNr, bool compat) {
        f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[5])));
        if (compat) {
            // 32-bit registers: only the low word of the tag fits.
            f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, lo(kFastPathTag), 0, 3));
        } else {
            f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, lo(kFastPathTag), 0, 5));
            f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[5]) + 4));
            f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, hi(kFastPathTag), 0, 3));
        }
        f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)));
        f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<uint32_t>(execveNr), 1, 0));
        f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
        f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)));
        for (const auto& s : sp) {
            f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<uint32_t>(s.nr), 0, 1));
            f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE));
        }
        f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    };
    f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch)));
    size_t archJump = f.size();
    f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 0, 0));  // patched below
    // compat (32-bit) or unknown arch
    f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArchCompat, 1, 0));
    f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    block(compatSpecs(), C::execve, true);
    size_t nativeStart = f.size();
    block(specs(), SYS_execve, false);
    f[archJump].jt = static_cast<uint8_t>(nativeStart - archJump - 1);
    if (nativeStart - archJump - 1 > 255) return false;  // BPF jump range
    sock_fprog prog{static_cast<unsigned short>(f.size()), f.data()};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return false;
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) == 0;
}

Tracer::Tracer(TracerConfig cfg) : cfg_(std::move(cfg)) {}

void Tracer::logf(int level, const char* fmt, ...) {
    if (cfg_.verbose < level) return;
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf) - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > static_cast<int>(sizeof(buf)) - 2) n = sizeof(buf) - 2;
    buf[n++] = '\n';
    ssize_t ignored = write(cfg_.logFd, buf, n);
    (void)ignored;
}

uint64_t Tracer::pushBytes(pid_t tid, uint64_t& scratch, const void* data, size_t len) {
    scratch -= (len + 15) & ~static_cast<uint64_t>(15);
    if (!writeMem(tid, scratch, data, len)) return 0;
    return scratch;
}

// "/proc/self" seen by the tracee is not the tracer's /proc/self: pin it to the tracee's pid.
static std::string fixProcSelf(pid_t tid, const std::string& guestPath) {
    auto starts = [&](const char* p) {
        size_t n = strlen(p);
        return guestPath.compare(0, n, p) == 0 && (guestPath.size() == n || guestPath[n] == '/');
    };
    if (!starts("/proc/self") && !starts("/proc/thread-self")) return guestPath;
    pid_t tgid = tid;
    char status[64];
    snprintf(status, sizeof(status), "/proc/%d/status", tid);
    if (FILE* f = fopen(status, "r")) {
        char line[128];
        while (fgets(line, sizeof(line), f))
            if (sscanf(line, "Tgid: %d", &tgid) == 1) break;
        fclose(f);
    }
    if (starts("/proc/self")) return "/proc/" + std::to_string(tgid) + guestPath.substr(strlen("/proc/self"));
    return "/proc/" + std::to_string(tgid) + "/task/" + std::to_string(tid) +
           guestPath.substr(strlen("/proc/thread-self"));
}

std::string Tracer::guestCwdOf(pid_t tid) {
    char link[64], buf[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/%d/cwd", tid);
    ssize_t n = readlink(link, buf, sizeof(buf) - 1);
    if (n <= 0) return cfg_.guestCwd;
    buf[n] = 0;
    std::string guest;
    if (cfg_.vfs.reverse(buf, guest)) return guest;
    return buf;
}

std::string Tracer::guestFdPath(pid_t tid, int fd) {
    char link[64], buf[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/%d/fd/%d", tid, fd);
    ssize_t n = readlink(link, buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    buf[n] = 0;
    std::string guest;
    if (cfg_.vfs.reverse(buf, guest)) return guest;
    return {};
}

bool Tracer::rewritePath(pid_t tid, Regs& r, int dirfdArg, int pathArg, bool followLast, uint64_t& scratch,
                         std::string* guestOut) {
    if (pathArg < 0 || r.arg[pathArg] == 0) return false;
    char path[PATH_MAX];
    if (!readString(tid, r.arg[pathArg], path, sizeof(path))) return false;
    if (path[0] == 0) return false;  // AT_EMPTY_PATH style call: operates on the fd itself

    std::string base;
    if (path[0] != '/') {
        int dirfd = dirfdArg >= 0 ? static_cast<int>(r.arg[dirfdArg]) : AT_FDCWD;
        if (dirfd == AT_FDCWD) {
            base = guestCwdOf(tid);
        } else {
            base = guestFdPath(tid, dirfd);
            if (base.empty()) return false;  // fd outside the guest view: let the kernel resolve it
        }
    }
    std::string full = Vfs::join(base.empty() ? "/" : base, path);
    if (full.compare(0, 6, "/proc/") == 0) {
        full = fixProcSelf(tid, full);
        if (!cfg_.procAttrDir.empty()) {
            // /proc/<pid>[/task/<tid>]/attr/<name>  ->  <procAttrDir>/<name>
            size_t a = full.find("/attr/");
            if (a != std::string::npos && full.find('/', a + 6) == std::string::npos) {
                bool ok = true;
                for (size_t i = 6; i < a; i++)
                    if (!isdigit(static_cast<unsigned char>(full[i])) && full[i] != '/' &&
                        full.compare(i, 4, "task") != 0) {
                        if (full.compare(i - 1, 5, "/task") != 0) ok = false;
                    }
                if (ok) full = cfg_.procAttrDir + full.substr(a + 5);
            }
        }
    }
    if (path[strlen(path) - 1] == '/' && full != "/") full += '/';
    std::string host, guest;
    if (!cfg_.vfs.resolve(full, "/", followLast, host, &guest)) return false;
    if (guestOut) *guestOut = guest;
    if (host == path) return true;  // identity mapping, nothing to rewrite
    uint64_t addr = pushBytes(tid, scratch, host.c_str(), host.size() + 1);
    if (!addr) return false;
    r.arg[pathArg] = addr;
    stats_.pathRewrites++;
    logf(2, "[%d] %s -> %s", tid, path, host.c_str());
    return true;
}

bool Tracer::rewriteSockaddr(pid_t tid, Regs& r, uint64_t& scratch) {
    socklen_t len = static_cast<socklen_t>(r.arg[2]);
    if (r.arg[1] == 0 || len <= offsetof(sockaddr_un, sun_path) || len > sizeof(sockaddr_un)) return false;
    sockaddr_un sa{};
    if (!readMem(tid, r.arg[1], &sa, len)) return false;
    if (sa.sun_family != AF_UNIX || sa.sun_path[0] == 0) return false;  // abstract sockets: untouched
    std::string p(sa.sun_path, strnlen(sa.sun_path, len - offsetof(sockaddr_un, sun_path)));
    std::string base = p[0] == '/' ? "/" : guestCwdOf(tid);
    std::string host;
    if (!cfg_.vfs.resolve(p, base, true, host) || host == p) return false;
    if (host.size() >= sizeof(sa.sun_path)) {
        logf(1, "[%d] socket path too long for host: %s", tid, host.c_str());
        return false;
    }
    sockaddr_un out{};
    out.sun_family = AF_UNIX;
    memcpy(out.sun_path, host.c_str(), host.size() + 1);
    socklen_t outLen = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + host.size() + 1);
    uint64_t addr = pushBytes(tid, scratch, &out, outLen);
    if (!addr) return false;
    r.arg[1] = addr;
    r.arg[2] = outLen;
    stats_.pathRewrites++;
    return true;
}

namespace {

// Reads PT_INTERP of an ELF file, or the "#!" line of a script.
struct ExecInfo {
    bool script = false;
    bool is32 = false;      // ELF32 (arm32 / i386)
    std::string interp;     // ELF interpreter or script interpreter (guest path)
    std::string scriptArg;  // optional single argument of a "#!" line
};

bool inspectExecutable(const std::string& hostPath, ExecInfo& info) {
    int fd = open(hostPath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    unsigned char head[256];
    ssize_t n = pread(fd, head, sizeof(head), 0);
    bool ok = false;
    if (n >= 2 && head[0] == '#' && head[1] == '!') {
        std::string line(reinterpret_cast<char*>(head) + 2, static_cast<size_t>(n - 2));
        line = line.substr(0, line.find('\n'));
        size_t a = line.find_first_not_of(" \t");
        if (a != std::string::npos) {
            line = line.substr(a);
            size_t b = line.find_first_of(" \t");
            info.script = true;
            info.interp = line.substr(0, b);
            if (b != std::string::npos) {
                std::string arg = line.substr(b);
                size_t s = arg.find_first_not_of(" \t");
                size_t e = arg.find_last_not_of(" \t\r");
                if (s != std::string::npos) info.scriptArg = arg.substr(s, e - s + 1);
            }
            ok = true;
        }
    } else if (n >= static_cast<ssize_t>(sizeof(Elf64_Ehdr)) && memcmp(head, ELFMAG, SELFMAG) == 0 &&
               head[EI_CLASS] == ELFCLASS64) {
        Elf64_Ehdr eh;
        memcpy(&eh, head, sizeof(eh));
        for (int i = 0; i < eh.e_phnum && i < 64; i++) {
            Elf64_Phdr ph;
            if (pread(fd, &ph, sizeof(ph), eh.e_phoff + static_cast<off_t>(i) * eh.e_phentsize) != sizeof(ph)) break;
            if (ph.p_type != PT_INTERP || ph.p_filesz == 0 || ph.p_filesz > PATH_MAX) continue;
            std::string interp(ph.p_filesz, '\0');
            if (pread(fd, &interp[0], ph.p_filesz, ph.p_offset) == static_cast<ssize_t>(ph.p_filesz)) {
                interp.resize(strnlen(interp.c_str(), interp.size()));
                info.interp = interp;
            }
            break;
        }
        ok = true;
    } else if (n >= static_cast<ssize_t>(sizeof(Elf32_Ehdr)) && memcmp(head, ELFMAG, SELFMAG) == 0 &&
               head[EI_CLASS] == ELFCLASS32) {
        info.is32 = true;
        Elf32_Ehdr eh;
        memcpy(&eh, head, sizeof(eh));
        for (int i = 0; i < eh.e_phnum && i < 64; i++) {
            Elf32_Phdr ph;
            if (pread(fd, &ph, sizeof(ph), eh.e_phoff + static_cast<off_t>(i) * eh.e_phentsize) != sizeof(ph)) break;
            if (ph.p_type != PT_INTERP || ph.p_filesz == 0 || ph.p_filesz > PATH_MAX) continue;
            std::string interp(ph.p_filesz, '\0');
            if (pread(fd, &interp[0], ph.p_filesz, ph.p_offset) == static_cast<ssize_t>(ph.p_filesz)) {
                interp.resize(strnlen(interp.c_str(), interp.size()));
                info.interp = interp;
            }
            break;
        }
        ok = true;
    }
    close(fd);
    return ok;
}

}  // namespace

std::string Tracer::guestExeOf(pid_t tid) {
    std::lock_guard<std::mutex> g(credsMu_);
    auto it = exe_.find(tgidOf(tid));
    return it == exe_.end() ? std::string() : it->second;
}

// LD_PRELOAD entry for a program. Android's linker only loads libraries from directories its
// namespace permits, so the library is exposed next to the program's own libraries.
std::string Tracer::preloadEntryFor(const std::string& prog, bool is32) {
    const std::string& lib = is32 ? cfg_.preload32Host : cfg_.preloadHost;
    if (lib.empty()) return {};
    if (!cfg_.androidPreload) return "LD_PRELOAD=" + lib;
    const char* libdir = is32 ? "/lib" : "/lib64";
    std::string dir = std::string("/system") + libdir;
    if (prog.rfind("/vendor/", 0) == 0 || prog.rfind("/odm/", 0) == 0) {
        dir = std::string("/vendor") + libdir;
    } else if (prog.rfind("/apex/", 0) == 0) {
        size_t e = prog.find('/', 6);
        if (e != std::string::npos) dir = prog.substr(0, e) + libdir;
    }
    std::string guest = dir + "/libnest_preload.so";
    const Mount* m = nullptr;
    cfg_.vfs.mapLexical(guest, &m);
    if (!m || m->guest != guest) {
        std::string host;
        if (cfg_.vfs.resolve(guest, "/", false, host)) ensureMountPoint(host, false);
        // One host copy per location: the linker checks the library's real path against the
        // namespace, so /vendor/lib64/... must not reverse-map to /system/lib64/....
        std::string copy = cfg_.stateDir + "/preload";
        mkdir(copy.c_str(), 0755);
        std::string name = guest.substr(1);
        for (auto& c : name)
            if (c == '/') c = '_';
        copy += "/" + name;
        struct stat st;
        if (stat(copy.c_str(), &st) != 0) {
            int in = open(lib.c_str(), O_RDONLY | O_CLOEXEC);
            int out = open((copy + ".tmp").c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
            char buf[65536];
            ssize_t n;
            while (in >= 0 && out >= 0 && (n = read(in, buf, sizeof(buf))) > 0)
                if (write(out, buf, static_cast<size_t>(n)) != n) break;
            if (in >= 0) close(in);
            if (out >= 0) close(out);
            rename((copy + ".tmp").c_str(), copy.c_str());
        }
        cfg_.vfs.bind(guest, copy, true);
        publishVfs();
    }
    return "LD_PRELOAD=" + guest;
}

bool Tracer::rewriteExec(pid_t tid, Regs& r, uint64_t& scratch) {
    stats_.execs++;
    const size_t ps = r.compat ? 4 : 8;  // pointer size of the calling process
    auto readPtr = [&](uint64_t addr, uint64_t& out) {
        out = 0;
        return readMem(tid, addr, &out, ps);
    };
    auto pushPtrs = [&](const std::vector<uint64_t>& ptrs) -> uint64_t {
        if (ps == 8) return pushBytes(tid, scratch, ptrs.data(), ptrs.size() * 8);
        std::vector<uint32_t> p32(ptrs.begin(), ptrs.end());
        return pushBytes(tid, scratch, p32.data(), p32.size() * 4);
    };
    char path[PATH_MAX];
    if (!r.arg[0] || !readString(tid, r.arg[0], path, sizeof(path))) return false;
    // Re-exec of itself (init does this for subcontexts): /proc/self/exe is the loader for
    // programs started through it, so substitute the real guest program.
    {
        std::string p = fixProcSelf(tid, path);
        if (p.rfind("/proc/", 0) == 0 && p.size() > 10 && p.compare(p.size() - 4, 4, "/exe") == 0) {
            pid_t pid = static_cast<pid_t>(atoi(p.c_str() + 6));
            std::string exe = guestExeOf(pid);
            if (!exe.empty()) snprintf(path, sizeof(path), "%s", exe.c_str());
        }
    }
    // Re-exec of the bare loader (e.g. via AT_EXECFN) without a program argument: the process
    // means its own program.
    {
        std::string exe = guestExeOf(tid);
        uint64_t a1 = 0;
        char arg1[8] = {};
        bool arg1Abs = r.arg[1] && readPtr(r.arg[1] + ps, a1) && a1 && readString(tid, a1, arg1, sizeof(arg1)) &&
                       arg1[0] == '/';
        std::string host;
        if (!exe.empty() && !arg1Abs && cfg_.vfs.resolve(path, "/", true, host)) {
            ExecInfo pi;
            std::string exeHost;
            if (cfg_.vfs.resolve(exe, "/", true, exeHost) && inspectExecutable(exeHost, pi) && !pi.interp.empty()) {
                std::string interpHost;
                if (cfg_.vfs.resolve(pi.interp, "/", true, interpHost) && interpHost == host)
                    snprintf(path, sizeof(path), "%s", exe.c_str());
            }
        }
    }
    std::string progGuest = Vfs::join(path[0] == '/' ? "/" : guestCwdOf(tid), path);
    std::vector<std::string> inject = cfg_.injectEnv;
    bool prog32 = false;
    {
        std::string h;
        ExecInfo pi;
        if (cfg_.vfs.resolve(progGuest, "/", true, h) && inspectExecutable(h, pi)) {
            // Scripts: the interpreter decides; resolve one level.
            if (pi.script) {
                ExecInfo ii;
                std::string ih;
                if (cfg_.vfs.resolve(pi.interp, "/", true, ih) && inspectExecutable(ih, ii)) prog32 = ii.is32;
            } else {
                prog32 = pi.is32;
            }
        }
    }
    std::string pre = preloadEntryFor(progGuest, prog32);
    if (!pre.empty()) inject.push_back(pre);
    {
        std::lock_guard<std::mutex> g(credsMu_);
        pendingExe_[tid] = progGuest;
    }

    // Original argv (pointers stay valid in the tracee; we only read them).
    std::vector<uint64_t> argvPtrs;
    for (int i = 0; i < 4096 && r.arg[1]; i++) {
        uint64_t p = 0;
        if (!readPtr(r.arg[1] + i * ps, p)) return false;
        if (!p) break;
        argvPtrs.push_back(p);
    }

    // Environment: make sure the injected entries are present (replace same-named ones).
    bool envChanged = false;
    std::vector<uint64_t> envPtrs;
    if (!inject.empty()) {
        for (int i = 0; i < 8192 && r.arg[2]; i++) {
            uint64_t p = 0;
            if (!readPtr(r.arg[2] + i * ps, p) || !p) break;
            char e[512];
            if (readString(tid, p, e, sizeof(e))) {
                bool replaced = false, same = false;
                for (const auto& inj : inject) {
                    size_t k = inj.find('=') + 1;
                    if (strncmp(e, inj.c_str(), k) == 0) {
                        replaced = true;
                        same = inj == e;
                    }
                }
                if (replaced && !same) { envChanged = true; continue; }
                if (replaced && same) continue;  // re-added below, keeps a single copy
            }
            envPtrs.push_back(p);
        }
        envChanged = true;  // always rebuild: cheap, and guarantees exactly one copy of each entry
    }
    auto applyEnv = [&]() -> bool {
        if (!envChanged) return true;
        std::vector<uint64_t> np = envPtrs;
        for (const auto& inj : inject) {
            uint64_t a = pushBytes(tid, scratch, inj.c_str(), inj.size() + 1);
            if (!a) return false;
            np.push_back(a);
        }
        np.push_back(0);
        uint64_t envAddr = pushPtrs(np);
        if (!envAddr) return false;
        r.arg[2] = envAddr;
        stats_.envInjections++;
        return true;
    };

    std::string base = path[0] == '/' ? "/" : guestCwdOf(tid);
    std::string hostProg, guestProg;
    if (!cfg_.vfs.resolve(path, base, true, hostProg, &guestProg)) return false;

    // Chain: script interpreter -> ELF -> ELF interpreter. Build new filename + argv prefix.
    std::vector<std::string> prefix;  // replaces argv[0]
    std::string target = hostProg;       // host file the kernel will execute
    // The program's guest path *as invoked* (symlinks not resolved): multi-call binaries such as
    // toybox pick the command from argv[0], so "/system/bin/cat" must not become ".../toybox".
    std::string targetGuest = Vfs::join(base, path);
    ExecInfo info;
    bool rewritten = false;
    for (int depth = 0; depth < 4 && inspectExecutable(target, info); depth++) {
        if (info.script) {
            std::string hostInterp;
            if (!cfg_.vfs.resolve(info.interp, "/", true, hostInterp)) break;
            // Kernel convention: interp [arg] script-path original-args...
            std::vector<std::string> np{info.interp};
            if (!info.scriptArg.empty()) np.push_back(info.scriptArg);
            np.push_back(prefix.empty() ? std::string(path) : prefix.front());
            for (size_t i = 1; i < prefix.size(); i++) np.push_back(prefix[i]);
            prefix = np;
            target = hostInterp;
            targetGuest = info.interp;
            info = ExecInfo{};
            rewritten = true;
            continue;
        }
        if (!info.interp.empty()) {
            // Only redirect when the guest's loader is a different file than the one the kernel
            // would pick from the host (on Android /system/bin/linker64 is itself a symlink).
            std::string hostInterp;
            char real[PATH_MAX];
            bool sameAsHost = realpath(info.interp.c_str(), real) != nullptr;
            if (cfg_.vfs.resolve(info.interp, "/", true, hostInterp) && !(sameAsHost && hostInterp == real)) {
                // Run the guest's own dynamic loader explicitly: "ld.so <program> args...". The
                // loader runs under translation too, so it gets the program's *guest* path.
                std::vector<std::string> np{info.interp, targetGuest};
                for (size_t i = 1; i < prefix.size(); i++) np.push_back(prefix[i]);
                prefix = np;
                target = hostInterp;
                targetGuest = info.interp;
                rewritten = true;
            }
        }
        break;
    }

    if (!rewritten) {
        if (!applyEnv()) return false;
        if (hostProg == path) return envChanged;
        uint64_t a = pushBytes(tid, scratch, hostProg.c_str(), hostProg.size() + 1);
        if (!a) return false;
        r.arg[0] = a;
        stats_.pathRewrites++;
        logf(1, "[%d] exec %s", tid, path);
        return true;
    }

    // Script paths stay guest paths: the interpreter itself runs under translation.
    std::vector<uint64_t> newArgv;
    for (const auto& s : prefix) {
        uint64_t a = pushBytes(tid, scratch, s.c_str(), s.size() + 1);
        if (!a) return false;
        newArgv.push_back(a);
    }
    for (size_t i = 1; i < argvPtrs.size(); i++) newArgv.push_back(argvPtrs[i]);
    newArgv.push_back(0);
    if (!applyEnv()) return false;
    uint64_t argvAddr = pushPtrs(newArgv);
    uint64_t fileAddr = pushBytes(tid, scratch, target.c_str(), target.size() + 1);
    if (!argvAddr || !fileAddr) return false;
    r.arg[0] = fileAddr;
    r.arg[1] = argvAddr;
    stats_.execRewrites++;
    logf(1, "[%d] exec %s via %s", tid, path, target.c_str());
    return true;
}

pid_t Tracer::tgidOf(pid_t tid) const {
    auto it = tgid_.find(tid);
    return it == tgid_.end() ? tid : it->second;
}

Tracer::Creds Tracer::credsOf(pid_t tid) const {
    std::lock_guard<std::mutex> g(credsMu_);
    auto it = creds_.find(tgidOf(tid));
    if (it != creds_.end()) return it->second;
    Creds c;
    c.ruid = c.euid = c.suid = c.fsuid = cfg_.initialUid;
    c.rgid = c.egid = c.sgid = c.fsgid = cfg_.initialGid;
    return c;
}

void Tracer::onNewTask(pid_t parent, pid_t tid) {
    pid_t tgid = tid;
    char status[64];
    snprintf(status, sizeof(status), "/proc/%d/status", tid);
    if (FILE* f = fopen(status, "r")) {
        char line[128];
        while (fgets(line, sizeof(line), f))
            if (sscanf(line, "Tgid: %d", &tgid) == 1) break;
        fclose(f);
    }
    Creds inherited = credsOf(parent);
    std::lock_guard<std::mutex> g(credsMu_);
    tgid_[tid] = tgid;
    if (tgid == tid) {
        creds_[tid] = inherited;  // new process inherits its parent's identity
        auto e = exe_.find(tgidOf(parent));
        if (e != exe_.end()) exe_[tid] = e->second;
    }
}

// uid/gid syscalls with the kernel's permission rules, applied to virtual credentials.
void Tracer::handleIds(pid_t tid, Regs& r, long nr) {
    stats_.faked++;
    Creds c = credsOf(tid);
    const uint32_t keep = 0xffffffffu;  // "-1": leave unchanged
    auto a = [&](int i) { return static_cast<uint32_t>(r.arg[i]); };
    long ret = 0;
    bool privileged = c.euid == 0;
    auto allowedU = [&](uint32_t v) { return privileged || v == c.ruid || v == c.euid || v == c.suid; };
    auto allowedG = [&](uint32_t v) { return privileged || v == c.rgid || v == c.egid || v == c.sgid; };

    if (nr == SYS_getuid) ret = c.ruid;
    else if (nr == SYS_geteuid) ret = c.euid;
    else if (nr == SYS_getgid) ret = c.rgid;
    else if (nr == SYS_getegid) ret = c.egid;
    else if (nr == SYS_getresuid || nr == SYS_getresgid) {
        uint32_t v[3] = {c.ruid, c.euid, c.suid};
        if (nr == SYS_getresgid) { v[0] = c.rgid; v[1] = c.egid; v[2] = c.sgid; }
        for (int i = 0; i < 3; i++)
            if (r.arg[i] && !writeMem(tid, r.arg[i], &v[i], sizeof(uint32_t))) ret = -EFAULT;
    } else if (nr == SYS_setuid) {
        uint32_t u = a(0);
        if (privileged) c.ruid = c.euid = c.suid = c.fsuid = u;
        else if (u == c.ruid || u == c.suid) c.euid = c.fsuid = u;
        else ret = -EPERM;
    } else if (nr == SYS_setgid) {
        uint32_t g = a(0);
        if (privileged) c.rgid = c.egid = c.sgid = c.fsgid = g;
        else if (g == c.rgid || g == c.sgid) c.egid = c.fsgid = g;
        else ret = -EPERM;
    } else if (nr == SYS_setresuid) {
        uint32_t nr_ = a(0), ne = a(1), ns = a(2);
        if ((nr_ != keep && !allowedU(nr_)) || (ne != keep && !allowedU(ne)) || (ns != keep && !allowedU(ns))) ret = -EPERM;
        else {
            if (nr_ != keep) c.ruid = nr_;
            if (ne != keep) c.euid = ne;
            if (ns != keep) c.suid = ns;
            c.fsuid = c.euid;
        }
    } else if (nr == SYS_setresgid) {
        uint32_t nr_ = a(0), ne = a(1), ns = a(2);
        if ((nr_ != keep && !allowedG(nr_)) || (ne != keep && !allowedG(ne)) || (ns != keep && !allowedG(ns))) ret = -EPERM;
        else {
            if (nr_ != keep) c.rgid = nr_;
            if (ne != keep) c.egid = ne;
            if (ns != keep) c.sgid = ns;
            c.fsgid = c.egid;
        }
    } else if (nr == SYS_setreuid) {
        uint32_t nr_ = a(0), ne = a(1);
        bool ok = (nr_ == keep || privileged || nr_ == c.ruid || nr_ == c.euid) && (ne == keep || allowedU(ne));
        if (!ok) ret = -EPERM;
        else {
            uint32_t oldR = c.ruid;
            if (nr_ != keep) c.ruid = nr_;
            if (ne != keep) c.euid = ne;
            if (nr_ != keep || (ne != keep && ne != oldR)) c.suid = c.euid;
            c.fsuid = c.euid;
        }
    } else if (nr == SYS_setregid) {
        uint32_t nr_ = a(0), ne = a(1);
        bool ok = (nr_ == keep || privileged || nr_ == c.rgid || nr_ == c.egid) && (ne == keep || allowedG(ne));
        if (!ok) ret = -EPERM;
        else {
            uint32_t oldR = c.rgid;
            if (nr_ != keep) c.rgid = nr_;
            if (ne != keep) c.egid = ne;
            if (nr_ != keep || (ne != keep && ne != oldR)) c.sgid = c.egid;
            c.fsgid = c.egid;
        }
    } else if (nr == SYS_setfsuid) {
        ret = c.fsuid;  // returns the previous value, never fails
        if (allowedU(a(0)) || a(0) == c.fsuid) c.fsuid = a(0);
    } else if (nr == SYS_setfsgid) {
        ret = c.fsgid;
        if (allowedG(a(0)) || a(0) == c.fsgid) c.fsgid = a(0);
    } else if (nr == SYS_setgroups) {
        ret = privileged ? 0 : -EPERM;
    }
    if (ret == -EPERM) stats_.denied++;
    {
        std::lock_guard<std::mutex> g(credsMu_);
        creds_[tgidOf(tid)] = c;
    }
    setSyscall(tid, r, -1);
    setReturn(tid, r, ret);
}

void Tracer::publishVfs() {
    if (share_) share_->publish(cfg_.vfs.serialize());
}

// Reads a path argument and resolves it to an absolute guest path (and host path).
bool Tracer::guestPathArg(pid_t tid, uint64_t addr, bool follow, std::string& guest, std::string* host) {
    char path[PATH_MAX];
    if (!addr || !readString(tid, addr, path, sizeof(path)) || !path[0]) return false;
    std::string base = path[0] == '/' ? "/" : guestCwdOf(tid);
    std::string h;
    if (!cfg_.vfs.resolve(path, base, follow, h, &guest)) return false;
    if (host) *host = h;
    return true;
}

// A mount point must exist in its parent directory, or directory listings (ls /apex, package
// scanners) would not show the mounted entry.
void ensureMountPoint(const std::string& hostPath, bool dir) {
    struct stat st;
    if (lstat(hostPath.c_str(), &st) == 0) return;
    if (dir) {
        mkdir(hostPath.c_str(), 0755);
    } else {
        int fd = open(hostPath.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
        if (fd >= 0) close(fd);
    }
}

long Tracer::doMount(pid_t tid, Regs& r) {
    stats_.mounts++;
    unsigned long flags = r.arg[3];
    std::string target, targetHost;
    if (!guestPathArg(tid, r.arg[1], true, target, &targetHost)) return -ENOENT;
    char fstype[64] = {};
    if (r.arg[2]) readString(tid, r.arg[2], fstype, sizeof(fstype));
    std::string fs = fstype;

    if (flags & MS_REMOUNT) return 0;
    if (flags & (MS_SHARED | MS_PRIVATE | MS_SLAVE | MS_UNBINDABLE)) return 0;  // propagation only
    if (flags & (MS_BIND | MS_MOVE)) {
        std::string src, srcHost;
        if (!guestPathArg(tid, r.arg[0], true, src, &srcHost)) return -ENOENT;
        struct stat st;
        if (stat(srcHost.c_str(), &st) != 0) return -ENOENT;
        ensureMountPoint(targetHost, S_ISDIR(st.st_mode));
        cfg_.vfs.bind(target, srcHost);
        if (flags & MS_MOVE) cfg_.vfs.unbind(src);
        logf(1, "[%d] mount %s %s -> %s", tid, (flags & MS_MOVE) ? "--move" : "--bind", src.c_str(), target.c_str());
        publishVfs();
        return 0;
    }
    std::string host;
    if (fs == "proc") host = "/proc";
    else if (fs == "sysfs") host = "/sys";
    else if (fs == "devpts") host = "/dev/pts";
    else if (fs == "selinuxfs") return 0;  // stays hidden: the guest sees SELinux as disabled
    else if (fs == "tmpfs" || fs == "ramfs" || fs == "cgroup" || fs == "cgroup2" || fs == "bpf" ||
             fs == "tracefs" || fs == "debugfs" || fs == "pstore" || fs == "configfs" || fs == "functionfs" ||
             fs == "binder") {
        // A fresh, empty, writable directory behind the target.
        host = cfg_.stateDir + "/mnt/" + std::to_string(++tmpfsCount_);
        std::string cur;
        for (size_t i = 1; i <= host.size(); i++)
            if (i == host.size() || host[i] == '/') mkdir(host.substr(0, i).c_str(), 0755);
    } else {
        logf(1, "[%d] mount of %s (%s) on %s ignored", tid, fstype, "block fs", target.c_str());
        return 0;  // real filesystems: the engine already provides the content
    }
    ensureMountPoint(targetHost, true);
    cfg_.vfs.bind(target, host);
    logf(1, "[%d] mount -t %s %s", tid, fstype, target.c_str());
    publishVfs();
    return 0;
}

long Tracer::doUmount(pid_t tid, Regs& r) {
    std::string target;
    if (!guestPathArg(tid, r.arg[0], false, target)) return -ENOENT;
    if (cfg_.vfs.unbind(target)) publishVfs();
    return 0;
}

// Privileged operations a guest Android needs. Returns the syscall result; `handled` false means
// "let the kernel run it" (only possible on the seccomp-stop path).
long Tracer::emulate(pid_t tid, Regs& r, long nr, bool& handled) {
    handled = true;
    stats_.emulated++;
    bool root = credsOf(tid).euid == 0;
    if (nr == SYS_mount) return root ? doMount(tid, r) : -EPERM;
    if (nr == SYS_umount2) return root ? doUmount(tid, r) : -EPERM;
    if (nr == SYS_unshare || nr == SYS_setns) return 0;  // single shared namespace for now
    if (nr == SYS_reboot) {
        logf(1, "[%d] guest requested reboot/power-off", tid);
        shutdown_ = true;
        return 0;
    }
    if (nr == SYS_pivot_root || nr == SYS_chroot || nr == SYS_sethostname || nr == SYS_setdomainname ||
        nr == SYS_acct || nr == SYS_swapon || nr == SYS_swapoff)
        return root ? 0 : -EPERM;
    if (nr == SYS_init_module || nr == SYS_finit_module || nr == SYS_delete_module) return -ENOSYS;
    handled = false;
    stats_.emulated--;
    return 0;
}

// Host seccomp policy (Android's app filter) refused a syscall with SIGSYS. Emulate it if we can,
// otherwise make it fail with EPERM instead of killing the guest process.
bool Tracer::handleSigsys(pid_t tid) {
    siginfo_t si{};
    if (ptrace(PTRACE_GETSIGINFO, tid, nullptr, &si) != 0 || si.si_code != 1 /* SYS_SECCOMP */) return false;
    long nr = si.si_syscall;
    Regs r;
    if (!getRegs(tid, r)) return false;
    if (r.compat) {
        const Spec* cs = findSpec(nr, true);
        nr = cs ? cs->logical : -1;
    }
    stats_.sigsys++;
    bool handled = false;
    long res = emulate(tid, r, nr, handled);
    if (!handled) {
        stats_.blocked++;
        logf(1, "[%d] host blocked syscall %ld -> EPERM", tid, nr);
        res = -EPERM;
    }
    setReturn(tid, r, res);
    return true;
}

void Tracer::handleSeccomp(pid_t tid) {
    stats_.stops++;
    Regs r;
    if (!getRegs(tid, r)) {
        ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        return;
    }
    const Spec* s = findSpec(static_cast<long>(r.nr), r.compat);
    bool wantExit = false;
    if (s) {
        uint64_t scratch = (r.sp - 1024) & ~static_cast<uint64_t>(15);  // below the x86 red zone
        bool changed = false;
        auto follow = [&](const Spec& sp) {
            bool f = sp.followDefault;
            if (sp.flagArg >= 0) {
                long flags = static_cast<long>(r.arg[sp.flagArg]);
                if (sp.noFollowMask && (flags & sp.noFollowMask)) f = false;
            }
            return f;
        };
        switch (s->kind) {
            case Kind::Path: {
                bool f = follow(*s);
                // linkat: old path follows only with AT_SYMLINK_FOLLOW.
                if (s->logical == SYS_linkat) f = (r.arg[4] & AT_SYMLINK_FOLLOW) != 0;
                if (s->path1 >= 0) changed |= rewritePath(tid, r, s->dirfd1, s->path1, f, scratch);
                if (s->path2 >= 0) changed |= rewritePath(tid, r, s->dirfd2, s->path2, false, scratch);
                if (changed) setArgs(tid, r);
                break;
            }
            case Kind::ReadLink: {
                std::string guest;
                rewritePath(tid, r, s->dirfd1, s->path1, false, scratch, &guest);
                setArgs(tid, r);
                // /proc/<pid>/{exe,cwd,root,fd/N} return host paths: translate them back at exit.
                if (guest.rfind("/proc/", 0) == 0) {
                    Pending p;
                    p.nr = s->logical;
                    p.guestPath = guest;
                    p.buf = r.arg[s->path1 + 1];
                    p.bufSize = r.arg[s->path1 + 2];
                    pending_[tid] = p;
                    wantExit = true;
                }
                break;
            }
            case Kind::Exec:
                if (rewriteExec(tid, r, scratch)) setArgs(tid, r);
                break;
            case Kind::GetCwd: {
                Pending p;
                p.nr = s->logical;
                p.buf = r.arg[0];
                p.bufSize = r.arg[1];
                pending_[tid] = p;
                wantExit = true;
                break;
            }
            case Kind::Sock:
                if (rewriteSockaddr(tid, r, scratch)) setArgs(tid, r);
                break;
            case Kind::FakeChown:
                if (cfg_.fakeRoot) {
                    stats_.faked++;
                    setSyscall(tid, r, -1);
                    setReturn(tid, r, credsOf(tid).euid == 0 ? 0 : -EPERM);
                } else {
                    if (rewritePath(tid, r, s->dirfd1, s->path1, follow(*s), scratch)) setArgs(tid, r);
                }
                break;
            case Kind::FakeZero:
                // fchown: ownership is not representable on the host; succeed only for root.
                if (!cfg_.fakeRoot) break;
                stats_.faked++;
                setSyscall(tid, r, -1);
                setReturn(tid, r, credsOf(tid).euid == 0 ? 0 : -EPERM);
                break;
            case Kind::Ids:
                if (cfg_.fakeRoot) handleIds(tid, r, s->logical);
                break;
            case Kind::Emulate: {
                bool handled = false;
                long res = emulate(tid, r, s->logical, handled);
                if (handled) {
                    setSyscall(tid, r, -1);
                    setReturn(tid, r, res);
                }
                break;
            }
        }
    }
    ptrace(wantExit ? PTRACE_SYSCALL : PTRACE_CONT, tid, nullptr, nullptr);
}

void Tracer::handleSyscallExit(pid_t tid) {
    auto it = pending_.find(tid);
    if (it == pending_.end()) {
        ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        return;
    }
    Pending p = it->second;
    pending_.erase(it);
    Regs r;
    if (!getRegs(tid, r)) {
        ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        return;
    }
    long ret = static_cast<long>(r.ret);
    if (p.nr != SYS_getcwd && p.guestPath.size() > 10 && p.guestPath.compare(p.guestPath.size() - 4, 4, "/exe") == 0) {
        // readlink("/proc/<pid>/exe"): report the guest program, not the loader running it.
        std::string exe = guestExeOf(static_cast<pid_t>(atoi(p.guestPath.c_str() + 6)));
        if (!exe.empty() && ret >= 0) {
            size_t n = std::min<size_t>(exe.size(), p.bufSize);
            writeMem(tid, p.buf, exe.c_str(), n);
            setReturn(tid, r, static_cast<long>(n));
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);
            return;
        }
    }
    if (ret > 0 && p.buf) {
        std::vector<char> buf(static_cast<size_t>(ret) + 1, 0);
        if (readMem(tid, p.buf, buf.data(), static_cast<size_t>(ret))) {
            // getcwd returns a NUL-terminated string (length incl. NUL); readlink does not terminate.
            std::string host(buf.data(), strnlen(buf.data(), static_cast<size_t>(ret)));
            std::string guest;
            if (!host.empty() && host[0] == '/' && cfg_.vfs.reverse(host, guest) && guest != host) {
                bool isCwd = p.nr == SYS_getcwd;
                size_t need = guest.size() + (isCwd ? 1 : 0);
                if (need <= p.bufSize) {
                    writeMem(tid, p.buf, guest.c_str(), need);
                    setReturn(tid, r, static_cast<long>(need));
                } else {
                    setReturn(tid, r, isCwd ? -ERANGE : -ENAMETOOLONG);
                }
            }
        }
    }
    ptrace(PTRACE_CONT, tid, nullptr, nullptr);
}

int Tracer::run(const std::string& program, const std::vector<std::string>& argv,
                const std::vector<std::string>& envp) {
    if (!cfg_.vfsSharePath.empty()) {
        share_.reset(new VfsShareWriter());
        if (!share_->open(cfg_.vfsSharePath)) return -1;
        publishVfs();
    }
    std::string hostCwd;
    cfg_.vfs.resolve(cfg_.guestCwd, "/", true, hostCwd);

    std::vector<char*> av, ev;
    for (auto& a : argv) av.push_back(const_cast<char*>(a.c_str()));
    av.push_back(nullptr);
    for (auto& e : envp) ev.push_back(const_cast<char*>(e.c_str()));
    ev.push_back(nullptr);

    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        if (chdir(hostCwd.c_str()) != 0) {
            // keep the inherited cwd; the guest will see it as reverse-mapped (or as-is)
        }
        if (cfg_.childSetup) cfg_.childSetup();
        ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
        raise(SIGSTOP);
        if (!installTraceFilter()) _exit(126);
        if (!cfg_.simulateTrap.empty()) {
            std::vector<sock_filter> f;
            f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch)));
            size_t j = f.size();
            f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 0, 0));
            f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)));
            for (long nr : cfg_.simulateTrap32) {
                f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<uint32_t>(nr), 0, 1));
                f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP));
            }
            f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
            f[j].jt = static_cast<uint8_t>(f.size() - j - 1);
            f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)));
            for (long nr : cfg_.simulateTrap) {
                f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<uint32_t>(nr), 0, 1));
                f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP));
            }
            f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
            sock_fprog prog{static_cast<unsigned short>(f.size()), f.data()};
            if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) _exit(126);
        }
        // The guest path is translated by the tracer on this very execve.
        syscall(SYS_execve, program.c_str(), av.data(), ev.data());
        _exit(127);
    }

    int status = 0;
    if (waitpid(child, &status, __WALL) != child || !WIFSTOPPED(status)) return -1;
    long opts = PTRACE_O_TRACESECCOMP | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE |
                PTRACE_O_TRACEEXEC | PTRACE_O_TRACESYSGOOD | PTRACE_O_EXITKILL;
    if (ptrace(PTRACE_SETOPTIONS, child, nullptr, reinterpret_cast<void*>(opts)) != 0) {
        kill(child, SIGKILL);
        return -1;
    }
    ptrace(PTRACE_CONT, child, nullptr, nullptr);

    std::set<pid_t> alive{child};
    {
        Creds initial = credsOf(child);
        std::lock_guard<std::mutex> g(credsMu_);
        tgid_[child] = child;
        creds_[child] = initial;  // initial identity from the config
    }
    // A new task is resumed only once BOTH its initial SIGSTOP and its parent's fork/clone event
    // were seen (they arrive in either order): its identity must be inherited before it runs.
    std::set<pid_t> registered;  // parent event seen
    std::set<pid_t> stopped;     // initial SIGSTOP seen, waiting for registration
    std::set<pid_t> started;     // initial SIGSTOP consumed
    started.insert(child);
    registered.insert(child);
    stats_.processes = 1;
    int rootStatus = -1;

    while (!alive.empty()) {
        if (shutdown_) {  // guest power-off: end every guest process
            shutdown_ = false;
            for (pid_t t : alive) kill(t, SIGKILL);
        }
        pid_t tid = waitpid(-1, &status, __WALL);
        if (tid < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            alive.erase(tid);
            pending_.erase(tid);
            registered.erase(tid);
            stopped.erase(tid);
            started.erase(tid);
            {
                std::lock_guard<std::mutex> g(credsMu_);
                if (tgidOf(tid) == tid) {
                    creds_.erase(tid);
                    exe_.erase(tid);
                }
                pendingExe_.erase(tid);
                tgid_.erase(tid);
            }
            if (tid == child) rootStatus = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            continue;
        }
        if (!WIFSTOPPED(status)) continue;
        alive.insert(tid);
        int sig = WSTOPSIG(status);
        int event = status >> 16;
        if (!started.count(tid) && sig == SIGSTOP) {
            started.insert(tid);
            if (registered.count(tid)) ptrace(PTRACE_CONT, tid, nullptr, nullptr);
            else stopped.insert(tid);  // parent's event not seen yet: keep it stopped
            continue;
        }
        if (sig == SIGTRAP && event == PTRACE_EVENT_SECCOMP) {
            handleSeccomp(tid);
        } else if (sig == (SIGTRAP | 0x80)) {
            handleSyscallExit(tid);
        } else if (sig == SIGTRAP && event == PTRACE_EVENT_EXEC) {
            // exec succeeded (tid is now the thread-group leader): commit the guest program.
            {
                std::lock_guard<std::mutex> g(credsMu_);
                unsigned long oldTid = 0;
                ptrace(PTRACE_GETEVENTMSG, tid, nullptr, &oldTid);
                auto it = pendingExe_.find(static_cast<pid_t>(oldTid ? oldTid : tid));
                if (it != pendingExe_.end()) {
                    exe_[tid] = it->second;
                    pendingExe_.erase(it);
                }
            }
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        } else if (sig == SIGTRAP && event != 0) {
            if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
                unsigned long n = 0;
                ptrace(PTRACE_GETEVENTMSG, tid, nullptr, &n);
                pid_t newTid = static_cast<pid_t>(n);
                if (newTid && !registered.count(newTid)) {
                    registered.insert(newTid);
                    alive.insert(newTid);
                    stats_.processes++;
                    onNewTask(tid, newTid);
                    if (stopped.erase(newTid)) ptrace(PTRACE_CONT, newTid, nullptr, nullptr);
                }
            }
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        } else if (sig == SIGSYS && handleSigsys(tid)) {
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);  // emulated: suppress the signal
        } else if (sig == SIGTRAP) {
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);  // plain exec trap
        } else {
            ptrace(PTRACE_CONT, tid, nullptr, reinterpret_cast<void*>(static_cast<long>(sig)));
        }
    }
    return rootStatus;
}

}  // namespace nest
