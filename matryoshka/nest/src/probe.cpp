// SPDX-License-Identifier: Apache-2.0
#include "probe.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "tracer.h"

namespace nest {

namespace {

std::string err(const char* what) { return std::string("fail:") + what + ": " + strerror(errno); }

bool exists(const char* p) { return access(p, F_OK) == 0; }

const char* catBinary() {
    if (exists("/system/bin/cat")) return "/system/bin/cat";
    return "/bin/cat";
}

// Runs fn in a child process; the child's exit code 0 means success.
template <typename Fn>
int inChild(Fn fn) {
    pid_t p = fork();
    if (p < 0) return -1;
    if (p == 0) _exit(fn());
    int st = 0;
    waitpid(p, &st, 0);
    return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
}

std::string checkSeccomp() {
    int rc = inChild([] { return installTraceFilter() ? 0 : 1; });
    return rc == 0 ? "ok" : "fail:seccomp filter rejected (rc=" + std::to_string(rc) + ")";
}

std::string checkExecFromData(const std::string& dir) {
    // Android forbids exec() from app data for targetSdk >= 29 (W^X). The guest needs it.
    std::string src = exists("/system/bin/toybox") ? "/system/bin/toybox" : "/bin/true";
    std::string dst = dir + "/probe-exec";
    int in = open(src.c_str(), O_RDONLY | O_CLOEXEC);
    int out = open(dst.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
    if (in < 0 || out < 0) return err("copy");
    char buf[65536];
    ssize_t n;
    while ((n = read(in, buf, sizeof(buf))) > 0)
        if (write(out, buf, static_cast<size_t>(n)) != n) break;
    close(in);
    close(out);
    chmod(dst.c_str(), 0755);
    int rc = inChild([&] {
        int null = open("/dev/null", O_WRONLY);
        dup2(null, 1);
        dup2(null, 2);
        const char* argv[] = {dst.c_str(), "true", nullptr};
        execv(dst.c_str(), const_cast<char**>(argv));
        return errno == EACCES ? 77 : 78;
    });
    unlink(dst.c_str());
    if (rc == 0) return "ok";
    if (rc == 77) return "fail:exec denied (W^X); app must target SDK 28";
    return "fail:exec rc=" + std::to_string(rc);
}

std::string checkMemfdAndFdPassing() {
#ifdef SYS_memfd_create
    int fd = static_cast<int>(syscall(SYS_memfd_create, "nest-probe", 0));
#else
    int fd = -1;
#endif
    if (fd < 0) return err("memfd_create");
    if (ftruncate(fd, 4096) != 0) return err("ftruncate");
    void* p = mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) return err("mmap");
    strcpy(static_cast<char*>(p), "shared");

    int sv[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv) != 0) return err("socketpair");
    char data = 'x';
    iovec iov{&data, 1};
    char ctrl[CMSG_SPACE(sizeof(int))] = {};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl;
    msg.msg_controllen = sizeof(ctrl);
    cmsghdr* c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET;
    c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(int));
    if (sendmsg(sv[0], &msg, 0) != 1) return err("sendmsg");
    char ctrl2[CMSG_SPACE(sizeof(int))] = {};
    msghdr in{};
    in.msg_iov = &iov;
    in.msg_iovlen = 1;
    in.msg_control = ctrl2;
    in.msg_controllen = sizeof(ctrl2);
    if (recvmsg(sv[1], &in, 0) != 1) return err("recvmsg");
    cmsghdr* rc = CMSG_FIRSTHDR(&in);
    if (!rc || rc->cmsg_type != SCM_RIGHTS) return "fail:no fd received";
    int got;
    memcpy(&got, CMSG_DATA(rc), sizeof(int));
    void* q = mmap(nullptr, 4096, PROT_READ, MAP_SHARED, got, 0);
    bool ok = q != MAP_FAILED && strcmp(static_cast<char*>(q), "shared") == 0;
    munmap(p, 4096);
    if (q != MAP_FAILED) munmap(q, 4096);
    close(fd);
    close(got);
    close(sv[0]);
    close(sv[1]);
    return ok ? "ok" : "fail:shared memory content mismatch";
}

// Full engine path: tracer + seccomp + ptrace + path rewrite + fake root on a real host binary.
std::string checkTracer(const std::string& dir) {
    std::string bound = dir + "/probe-bind";
    mkdir(bound.c_str(), 0755);
    FILE* f = fopen((bound + "/hello").c_str(), "w");
    if (!f) return err("fopen");
    fputs("nest-ok", f);
    fclose(f);
    int pipefd[2];
    if (pipe(pipefd) != 0) return err("pipe");

    TracerConfig cfg;
    cfg.vfs.bind("/", "/");
    cfg.vfs.bind("/mvm-probe", bound);
    int wfd = pipefd[1];
    cfg.childSetup = [wfd] { dup2(wfd, 1); };
    Tracer tracer(cfg);
    const char* cat = catBinary();
    int rc = tracer.run(cat, {cat, "/mvm-probe/hello"}, {"PATH=/system/bin:/bin"});
    close(pipefd[1]);
    char buf[64] = {};
    ssize_t n = read(pipefd[0], buf, sizeof(buf) - 1);
    close(pipefd[0]);
    unlink((bound + "/hello").c_str());
    rmdir(bound.c_str());
    if (rc < 0) return "fail:could not start a traced process (ptrace/seccomp blocked)";
    if (n > 0 && strcmp(buf, "nest-ok") == 0) {
        const auto& s = tracer.stats();
        return "ok:stops=" + std::to_string(s.stops) + ",rewrites=" + std::to_string(s.pathRewrites);
    }
    return "fail:path rewrite did not work (rc=" + std::to_string(rc) + ", read " + std::to_string(n) + " bytes)";
}

std::string checkBenchmark(const std::string& dir) {
    // Cost of one intercepted syscall, which bounds guest file-heavy workloads.
    TracerConfig cfg;
    cfg.vfs.bind("/", "/");
    cfg.vfs.bind("/mvm-probe", dir);
    cfg.childSetup = [] {
        int null = open("/dev/null", O_WRONLY);
        dup2(null, 1);
        dup2(null, 2);
    };
    Tracer tracer(cfg);
    const char* sh = exists("/system/bin/sh") ? "/system/bin/sh" : "/bin/sh";
    timespec a{}, b{};
    clock_gettime(CLOCK_MONOTONIC, &a);
    int rc = tracer.run(sh, {sh, "-c", "i=0; while [ $i -lt 2000 ]; do [ -e /mvm-probe ]; i=$((i+1)); done"}, {});
    clock_gettime(CLOCK_MONOTONIC, &b);
    if (rc != 0) return "fail:rc=" + std::to_string(rc);
    double ms = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    char out[96];
    snprintf(out, sizeof(out), "ok:%.0fms for %llu stops (%.1f us/stop)", ms,
             (unsigned long long)tracer.stats().stops,
             tracer.stats().stops ? ms * 1000.0 / tracer.stats().stops : 0.0);
    return out;
}

}  // namespace

std::string runProbe(const std::string& workDir) {
    std::string r;
    auto line = [&](const std::string& k, const std::string& v) { r += k + "=" + v + "\n"; };
    utsname u{};
    uname(&u);
    line("info.kernel", std::string(u.release));
    line("info.machine", u.machine);
    line("info.uid", std::to_string(getuid()));
    line("info.binder_device", exists("/dev/binder") ? "present" : "absent");
    line("info.ashmem_device", exists("/dev/ashmem") ? "present" : "absent");
    {
        FILE* f = fopen("/proc/sys/kernel/yama/ptrace_scope", "r");
        int v = -1;
        if (f) {
            if (fscanf(f, "%d", &v) != 1) v = -1;
            fclose(f);
        }
        line("info.yama_ptrace_scope", f ? std::to_string(v) : "n/a");
    }
    line("check.seccomp_filter", checkSeccomp());
    line("check.exec_from_app_data", checkExecFromData(workDir));
    line("check.memfd_fd_passing", checkMemfdAndFdPassing());
    line("check.tracer_path_rewrite", checkTracer(workDir));
    line("check.tracer_benchmark", checkBenchmark(workDir));
    return r;
}

}  // namespace nest
