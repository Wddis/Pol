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
#include <sys/wait.h>
#include <unistd.h>

#include "arch.h"

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
    FakeZero,   // skip syscall, return 0 (identity / privileged no-ops)
    GetResId,   // getresuid/getresgid: write zeros, return 0
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
    for (long nr : {SYS_getuid, SYS_geteuid, SYS_getgid, SYS_getegid}) s.push_back({nr, Kind::FakeZero});
    for (long nr : {SYS_setuid, SYS_setgid, SYS_setreuid, SYS_setregid, SYS_setresuid, SYS_setresgid,
                    SYS_setfsuid, SYS_setfsgid, SYS_setgroups})
        s.push_back({nr, Kind::FakeZero});
    s.push_back({SYS_getresuid, Kind::GetResId});
    s.push_back({SYS_getresgid, Kind::GetResId});
    return s;
}

const std::vector<Spec>& specs() {
    static const std::vector<Spec> s = buildSpecs();
    return s;
}

const Spec* findSpec(long nr) {
    for (const auto& s : specs())
        if (s.nr == nr) return &s;
    return nullptr;
}

}  // namespace

bool installTraceFilter() {
    const auto& sp = specs();
    std::vector<sock_filter> f;
    f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch)));
    f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 1, 0));
    f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)));
    for (const auto& s : sp) {
        f.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<uint32_t>(s.nr), 0, 1));
        f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE));
    }
    f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
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
    if (full.compare(0, 6, "/proc/") == 0) full = fixProcSelf(tid, full);
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
    } else if (n >= 4 && memcmp(head, ELFMAG, SELFMAG) == 0) {
        ok = true;  // 32-bit ELF: handled like a static binary for now (arm32 guests: later stage)
    }
    close(fd);
    return ok;
}

}  // namespace

bool Tracer::rewriteExec(pid_t tid, Regs& r, uint64_t& scratch) {
    stats_.execs++;
    char path[PATH_MAX];
    if (!r.arg[0] || !readString(tid, r.arg[0], path, sizeof(path))) return false;

    // Original argv (pointers stay valid in the tracee; we only read them).
    std::vector<uint64_t> argvPtrs;
    for (int i = 0; i < 4096 && r.arg[1]; i++) {
        uint64_t p = 0;
        if (!readMem(tid, r.arg[1] + i * 8, &p, 8)) return false;
        if (!p) break;
        argvPtrs.push_back(p);
    }

    std::string base = path[0] == '/' ? "/" : guestCwdOf(tid);
    std::string hostProg, guestProg;
    if (!cfg_.vfs.resolve(path, base, true, hostProg, &guestProg)) return false;

    // Chain: script interpreter -> ELF -> ELF interpreter. Build new filename + argv prefix.
    std::vector<std::string> prefix;  // replaces argv[0]
    std::string target = hostProg;       // host file the kernel will execute
    std::string targetGuest = guestProg; // the same file as the (translated) guest sees it
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
            std::string hostInterp;
            if (cfg_.vfs.resolve(info.interp, "/", true, hostInterp) && hostInterp != info.interp) {
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
        if (hostProg == path) return true;
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
    uint64_t argvAddr = pushBytes(tid, scratch, newArgv.data(), newArgv.size() * 8);
    uint64_t fileAddr = pushBytes(tid, scratch, target.c_str(), target.size() + 1);
    if (!argvAddr || !fileAddr) return false;
    r.arg[0] = fileAddr;
    r.arg[1] = argvAddr;
    stats_.execRewrites++;
    logf(1, "[%d] exec %s via %s", tid, path, target.c_str());
    return true;
}

void Tracer::handleSeccomp(pid_t tid) {
    stats_.stops++;
    Regs r;
    if (!getRegs(tid, r)) {
        ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        return;
    }
    const Spec* s = findSpec(static_cast<long>(r.nr));
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
                if (s->nr == SYS_linkat) f = (r.arg[4] & AT_SYMLINK_FOLLOW) != 0;
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
                    p.nr = s->nr;
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
                p.nr = s->nr;
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
                    setReturn(tid, r, 0);
                } else {
                    if (rewritePath(tid, r, s->dirfd1, s->path1, follow(*s), scratch)) setArgs(tid, r);
                }
                break;
            case Kind::FakeZero:
            case Kind::GetResId:
                if (!cfg_.fakeRoot) break;
                stats_.faked++;
                if (s->kind == Kind::GetResId) {
                    uint32_t zero = 0;
                    for (int i = 0; i < 3; i++)
                        if (r.arg[i]) writeMem(tid, r.arg[i], &zero, sizeof(zero));
                }
                setSyscall(tid, r, -1);
                setReturn(tid, r, 0);
                break;
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
    std::set<pid_t> fresh;  // auto-attached tids whose initial SIGSTOP we have not consumed yet
    stats_.processes = 1;
    int rootStatus = -1;

    while (!alive.empty()) {
        pid_t tid = waitpid(-1, &status, __WALL);
        if (tid < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            alive.erase(tid);
            pending_.erase(tid);
            if (tid == child) rootStatus = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            continue;
        }
        if (!WIFSTOPPED(status)) continue;
        if (!alive.count(tid)) {
            alive.insert(tid);  // new thread/process reported before its parent's event
            fresh.insert(tid);
        }
        int sig = WSTOPSIG(status);
        int event = status >> 16;
        if (sig == SIGTRAP && event == PTRACE_EVENT_SECCOMP) {
            handleSeccomp(tid);
        } else if (sig == (SIGTRAP | 0x80)) {
            handleSyscallExit(tid);
        } else if (sig == SIGTRAP && event != 0) {
            if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
                unsigned long newTid = 0;
                ptrace(PTRACE_GETEVENTMSG, tid, nullptr, &newTid);
                if (newTid && !alive.count(static_cast<pid_t>(newTid))) {
                    alive.insert(static_cast<pid_t>(newTid));
                    fresh.insert(static_cast<pid_t>(newTid));
                    stats_.processes++;
                }
            }
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        } else if (sig == SIGSTOP && fresh.count(tid)) {
            fresh.erase(tid);
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        } else if (sig == SIGTRAP) {
            ptrace(PTRACE_CONT, tid, nullptr, nullptr);  // plain exec trap
        } else {
            ptrace(PTRACE_CONT, tid, nullptr, reinterpret_cast<void*>(static_cast<long>(sig)));
        }
    }
    return rootStatus;
}

}  // namespace nest
