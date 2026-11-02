// Nest tracer: runs guest processes with a virtual filesystem view and a virtual root identity.
//
// Every guest process is a ptrace tracee of this supervisor. A seccomp filter (inherited by all
// descendants) stops the tracee only for syscalls that need translation, so ordinary syscalls run
// at native speed. Translated paths are written into the tracee's stack area below its stack
// pointer, and syscall arguments are redirected there.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "vfs.h"
#include "vfs_share.h"

namespace nest {

struct TracerConfig {
    Vfs vfs;
    std::string guestCwd = "/";
    // Virtual identities: every guest process has its own uid/gid set, tracked by the tracer with
    // Linux semantics (setuid/setresuid/...), inherited across fork/exec. The host kernel only
    // ever sees the app's uid. Root exists only where the guest grants it (init, Magisk su).
    bool fakeRoot = true;
    uint32_t initialUid = 0;  // identity of the first guest process (guest init = 0)
    uint32_t initialGid = 0;
    int verbose = 0;          // 1 = exec log, 2 = every intercepted syscall
    int logFd = 2;
    // Environment entries forced into every guest execve (e.g. LD_PRELOAD of the fast path and
    // NEST_VFS_FILE). Guest init starts services with a clean environment, so injecting at exec
    // time is the only reliable way to keep the fast path in every process.
    std::vector<std::string> injectEnv;
    // Runs in the child right before the first execve (e.g. dup2 for stdio redirection).
    std::function<void()> childSetup;
    // Where emulated mounts (tmpfs etc.) get their host directories.
    std::string stateDir = "/tmp";
    // Live mount table file for the fast path (see vfs_share.h); empty = none.
    std::string vfsSharePath;
    // Tests only: install an extra filter that SIGSYS-traps these syscalls, like Android's app
    // seccomp policy does for mount & co.
    std::vector<long> simulateTrap;
    std::vector<long> simulateTrap32;
    // If set, /proc/<pid>/attr/* (SELinux process attributes) map to files in this directory,
    // so the guest's setcon()/getcon() work without touching the host's SELinux.
    std::string procAttrDir;
    // Fast-path library (host path). With androidPreload it is exposed inside the guest in the
    // library directory the program's linker namespace may load from (/system/lib64,
    // /vendor/lib64, /apex/<name>/lib64) and injected as LD_PRELOAD on every exec.
    std::string preloadHost;
    std::string preload32Host;  // the same library built for 32-bit processes
    bool androidPreload = false;
    // The root guest process sees itself as pid 1 (Android init insists on it): getpid/gettid/
    // getppid answer 1 for it, and kill/tkill/tgkill and /proc/1 reach it. kill(-1) is confined
    // to guest processes.
    bool virtualInitPid = false;
    // connect() to these host socket paths goes to another host path (bind() is unchanged):
    // lets the log tee sit in front of the guest's logd.
    std::map<std::string, std::string> connectRedirect;
};

struct TracerStats {
    uint64_t stops = 0;          // seccomp stops handled
    uint64_t pathRewrites = 0;
    uint64_t execs = 0;
    uint64_t execRewrites = 0;   // exec redirected to the guest's own ELF interpreter / shebang
    uint64_t faked = 0;          // identity / privilege syscalls answered without the kernel
    uint64_t denied = 0;         // privilege changes refused (EPERM), like the kernel would
    uint64_t processes = 0;
    uint64_t envInjections = 0;
    uint64_t emulated = 0;       // privileged syscalls emulated (mount, pivot_root, ...)
    uint64_t sigsys = 0;         // of those, delivered as SIGSYS by the host's seccomp policy
    uint64_t blocked = 0;        // host-blocked syscalls we could only answer with an error
    uint64_t mounts = 0;
};

class Tracer {
public:
    explicit Tracer(TracerConfig cfg);

    // Starts `program` (a guest path) and supervises it and all descendants until they exit.
    // Returns the root process exit status in shell convention (128+signal when killed), or -1.
    int run(const std::string& program, const std::vector<std::string>& argv,
            const std::vector<std::string>& envp);

    const TracerStats& stats() const { return stats_; }
    TracerConfig& config() { return cfg_; }

    struct Creds {
        uint32_t ruid = 0, euid = 0, suid = 0, fsuid = 0;
        uint32_t rgid = 0, egid = 0, sgid = 0, fsgid = 0;
        // Capability sets (bit n = capability n) and securebits, with the kernel's transitions on
        // setuid and exec. The host kernel never sees them: privileged calls are emulated anyway.
        uint64_t capEff = 0, capPrm = 0, capInh = 0, capBnd = 0, capAmb = 0;
        uint32_t securebits = 0;
        bool hasCap(int cap) const { return (capEff >> cap) & 1; }
    };
    // Current virtual identity of a guest process (by any of its thread ids).
    Creds credsOf(pid_t tid) const;

private:
    struct Pending {
        long nr = -1;            // syscall awaiting its exit stop
        std::string guestPath;   // for readlinkat on /proc magic links
        uint64_t buf = 0;
        uint64_t bufSize = 0;
    };

    void handleSeccomp(pid_t tid);
    bool handleSigsys(pid_t tid);
    long emulate(pid_t tid, struct Regs& r, long nr, bool& handled);
    long doMount(pid_t tid, struct Regs& r);
    long doUmount(pid_t tid, struct Regs& r);
    bool guestPathArg(pid_t tid, uint64_t addr, bool follow, std::string& guest, std::string* host = nullptr);
    void publishVfs();
    void handleIds(pid_t tid, struct Regs& r, long nr);
    void handleCaps(pid_t tid, struct Regs& r, long nr);
    void applyExecCaps(pid_t tgid);
    bool handlePid(pid_t tid, struct Regs& r, long nr, long& result);
    pid_t parentOf(pid_t tid) const;
    void onNewTask(pid_t parent, pid_t tid);
    pid_t tgidOf(pid_t tid) const;
    void handleSyscallExit(pid_t tid);
    void fixSockName(pid_t tid, uint64_t addr, uint64_t lenAddr);
    bool clampRlimit(pid_t tid, struct Regs& r, long nr, uint64_t& scratch);
    bool rewritePath(pid_t tid, struct Regs& r, int dirfdArg, int pathArg, bool followLast,
                     uint64_t& scratch, std::string* guestOut = nullptr);
    bool rewriteExec(pid_t tid, struct Regs& r, uint64_t& scratch);
    bool rewriteSockaddr(pid_t tid, struct Regs& r, uint64_t& scratch, bool isConnect);
    std::string guestCwdOf(pid_t tid);
    std::string guestFdPath(pid_t tid, int fd);
    uint64_t pushBytes(pid_t tid, uint64_t& scratch, const void* data, size_t len);
    void logf(int level, const char* fmt, ...);

    TracerConfig cfg_;
    TracerStats stats_;
    std::map<pid_t, Pending> pending_;
    std::map<pid_t, pid_t> tgid_;     // thread id -> process id
    std::map<pid_t, Creds> creds_;    // process id -> virtual credentials
    std::map<pid_t, std::string> exe_;      // process id -> guest program (it may run via the loader)
    std::map<pid_t, std::string> pendingExe_;  // tid -> program of an in-flight execve
    std::string preloadEntryFor(const std::string& guestProgram, bool is32);
    std::string guestExeOf(pid_t tid);
    mutable std::mutex credsMu_;
    pid_t initTgid_ = -1;
    std::string lastHost_;  // host path of the latest rewritePath  // host pid of the root guest process (virtual pid 1)
    std::unique_ptr<VfsShareWriter> share_;
    int tmpfsCount_ = 0;
    bool shutdown_ = false;      // creds_/tgid_ are also read by the binder broker thread
};

// Creates an empty directory (or file) at hostPath if nothing is there yet.
void ensureMountPoint(const std::string& hostPath, bool dir);

// The seccomp filter used by the tracer (exposed for the capability probe).
bool installTraceFilter();

}  // namespace nest
