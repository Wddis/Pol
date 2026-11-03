#include <sys/xattr.h>
#include <fstream>
#include <sstream>
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

TEST(rlimit_raise_succeeds_for_privileged_guest_only) {
    EXPECT_EQ(run(idCmd({"rlimit-raise"})).out, "0\n");
    EXPECT_EQ(run(idCmd({"rlimit-raise"}, "10050")).out, "-1 EPERM\n");
}

// ---- virtual pid 1 (Android init refuses cgroup setup unless getpid() == 1)

TEST(pid1_guest_init_sees_itself_as_pid_1) {
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "--pid1", "--", NEST_IDPROG_PATH, "pids", "child-ppid", "proc1-exe"});
    EXPECT_EQ(r.out, "1 1 kill1=0\nppid 1\nidprog\n");
    auto off = run({NEST_RUN_PATH, "-b", "/=/", "--", NEST_IDPROG_PATH, "pids"});
    EXPECT_TRUE(off.out.rfind("1 1 ", 0) != 0);
}

// ---- virtual capabilities (init's SetCapsForExec, zygote, Magisk su)

TEST(caps_root_has_all_and_app_none) {
    auto root = run(idCmd({"caps", "bset-read", "21"}));
    EXPECT_EQ(root.out, "ffffffff ffffffff 0 amb00\n1\n");
    auto app = run(idCmd({"caps", "capset", "1", "1", "0"}, "10050"));
    EXPECT_EQ(app.out, "0 0 0 amb00\n-1 EPERM\n");
}

TEST(caps_init_style_service_keeps_ambient_across_exec) {
    // What Android init does for a service with `capabilities` (e.g. lmkd: KILL, SYS_NICE...):
    // keep caps over setuid, drop the bounding set, raise ambient, exec.
    // Dropping the bounding set needs CAP_SETPCAP, so init keeps it until the final capset.
    auto r = run(idCmd({"keepcaps", "1", "setresuid", "1000", "1000", "1000", "caps", "capset", "1c0", "1c0", "c0",
                        "bset-drop", "21", "capset", "c0", "c0", "c0", "ambient-raise", "6", "ambient-raise", "7",
                        "exec", NEST_IDPROG_PATH, "caps", "bset-read", "21", "bset-drop", "6", "uid"}));
    EXPECT_EQ(r.out, "0\n0\n0 ffffffff 0 amb00\n0\n0\n0\n0\n0\nc0 c0 c0 amb11\n0\n-1 EPERM\n1000 1000 1000\n");
}

TEST(caps_dropped_without_keepcaps_and_cannot_raise) {
    auto r = run(idCmd({"setuid", "1000", "caps", "capset", "c0", "c0", "0", "ambient-raise", "6"}));
    EXPECT_EQ(r.out, "0\n0 0 0 amb00\n-1 EPERM\n-1 EPERM\n");
}

TEST(caps_root_exec_regains_full_set_from_bounding) {
    auto r = run(idCmd({"bset-drop", "7", "capset", "0", "0", "0", "exec", NEST_IDPROG_PATH, "caps"}));
    EXPECT_EQ(r.out, "0\n0\nffffff7f ffffff7f 0 amb00\n");
}

TEST(ids_and_caps_work_under_android_app_seccomp_policy) {
    // On a phone the app policy refuses set*id/setgroups/capset/fchown with SIGSYS: the tracer must
    // answer them from the virtual credentials all the same (init switching to service users).
    std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=/", "--simulate-app-seccomp", "-s", "--", NEST_IDPROG_PATH,
                               "keepcaps", "1", "setresuid", "1000", "1000", "1000", "caps", "capset", "1c0", "1c0", "c0",
                               "uid", "setuid", "0", "open-fchown", "/etc/hostname"};
    auto r = run(a);
    // CAP_SETUID was kept (0x1c0), so setuid(0) is allowed exactly as by the kernel; CAP_CHOWN was not.
    EXPECT_EQ(r.out, "0\n0\n0 ffffffff 0 amb00\n0\n1000 1000 1000\n0\nOperation not permitted\n");
    EXPECT_TRUE(r.err.find("sigsys=0 ") == std::string::npos);
    auto root = run({NEST_RUN_PATH, "-b", "/=/", "--simulate-app-seccomp", "--", NEST_IDPROG_PATH, "setgid", "1000",
                     "setuid", "1000", "uid", "gid"});
    EXPECT_EQ(root.out, "0\n0\n1000 1000 1000\n1000 1000 1000\n");
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

// errno must survive both preload branches (fast-path translation and the traced fallback).
TEST(fastpath_tag_does_not_leak_into_later_syscalls) {
    // Guest app uid: fchown must be refused by the tracer, never run by the (root) host kernel.
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "-u", "10050", "-P", NEST_PRELOAD_PATH, "--", NEST_IDPROG_PATH,
                  "open-fchown", "/etc/hostname", "open-fchown", "/etc/hostname"});
    EXPECT_EQ(r.out, "Operation not permitted\nOperation not permitted\n");
}

TEST(fastpath_preserves_errno) {
    auto r = run(fastCmd({"/bin/multi", "errno-rel", "errno-abs"}));
    EXPECT_EQ(r.out, "-1 No such file or directory\n-1 No such file or directory\n");
}

// ---- virtual mounts (guest init), with and without Android's SIGSYS policy

static std::vector<std::string> mountCmd(bool androidPolicy, const std::string& uid, std::vector<std::string> extra) {
    std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=" + rootfs(), "-b", "/proc=/proc", "-b", "/dev=/dev",
                               "-P", NEST_PRELOAD_PATH, "-s"};
    if (androidPolicy) a.push_back("--simulate-app-seccomp");
    if (!uid.empty()) { a.push_back("-u"); a.push_back(uid); }
    a.push_back("--");
    a.push_back("/bin/multi");
    a.insert(a.end(), extra.begin(), extra.end());
    return a;
}

static Result runInState(const std::vector<std::string>& args, const std::string& state) {
    setenv("NEST_STATE_DIR", state.c_str(), 1);
    Result r = run(args);
    unsetenv("NEST_STATE_DIR");
    return r;
}

TEST(mount_tmpfs_bind_umount_seen_by_fast_path) {
    for (bool policy : {false, true}) {
        std::string state = mktmp();
        auto r = runInState(mountCmd(policy, "", {"mount-tmpfs", "/tmp", "write", "/tmp/f", "in-tmpfs", "cat", "/tmp/f",
                                                  "bind", "/hello.txt", "/tmp/h", "cat", "/tmp/h",
                                                  "umount", "/tmp/h", "cat", "/tmp/h"}),
                            state);
        // After umount the (empty) mount point file stays behind, exactly like on Linux.
        EXPECT_EQ(r.out, "0\nin-tmpfs\n0\nhello from the guest root\n\n0\n\n");
        // tmpfs content lives in the engine's state dir, not in the (shared, read-only) rootfs.
        struct stat st;
        EXPECT_TRUE(stat((state + "/mnt/1/f").c_str(), &st) == 0);
        EXPECT_TRUE(stat((rootfs() + "/tmp/f").c_str(), &st) != 0);
        if (policy) EXPECT_TRUE(r.err.find("sigsys=0 ") == std::string::npos);  // came in as SIGSYS
    }
}

TEST(mount_cgroup_hierarchy_behaves_like_kernel) {
    // Android init mounts cpuset/memcg and copies cpus/mems into new groups; libprocessgroup
    // writes pids into tasks files of groups it just created, without O_CREAT.
    for (bool fast : {false, true}) {
        std::string state = mktmp();
        auto args = mountCmd(false, "", {"mount-fs", "cpuset", "/tmp", "", "cat", "/tmp/mems", "mkdir", "/tmp/bg",
                                         "write-existing", "/tmp/bg/tasks", "123", "cat", "/tmp/bg/tasks",
                                         "write-existing", "/tmp/nope/tasks", "1"});
        if (!fast) {  // drop "-P <preload>": every call goes through the tracer
            for (size_t k = 0; k + 1 < args.size(); k++)
                if (args[k] == "-P") { args.erase(args.begin() + k, args.begin() + k + 2); break; }
        }
        auto r = runInState(args, state);
        EXPECT_EQ(r.out, "0\n0\n\n0\n0\n123\n-1 No such file or directory\n");
    }
}

TEST(unix_socket_names_are_guest_paths) {
    // android_get_control_socket() rejects init's sockets unless getsockname() says /dev/socket/<name>.
    std::string host = mktmp();
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "-b", "/dev/socket=" + host, "--", NEST_IDPROG_PATH, "sockname",
                  "/dev/socket/lmkd"});
    EXPECT_EQ(r.out, "/dev/socket/lmkd 1 /dev/socket/lmkd\n");
}

// ---- eBPF maps for bpfloader / netd (bpf_emu.h)
TEST(bpf_maps_pin_and_retrieve_like_the_kernel) {
    const char* expect =
        "create fd\nupdate 0 noexist -17\npin 0 again -17\nget fd\nlookup 0 1122334455667788\nfull -7\n"
        "iter 0:7 0:9 -2\ndelete 0 missing -2\narray lookup 0 0 oob -2\nprog fd\ninfo 0 type1 key4 val8 max2\n";
    for (bool appPolicy : {false, true}) {  // seccomp stop, or SIGSYS from Android's app filter
        std::string state = mktmp(), fs = mktmp();
        std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=/", "-b", "/sys/fs/bpf=" + fs, "-s"};
        if (appPolicy) a.push_back("--simulate-app-seccomp");
        for (auto x : {"--", NEST_IDPROG_PATH, "bpf-demo", "/sys/fs/bpf/map_netd_test"}) a.push_back(x);
        auto r = runInState(a, state);
        EXPECT_EQ(r.out, expect);
        if (appPolicy) EXPECT_TRUE(r.err.find("sigsys=0 ") == std::string::npos);
    }
}

TEST(android_kmsg_keeps_every_writer_line) {
    // init, ueventd and logd each open /dev/kmsg for writing; none may overwrite the others.
    for (bool fast : {false, true}) {
        std::string state = mktmp();
        std::vector<std::string> a{NEST_RUN_PATH, "-b", "/=/", "--android", state};
        if (fast) { a.push_back("-P"); a.push_back(NEST_PRELOAD_PATH); }
        for (auto x : {"--", NEST_IDPROG_PATH, "write", "/dev/kmsg", "<6>one\n", "write", "/dev/kmsg", "<6>two\n"})
            a.push_back(x);
        run(a);
        std::ifstream f(state + "/kmsg");
        std::stringstream ss;
        ss << f.rdbuf();
        EXPECT_EQ(ss.str(), "<6>one\n<6>two\n");
    }
}

TEST(bpf_prctl_carrier) {
    auto r = runInState({NEST_RUN_PATH, "-b", "/=/", "--", NEST_IDPROG_PATH, "bpf-carrier"}, mktmp());
    EXPECT_EQ(r.out, "carrier fd 0 0 42\n");
}

TEST(android_oom_score_adj_is_virtual_per_process) {
    std::string state = mktmp();
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "--android", state, "--", NEST_IDPROG_PATH, "cat", "/proc/self/oom_score_adj",
                  "write", "/proc/self/oom_score_adj", "-1000", "cat", "/proc/self/oom_score_adj"});
    EXPECT_EQ(r.out, "0\n\n-1000\n");
}

// Like the phone: the guest root is a separate tree, the engine's state dir is not part of it.
TEST(android_profile_on_separate_root_like_the_phone) {
    std::string state = mktmp();
    auto a = [&](const std::string& uid, std::vector<std::string> cmd) {
        std::vector<std::string> v{NEST_RUN_PATH, "-b", "/=" + rootfs(), "--android", state};
        if (!uid.empty()) { v.push_back("-u"); v.push_back(uid); }
        v.push_back("--");
        v.push_back("/bin/multi");
        v.insert(v.end(), cmd.begin(), cmd.end());
        return v;
    };
    // init writes /proc/1/oom_score_adj: virtual file inside the state dir
    auto oom = runInState(a("", {"write", "/proc/self/oom_score_adj", "-1000", "cat", "/proc/self/oom_score_adj"}), state);
    EXPECT_EQ(oom.out, "-1000\n");
    // bionic labels each property file; init aborts if that fails. Never reaches the host.
    auto root = runInState(a("", {"label", "/tmp/prop-label-test"}), state);
    EXPECT_EQ(root.out, "0 0\n");
    char buf[64];
    EXPECT_TRUE(getxattr((rootfs() + "/tmp/prop-label-test").c_str(), "security.selinux", buf, sizeof(buf)) < 0);
    auto app = runInState(a("10050", {"label", "/tmp/prop-label-app"}), state);
    EXPECT_EQ(app.out, "Operation not permitted Operation not permitted\n");
}

TEST(lower_nice_is_never_refused_to_the_guest) {
    // Like init's `priority -20` for zygote. Run as an unprivileged host user (RLIMIT_NICE 0):
    // the host kernel refuses, the guest must still see success.
    std::vector<std::string> drop{"/usr/bin/setpriv", "--reuid=65534", "--regid=65534", "--clear-groups"};
    auto with = [&](std::vector<std::string> v) { v.insert(v.begin(), drop.begin(), drop.end()); return v; };
    auto host = run(with({NEST_IDPROG_PATH, "nice", "-5"}));
    if (host.out != "Permission denied\n") {
        fprintf(stderr, "  skipped: cannot run unprivileged here (%s)\n", trim(host.out).c_str());
        return;
    }
    auto r = run(with({NEST_RUN_PATH, "-b", "/=/", "-u", "10050", "--", NEST_IDPROG_PATH, "nice", "5", "nice", "-5"}));
    EXPECT_EQ(r.out, "0\n0\n");
}

TEST(virtual_file_owner_like_the_phone) {
    // On the phone every guest file belongs to the app's uid. The guest must see root (bionic's
    // property area refuses anything else) and the owners its chown() set.
    std::vector<std::string> drop{"/usr/bin/setpriv", "--reuid=65534", "--regid=65534", "--clear-groups"};
    auto with = [&](std::vector<std::string> v) { v.insert(v.begin(), drop.begin(), drop.end()); return v; };
    if (run(with({"/bin/true"})).status != 0) {
        fprintf(stderr, "  skipped: cannot run unprivileged here\n");
        return;
    }
    std::string dir = mktmp();
    chmod(dir.c_str(), 0777);
    std::string f = dir + "/property_info";
    std::string cmd = "touch " + f;
    auto mk = run(with({"/bin/sh", "-c", cmd}));
    (void)mk;
    auto r = run(with({NEST_RUN_PATH, "-b", "/=/", "-P", NEST_PRELOAD_PATH, "--", NEST_IDPROG_PATH, "owner", f, "chown", f,
                       "1000", "1001", "owner", f, "fchown", f, "-1", "2000", "owner", f, "chown", f, "0", "0", "owner", f}));
    EXPECT_EQ(r.out, "0:0 0:0\n0\n1000:1001 1000:1001\n0\n1000:2000 1000:2000\n0\n0:0 0:0\n");
    // A static program (no preload, like the bootstrap linkerconfig reading the property area):
    // the tracer patches the owner into stat/fstat results.
    auto sr = run(with({NEST_RUN_PATH, "-b", "/=/", "-P", NEST_PRELOAD_PATH, "--", NEST_IDPROG_STATIC_PATH, "owner", f,
                        "chown", f, "1000", "1001", "owner", f, "chown", f, "0", "0", "owner", f}));
    EXPECT_EQ(sr.out, "0:0 0:0\n0\n1000:1001 1000:1001\n0\n0:0 0:0\n");
    struct stat st;
    EXPECT_TRUE(stat(f.c_str(), &st) == 0 && st.st_uid == 65534);  // the host file never changed owner
}

TEST(kernel_uevent_socket_is_faked) {
    // ueventd aborts if it cannot open the NETLINK_KOBJECT_UEVENT socket, which an untrusted app
    // may not. The engine must hand it a harmless socket that binds and never delivers events.
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "--android", mktmp(), "--", NEST_IDPROG_PATH, "uevent"});
    EXPECT_EQ(r.out, "socket ok bind ok\n");
}

TEST(rlimit_raise_succeeds_as_unprivileged_host_user) {
    // Like the phone: the host user may not raise hard limits at all; init's `rlimit memlock 1G`
    // for bpfloader must still succeed for the guest's root.
    std::vector<std::string> drop{"/usr/bin/setpriv", "--reuid=65534", "--regid=65534", "--clear-groups"};
    auto with = [&](std::vector<std::string> v) { v.insert(v.begin(), drop.begin(), drop.end()); return v; };
    if (run(with({"/bin/true"})).status != 0) {
        fprintf(stderr, "  skipped: cannot run unprivileged here\n");
        return;
    }
    auto r = run(with({NEST_RUN_PATH, "-b", "/=/", "--", NEST_IDPROG_PATH, "rlimit-raise"}));
    EXPECT_EQ(r.out, "0\n");
    auto app = run(with({NEST_RUN_PATH, "-b", "/=/", "-u", "10050", "--", NEST_IDPROG_PATH, "rlimit-raise"}));
    EXPECT_EQ(app.out, "-1 EPERM\n");
}

TEST(fusectl_mount_is_a_readable_empty_dir) {
    // vold lists /sys/fs/fuse/connections and aborts if it cannot; the host's is unreadable to apps.
    std::string state = mktmp();
    auto r = runInState(mountCmd(false, "", {"mount-fs", "fusectl", "/tmp", "", "write", "/tmp/probe", "ok", "cat", "/tmp/probe"}),
                        state);
    EXPECT_EQ(r.out, "0\nok\n");
}

TEST(non_dumpable_guest_still_readable_by_tracer) {
    // logd on the phone: after PR_SET_DUMPABLE 0 an unprivileged host user cannot process_vm_readv
    // the process; capset's arguments must still be read (it failed with EFAULT).
    std::vector<std::string> drop{"/usr/bin/setpriv", "--reuid=65534", "--regid=65534", "--clear-groups"};
    auto with = [&](std::vector<std::string> v) { v.insert(v.begin(), drop.begin(), drop.end()); return v; };
    if (run(with({"/bin/true"})).status != 0) {
        fprintf(stderr, "  skipped: cannot run unprivileged here\n");
        return;
    }
    auto r = run(with({NEST_RUN_PATH, "-b", "/=/", "--", NEST_IDPROG_PATH, "nodump", "capset", "c0", "c0", "0", "caps"}));
    EXPECT_EQ(r.out, "0 0\n0\nc0 c0 0 amb00\n");
}

TEST(mount_requires_root_identity) {
    auto r = runInState(mountCmd(true, "10050", {"mount-tmpfs", "/tmp"}), mktmp());
    EXPECT_EQ(r.out, "-1 EPERM\n");
}

// ---- 32-bit (compat) processes: i386 here, arm32 on phones; same tracer code paths.
#ifdef NEST_HAVE_M32
TEST(compat32_paths_ids_and_mounts) {
    std::string state = mktmp();
    auto r = runInState({NEST_RUN_PATH, "-b", "/=/", "-b", "/etc/nest-test=" + hostDataDir(), "-u", "10050", "-s", "--",
                         NEST_IDPROG32_PATH, "bits", "cat", "/etc/nest-test/hello.txt", "uid", "setuid", "0"},
                        state);
    EXPECT_EQ(r.out, "32\nbound file\n\n10050 10050 10050\n-1 EPERM\n");
    auto m = runInState({NEST_RUN_PATH, "-b", "/=/", "--simulate-app-seccomp", "-s", "--", NEST_IDPROG32_PATH,
                         "mount-tmpfs", "/mnt", "write", "/mnt/x", "t32", "cat", "/mnt/x"},
                        state);
    EXPECT_EQ(m.out, "0\nt32\n");
    EXPECT_TRUE(m.err.find("sigsys=0 ") == std::string::npos);
}

TEST(compat32_identity_crosses_abi_boundaries) {
    // 64-bit -> 32-bit -> 64-bit exec chain keeps the virtual identity.
    auto r = run({NEST_RUN_PATH, "-b", "/=/", "--", NEST_IDPROG_PATH, "setuid", "10077", "exec", NEST_IDPROG32_PATH,
                  "bits", "uid", "exec", NEST_IDPROG_PATH, "bits", "uid"});
    EXPECT_EQ(r.out, "0\n32\n10077 10077 10077\n64\n10077 10077 10077\n");
}

TEST(compat32_fast_path) {
    auto slow = run({NEST_RUN_PATH, "-b", "/=/", "-b", "/etc/nest-test=" + hostDataDir(), "-s", "--", NEST_IDPROG32_PATH,
                     "statloop", "2000", "/etc/nest-test/hello.txt"});
    auto fast = run({NEST_RUN_PATH, "-b", "/=/", "-b", "/etc/nest-test=" + hostDataDir(), "-P", NEST_PRELOAD_PATH,
                     "-P32", NEST_PRELOAD32_PATH, "-s", "--", NEST_IDPROG32_PATH, "statloop", "2000",
                     "/etc/nest-test/hello.txt"});
    EXPECT_EQ(slow.out, "2000\n");
    EXPECT_EQ(fast.out, "2000\n");
    long s = statValue(slow.err, "stops"), f = statValue(fast.err, "stops");
    EXPECT_TRUE(f >= 0 && f * 5 < s);
    fprintf(stderr, "  compat32: 2000 stat() slow path %ld stops, fast path %ld stops\n", s, f);
}
#endif
