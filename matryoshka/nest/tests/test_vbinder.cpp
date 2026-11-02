// vbinder end-to-end: a context manager, a service and a client — separate processes speaking the
// raw binder protocol — talk through the userspace broker via libnest_preload.so.
// SPDX-License-Identifier: Apache-2.0
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <signal.h>
#include <string>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include "test.h"
#include "vbinder.h"

namespace {

struct Child {
    pid_t pid = -1;
    int out = -1;
    std::string buf;
};

Child spawn(const std::string& role, const std::string& sock) {
    int p[2];
    if (pipe(p) != 0) return {};
    pid_t pid = fork();
    if (pid == 0) {
        dup2(p[1], 1);
        close(p[0]);
        setenv("LD_PRELOAD", NEST_PRELOAD_PATH, 1);
        setenv("NEST_BINDER_SOCK", sock.c_str(), 1);
        execl(NEST_BINDERPROG_PATH, NEST_BINDERPROG_PATH, role.c_str(), nullptr);
        _exit(127);
    }
    close(p[1]);
    return {pid, p[0], ""};
}

// Reads lines until `want` appears (or timeout). Returns everything read so far.
std::string waitFor(Child& c, const std::string& want, int timeoutMs = 10000) {
    while (c.buf.find(want) == std::string::npos) {
        pollfd pf{c.out, POLLIN, 0};
        if (poll(&pf, 1, timeoutMs) <= 0) break;
        char tmp[512];
        ssize_t n = read(c.out, tmp, sizeof(tmp));
        if (n <= 0) break;
        c.buf.append(tmp, static_cast<size_t>(n));
    }
    return c.buf;
}

int reap(Child& c, int timeoutMs = 10000) {
    for (int waited = 0; waited < timeoutMs; waited += 20) {
        int st = 0;
        if (waitpid(c.pid, &st, WNOHANG) == c.pid) return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
        usleep(20000);
    }
    kill(c.pid, SIGKILL);
    waitpid(c.pid, nullptr, 0);
    return -1;
}

}  // namespace

static void managerServiceClientScenario(const std::string& managerRole) {
    std::string sock = "/tmp/nest-vb-" + std::to_string(getpid()) + managerRole;
    nest::vbinder::Broker broker(sock, [](int) { return 10123u; });  // virtual euid from the "tracer"
    EXPECT_TRUE(broker.listen());
    std::thread th([&] { broker.serve(); });

    Child mgr = spawn(managerRole, sock);
    EXPECT_TRUE(waitFor(mgr, "ready").find("ready") != std::string::npos);
    Child srv = spawn("server", sock);
    EXPECT_TRUE(waitFor(srv, "registered").find("registered added acq1") != std::string::npos);
    Child cli = spawn("client", sock);
    std::string out = waitFor(cli, "dead-after\n");

    EXPECT_EQ(out,
              "echo cba\n"
              "pong:ping:uid10123\n"
              "fd-ok\n"
              "nested:cb-ok\n"
              "dead-reply\n"
              "death 0xdead\n"
              "dead-after\n");
    EXPECT_EQ(reap(cli), 0);
    EXPECT_EQ(reap(srv), 0);
    EXPECT_TRUE(waitFor(mgr, "manager-quit").find("manager-quit") != std::string::npos);
    EXPECT_EQ(reap(mgr), 0);

    auto s = broker.stats();
    EXPECT_EQ(s.deathNotices, 1u);
    EXPECT_TRUE(s.fdsPassed >= 1);
    EXPECT_TRUE(s.deadReplies >= 2);
    fprintf(stderr, "  broker: processes=%llu transactions=%llu replies=%llu fds=%llu deaths=%llu\n",
            (unsigned long long)s.processes, (unsigned long long)s.transactions, (unsigned long long)s.replies,
            (unsigned long long)s.fdsPassed, (unsigned long long)s.deathNotices);
    broker.stop();
    th.join();
}

TEST(vbinder_manager_service_client_scenario) { managerServiceClientScenario("manager"); }

// Android 11's servicemanager waits for work with epoll on the binder fd (Looper), not in a
// blocking read: the fd has to turn readable exactly when work is queued.
TEST(vbinder_polling_context_manager) { managerServiceClientScenario("poll-manager"); }

// Full stack: the three processes run under the tracer with the fast path; the broker runs inside
// nest-run (-B) and reports each process's *virtual* euid (-u 10077) to the service.
TEST(vbinder_under_tracer_reports_virtual_uid) {
    std::string sock = "/tmp/nest-vbt-" + std::to_string(getpid());
    std::string p = NEST_BINDERPROG_PATH;
    std::string script = p + " manager & sleep 0.5; " + p + " server & sleep 0.5; " + p + " client; wait";
    int out[2];
    if (pipe(out) != 0) return;
    pid_t pid = fork();
    if (pid == 0) {
        dup2(out[1], 1);
        close(out[0]);
        execl(NEST_RUN_PATH, NEST_RUN_PATH, "-b", "/=/", "-u", "10077", "-P", NEST_PRELOAD_PATH, "-B", sock.c_str(), "--",
              "/bin/sh", "-c", script.c_str(), nullptr);
        _exit(127);
    }
    close(out[1]);
    Child c{pid, out[0], ""};
    std::string got = waitFor(c, "manager-quit", 20000);
    EXPECT_TRUE(got.find("pong:ping:uid10077\n") != std::string::npos);
    EXPECT_TRUE(got.find("nested:cb-ok\n") != std::string::npos);
    EXPECT_TRUE(got.find("death 0xdead\n") != std::string::npos);
    EXPECT_EQ(reap(c, 20000), 0);
}
