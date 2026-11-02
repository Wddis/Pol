// End-to-end tests of the tracer through the nest-run supervisor, using real host binaries.
// The "rootfs" tests build a tiny guest root (binaries + their shared libraries + the dynamic
// loader) — the same situation as an Android guest, whose binaries need the guest's own linker.
// SPDX-License-Identifier: Apache-2.0
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <set>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#include "test.h"

#ifndef NEST_RUN_PATH
#error "NEST_RUN_PATH must point to the nest_run binary"
#endif

namespace {

struct Result {
    int status = -1;
    std::string out, err;
};

std::string readAll(int fd) {
    std::string s;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) s.append(buf, static_cast<size_t>(n));
    return s;
}

Result run(const std::vector<std::string>& args) {
    int out[2], err[2];
    if (pipe(out) != 0 || pipe(err) != 0) return {};
    pid_t pid = fork();
    if (pid == 0) {
        dup2(out[1], 1);
        dup2(err[1], 2);
        close(out[0]);
        close(err[0]);
        std::vector<char*> av;
        for (auto& a : args) av.push_back(const_cast<char*>(a.c_str()));
        av.push_back(nullptr);
        execv(av[0], av.data());
        _exit(127);
    }
    close(out[1]);
    close(err[1]);
    Result r;
    // stderr is small; read stdout first, then stderr.
    r.out = readAll(out[0]);
    r.err = readAll(err[0]);
    close(out[0]);
    close(err[0]);
    int st = 0;
    waitpid(pid, &st, 0);
    r.status = WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
    return r;
}

std::string trim(std::string s) {
    while (!s.empty() && (s.back() == '\n' || s.back() == ' ')) s.pop_back();
    return s;
}

void writeFile(const std::string& path, const std::string& content, mode_t mode = 0644) {
    FILE* f = fopen(path.c_str(), "w");
    fputs(content.c_str(), f);
    fclose(f);
    chmod(path.c_str(), mode);
}

std::string mktmp() {
    char tpl[] = "/tmp/nesttrXXXXXX";
    return mkdtemp(tpl);
}

void mkdirs(const std::string& path) {
    std::string cur;
    for (size_t i = 1; i <= path.size(); i++) {
        if (i == path.size() || path[i] == '/') {
            cur = path.substr(0, i);
            mkdir(cur.c_str(), 0755);
        }
    }
}

// Copies `bin` and every library it needs (per ldd) into root, preserving absolute paths.
void copyWithDeps(const std::string& root, const std::string& bin, const std::string& as = "") {
    std::set<std::string> files{bin};
    std::string cmd = "ldd " + bin + " 2>/dev/null";
    FILE* p = popen(cmd.c_str(), "r");
    char line[1024];
    while (p && fgets(line, sizeof(line), p)) {
        std::string l(line);
        size_t slash = l.find('/');
        if (slash == std::string::npos) continue;
        std::string path = l.substr(slash, l.find_first_of(" (\n", slash) - slash);
        files.insert(path);
    }
    if (p) pclose(p);
    for (const auto& f : files) {
        std::string dst = root + (f == bin && !as.empty() ? as : f);
        mkdirs(dst.substr(0, dst.rfind('/')));
        std::string c = "cp -L '" + f + "' '" + dst + "'";
        if (system(c.c_str()) != 0) fprintf(stderr, "copy failed: %s\n", c.c_str());
    }
}

const std::string& rootfs() {
    static std::string root;
    if (!root.empty()) return root;
    root = mktmp();
    copyWithDeps(root, "/bin/cat");
    copyWithDeps(root, "/bin/dash", "/bin/sh");
    copyWithDeps(root, "/usr/bin/readlink");
    copyWithDeps(root, "/usr/bin/id");
    copyWithDeps(root, NEST_IDPROG_PATH, "/bin/multi");
    (void)!symlink("/bin/multi", (root + "/bin/applet").c_str());  // like /system/bin/cat -> toybox
    mkdirs(root + "/proc");
    mkdirs(root + "/dev");
    mkdirs(root + "/tmp");
    writeFile(root + "/hello.txt", "hello from the guest root\n");
    writeFile(root + "/script.sh", "#!/bin/sh\necho script-ok \"$1\"\n", 0755);
    (void)!symlink("/bin/cat", (root + "/bin/mycat").c_str());  // absolute guest symlink
    return root;
}

std::vector<std::string> guestCmd(std::vector<std::string> extra) {
    std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=" + rootfs(), "-b", "/proc=/proc", "-b", "/dev=/dev", "-s", "--"};
    a.insert(a.end(), extra.begin(), extra.end());
    return a;
}

std::string hostDataDir() {
    static std::string d;
    if (d.empty()) {
        d = mktmp();
        writeFile(d + "/hello.txt", "bound file\n");
    }
    return d;
}

}  // namespace

TEST(tracer_bind_on_host_root) {
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "-b", "/etc/nest-test=" + hostDataDir(), "--", "/bin/cat",
                  "/etc/nest-test/hello.txt"});
    EXPECT_EQ(r.status, 0);
    EXPECT_EQ(r.out, "bound file\n");
}

TEST(tracer_getcwd_reports_guest_path) {
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "-b", "/etc/nest-test=" + hostDataDir(), "--", "/bin/dash", "-c",
                  "cd /etc/nest-test && pwd -P"});
    EXPECT_EQ(trim(r.out), "/etc/nest-test");
}

TEST(tracer_follows_children) {
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "-b", "/etc/nest-test=" + hostDataDir(), "--", "/bin/dash", "-c",
                  "/bin/cat /etc/nest-test/hello.txt; (/bin/cat /etc/nest-test/hello.txt) | /bin/cat"});
    EXPECT_EQ(r.out, "bound file\nbound file\n");
}

TEST(tracer_rootfs_exec_uses_guest_loader) {
    auto r = run(guestCmd({"/bin/cat", "/hello.txt"}));
    EXPECT_EQ(r.status, 0);
    EXPECT_EQ(r.out, "hello from the guest root\n");
    EXPECT_TRUE(r.err.find("exec_rewrites=1") != std::string::npos);
    if (r.status != 0) fprintf(stderr, "%s", r.err.c_str());
}

TEST(tracer_rootfs_shell_and_children) {
    auto r = run(guestCmd({"/bin/sh", "-c", "/bin/cat /hello.txt && /bin/mycat /hello.txt"}));
    EXPECT_EQ(r.out, "hello from the guest root\nhello from the guest root\n");
}

TEST(tracer_rootfs_shebang_script) {
    auto r = run(guestCmd({"/script.sh", "arg1"}));
    EXPECT_EQ(trim(r.out), "script-ok arg1");
}

TEST(tracer_rootfs_proc_links_are_guest_paths) {
    auto r = run(guestCmd({"/bin/sh", "-c", "cd /tmp && /usr/bin/readlink /proc/self/cwd"}));
    EXPECT_EQ(trim(r.out), "/tmp");
}

TEST(tracer_rootfs_missing_file_errors_normally) {
    auto r = run(guestCmd({"/bin/cat", "/does/not/exist"}));
    EXPECT_TRUE(r.status != 0);
    EXPECT_TRUE(r.err.find("No such file") != std::string::npos);
}

TEST(tracer_fake_root_identity) {
    auto r = run(guestCmd({"/usr/bin/id", "-u"}));
    EXPECT_EQ(trim(r.out), "0");
    EXPECT_TRUE(r.err.find("faked=") != std::string::npos && r.err.find("faked=0 ") == std::string::npos);
}

TEST(tracer_benchmark_stat_loop) {
    const char* loop = "i=0; while [ $i -lt 3000 ]; do [ -e /hello.txt ]; i=$((i+1)); done; echo done";
    timespec a{}, b{}, c{};
    clock_gettime(CLOCK_MONOTONIC, &a);
    auto traced = run(guestCmd({"/bin/sh", "-c", loop}));
    clock_gettime(CLOCK_MONOTONIC, &b);
    auto native = run({"/bin/dash", "-c", loop});
    clock_gettime(CLOCK_MONOTONIC, &c);
    double tt = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    double tn = (c.tv_sec - b.tv_sec) * 1e3 + (c.tv_nsec - b.tv_nsec) / 1e6;
    EXPECT_EQ(trim(traced.out), "done");
    fprintf(stderr, "  bench: 3000 stat() traced %.1f ms vs native %.1f ms (%.1f us/intercepted call)\n", tt, tn,
            (tt - tn) * 1000.0 / 3000.0);
}

// ---- virtual identities (what Android's init/zygote and Magisk's su rely on)

static std::vector<std::string> idCmd(std::vector<std::string> extra, const std::string& uid = "") {
    std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=/", "-s"};
    if (!uid.empty()) { a.push_back("-u"); a.push_back(uid); }
    a.push_back("--");
    a.push_back(NEST_IDPROG_PATH);
    a.insert(a.end(), extra.begin(), extra.end());
    return a;
}

TEST(ids_guest_init_starts_as_root) {
    auto r = run(idCmd({"uid", "gid"}));
    EXPECT_EQ(r.out, "0 0 0\n0 0 0\n");
}

TEST(ids_app_cannot_become_root) {
    auto r = run(idCmd({"uid", "setuid", "0", "setresuid", "0", "0", "0", "uid"}, "10050"));
    EXPECT_EQ(r.out, "10050 10050 10050\n-1 EPERM\n-1 EPERM\n10050 10050 10050\n");
    EXPECT_TRUE(r.err.find("denied=2") != std::string::npos);
}

TEST(ids_root_drops_privileges_permanently) {
    auto r = run(idCmd({"setuid", "10050", "uid", "setuid", "0", "uid"}));
    EXPECT_EQ(r.out, "0\n10050 10050 10050\n-1 EPERM\n10050 10050 10050\n");
}

TEST(ids_saved_uid_allows_regaining_root) {
    // Like a daemon that drops euid but keeps root in the saved set (Linux semantics).
    auto r = run(idCmd({"setresuid", "10050", "10050", "0", "uid", "setuid", "0", "uid"}));
    EXPECT_EQ(r.out, "0\n10050 10050 0\n0\n10050 0 0\n");
}

TEST(ids_inherited_across_fork_and_exec) {
    auto r = run(idCmd({"setuid", "10077", "fork-uid", "exec", "/usr/bin/id", "-u"}));
    EXPECT_EQ(r.out, "0\nchild 10077 10077\n10077\n");
}

// Regression for the Redmi Note 8 probe: multi-call binaries (toybox) dispatch on argv[0], which must
// survive symlink resolution and the switch to the guest's loader.
TEST(exec_keeps_argv0_through_guest_loader) {
    auto r = run(guestCmd({"/bin/applet", "argv0"}));
    EXPECT_EQ(trim(r.out), "applet");
    EXPECT_TRUE(r.err.find("exec_rewrites=1") != std::string::npos);
}

TEST(exec_symlinked_host_loader_is_not_redirected) {
    // Host root: the program's loader is the host's own (even if reached through a symlink, as
    // /system/bin/linker64 is on Android), so exec must not be rewritten at all.
    std::string dir = mktmp();
    (void)!symlink(NEST_IDPROG_PATH, (dir + "/applet").c_str());
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "-s", "--", dir + "/applet", "argv0"});
    EXPECT_EQ(trim(r.out), "applet");
    EXPECT_TRUE(r.err.find("exec_rewrites=0") != std::string::npos);
}

// ---- fast path (in-process translation, no ptrace stop)

static std::vector<std::string> fastCmd(std::vector<std::string> extra) {
    std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=" + rootfs(), "-b", "/proc=/proc", "-b", "/dev=/dev",
                               "-P", NEST_PRELOAD_PATH, "-s", "--"};
    a.insert(a.end(), extra.begin(), extra.end());
    return a;
}

static long statValue(const std::string& err, const char* key) {
    size_t p = err.find(std::string(key) + "=");
    return p == std::string::npos ? -1 : atol(err.c_str() + p + strlen(key) + 1);
}

TEST(fastpath_same_results_as_slow_path) {
    auto r = run(fastCmd({"/bin/sh", "-c", "/bin/cat /hello.txt && /bin/mycat /hello.txt && /script.sh x && cd /tmp && /usr/bin/readlink /proc/self/cwd"}));
    EXPECT_EQ(r.out, "hello from the guest root\nhello from the guest root\nscript-ok x\n/tmp\n");
    EXPECT_TRUE(statValue(r.err, "env") >= 3);  // injected into every exec
}

TEST(fastpath_avoids_stops) {
    const char* loop = "i=0; while [ $i -lt 3000 ]; do [ -e /hello.txt ]; i=$((i+1)); done; echo done";
    timespec a{}, b{}, c{};
    clock_gettime(CLOCK_MONOTONIC, &a);
    auto slow = run(guestCmd({"/bin/sh", "-c", loop}));
    clock_gettime(CLOCK_MONOTONIC, &b);
    auto fast = run(fastCmd({"/bin/sh", "-c", loop}));
    clock_gettime(CLOCK_MONOTONIC, &c);
    EXPECT_EQ(trim(fast.out), "done");
    long slowStops = statValue(slow.err, "stops"), fastStops = statValue(fast.err, "stops");
    EXPECT_TRUE(fastStops >= 0 && fastStops * 10 < slowStops);
    double ts = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    double tf = (c.tv_sec - b.tv_sec) * 1e3 + (c.tv_nsec - b.tv_nsec) / 1e6;
    fprintf(stderr, "  bench: 3000 stat() slow path %.1f ms (%ld stops) vs fast path %.1f ms (%ld stops)\n", ts,
            slowStops, tf, fastStops);
}

TEST(fastpath_app_files_land_in_guest_view) {
    auto r = run(fastCmd({"/bin/sh", "-c", "echo data > /tmp/fp.txt && /bin/cat /tmp/fp.txt"}));
    EXPECT_EQ(r.out, "data\n");
    struct stat st;
    EXPECT_TRUE(stat((rootfs() + "/tmp/fp.txt").c_str(), &st) == 0);  // written inside the rootfs
}

// The preload library must export only the libc wrappers (never its private C++ runtime).
TEST(preload_exports_only_wrappers) {
    std::string cmd = std::string("nm -D --defined-only ") + NEST_PRELOAD_PATH + " | awk '{print $3}'";
    FILE* p = popen(cmd.c_str(), "r");
    char line[256];
    int total = 0, unexpected = 0;
    while (p && fgets(line, sizeof(line), p)) {
        std::string s = trim(line);
        if (s.empty()) continue;
        total++;
        if (s.rfind("_Z", 0) == 0 || s.rfind("__cxa", 0) == 0 || s.rfind("__gxx", 0) == 0) {
            unexpected++;
            fprintf(stderr, "  unexpected export: %s\n", s.c_str());
        }
    }
    if (p) pclose(p);
    EXPECT_TRUE(total > 10);
    EXPECT_EQ(unexpected, 0);
}
