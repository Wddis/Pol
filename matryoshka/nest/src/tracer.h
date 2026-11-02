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
#include <string>
#include <vector>

#include "vfs.h"

namespace nest {

struct TracerConfig {
    Vfs vfs;
    std::string guestCwd = "/";
    bool fakeRoot = true;     // guest sees uid/gid 0 and privileged calls succeed
    int verbose = 0;          // 1 = exec log, 2 = every intercepted syscall
    int logFd = 2;
    // Runs in the child right before the first execve (e.g. dup2 for stdio redirection).
    std::function<void()> childSetup;
};

struct TracerStats {
    uint64_t stops = 0;          // seccomp stops handled
    uint64_t pathRewrites = 0;
    uint64_t execs = 0;
    uint64_t execRewrites = 0;   // exec redirected to the guest's own ELF interpreter / shebang
    uint64_t faked = 0;          // identity / privilege syscalls answered without the kernel
    uint64_t processes = 0;
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

private:
    struct Pending {
        long nr = -1;            // syscall awaiting its exit stop
        std::string guestPath;   // for readlinkat on /proc magic links
        uint64_t buf = 0;
        uint64_t bufSize = 0;
    };

    void handleSeccomp(pid_t tid);
    void handleSyscallExit(pid_t tid);
    bool rewritePath(pid_t tid, struct Regs& r, int dirfdArg, int pathArg, bool followLast,
                     uint64_t& scratch, std::string* guestOut = nullptr);
    bool rewriteExec(pid_t tid, struct Regs& r, uint64_t& scratch);
    bool rewriteSockaddr(pid_t tid, struct Regs& r, uint64_t& scratch);
    std::string guestCwdOf(pid_t tid);
    std::string guestFdPath(pid_t tid, int fd);
    uint64_t pushBytes(pid_t tid, uint64_t& scratch, const void* data, size_t len);
    void logf(int level, const char* fmt, ...);

    TracerConfig cfg_;
    TracerStats stats_;
    std::map<pid_t, Pending> pending_;
};

// The seccomp filter used by the tracer (exposed for the capability probe).
bool installTraceFilter();

}  // namespace nest
