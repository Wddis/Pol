// nest-run: command-line supervisor for Nest guests.
//
// On Android the app launches it as libnest_run.so from its native library directory (the only
// place apps may execute code from regardless of targetSdk). On Linux it is used by the tests.
//
//   nest-run --probe DIR                       run the capability probe
//   nest-run [-b GUEST=HOST[:ro]]... [-w GUEST_CWD] [-0|-n] [--pid1] [--display WxH@DPI] [-v]... [-s] [-E K=V]... [-c] -- PROGRAM [ARGS...]
//
//   -b   bind a host directory/file at a guest path ("-b /=rootfs" sets the guest root)
//   -w   guest working directory (default "/")
//   -0   virtual identities (default) ; -n  real identity ; -u UID  start as this virtual uid
//   -v   verbose (repeat for more)    ; -s  print statistics to stderr on exit
//   --android DIR  guest-Android environment (private /dev, /proc overrides, APEXes); DIR = state
//   -B   serve /dev/{,hw,vnd}binder for the guest from a userspace broker on this socket
//   -P32 the same library for 32-bit guest processes
//   -P   fast-path preload library (host path); NEST_STATE_DIR = where to write the mount table
//   -E   add/override an environment variable ; -c  start with a clean environment
//
// SPDX-License-Identifier: Apache-2.0
#include <cstdio>
#include <fcntl.h>
#include <dirent.h>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <algorithm>
#include <memory>
#include <string>
#include <sys/syscall.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/socket.h>
#include <vector>

#include <thread>

#include "android_profile.h"
#include "gpu_passthrough.h"
#include "guest_log.h"
#include "probe.h"
#include "vbinder.h"
#include "wayland_server.h"
#include "tracer.h"

extern char** environ;

static void usage() {
    fprintf(stderr, "usage: nest-run [-b GUEST=HOST[:ro]]... [-w CWD] [-0|-n] [--pid1] [--display WxH@DPI] [-v] [-s] [-E K=V]... [-c] -- PROG [ARGS]\n");
}

int main(int argc, char** argv) {
    // Capability probe mode: nest-run --probe <writable dir>
    if (argc == 3 && strcmp(argv[1], "--probe") == 0) {
        fputs(nest::runProbe(argv[2]).c_str(), stdout);
        return 0;
    }
    nest::TracerConfig cfg;
    bool stats = false, clean = false;
    std::string preload, binderSock, androidState;
    nest::DisplayConfig display;
    bool useGpu = false;  // --gpu: the phone's GPU driver for the guest (graphics, step 2)
    std::vector<std::string> extraEnv;
    int i = 1;
    for (; i < argc; i++) {
        std::string a = argv[i];
        if (a == "--") { i++; break; }
        if (a == "-b" && i + 1 < argc) {
            std::string spec = argv[++i];
            size_t eq = spec.find('=');
            if (eq == std::string::npos) { usage(); return 2; }
            std::string guest = spec.substr(0, eq), host = spec.substr(eq + 1);
            bool ro = false;
            if (host.size() > 3 && host.compare(host.size() - 3, 3, ":ro") == 0) { ro = true; host.resize(host.size() - 3); }
            cfg.vfs.bind(guest, host, ro);
        } else if (a == "-w" && i + 1 < argc) {
            cfg.guestCwd = argv[++i];
        } else if (a == "-0") {
            cfg.fakeRoot = true;
        } else if (a == "-u" && i + 1 < argc) {
            cfg.initialUid = static_cast<uint32_t>(strtoul(argv[++i], nullptr, 10));
            cfg.initialGid = cfg.initialUid;
        } else if (a == "-B" && i + 1 < argc) {
            binderSock = argv[++i];
        } else if (a == "-P" && i + 1 < argc) {
            preload = argv[++i];
        } else if (a == "-P32" && i + 1 < argc) {
            cfg.preload32Host = argv[++i];
        } else if (a == "--android" && i + 1 < argc) {
            androidState = argv[++i];
        } else if (a == "--gpu") {
            useGpu = true;
        } else if (a == "--display" && i + 1 < argc) {
            // WIDTHxHEIGHT[@DPI]: the guest's screen (Wayland output + framebuffer size)
            if (sscanf(argv[++i], "%dx%d@%d", &display.width, &display.height, &display.dpi) < 2) {
                usage();
                return 2;
            }
        } else if (a == "--simulate-app-seccomp") {
            // Tests: behave like Android's app seccomp policy (SIGSYS on mount & co.).
            cfg.simulateTrap = {SYS_mount, SYS_umount2, SYS_pivot_root, SYS_chroot, SYS_reboot, SYS_swapon, SYS_bpf,
                                SYS_setuid, SYS_setgid, SYS_setreuid, SYS_setregid, SYS_setresuid, SYS_setresgid,
                                SYS_setfsuid, SYS_setfsgid, SYS_setgroups, SYS_capset, SYS_fchown};
#if defined(__x86_64__)
            // i386: mount umount2 pivot_root chroot reboot swapon bpf, set*id32, setgroups32, capset, fchown32
            cfg.simulateTrap32 = {21, 52, 217, 61, 88, 87, 357, 213, 214, 203, 204, 208, 210, 215, 216, 206, 185, 207};
#endif
        } else if (a == "--pid1") {
            cfg.virtualInitPid = true;
        } else if (a == "-n") {
            cfg.fakeRoot = false;
        } else if (a == "-v") {
            cfg.verbose++;
        } else if (a == "-s") {
            stats = true;
        } else if (a == "-c") {
            clean = true;
        } else if (a == "-E" && i + 1 < argc) {
            extraEnv.push_back(argv[++i]);
        } else {
            usage();
            return 2;
        }
    }
    if (i >= argc) { usage(); return 2; }

    std::vector<std::string> args(argv + i, argv + argc);
    std::vector<std::string> env;
    if (!clean)
        for (char** e = environ; *e; e++) env.emplace_back(*e);
    for (auto& kv : extraEnv) {
        std::string key = kv.substr(0, kv.find('=') + 1);
        for (auto it = env.begin(); it != env.end();) it = it->rfind(key, 0) == 0 ? env.erase(it) : it + 1;
        env.push_back(kv);
    }

    if (!androidState.empty()) {
        std::string root;
        for (const auto& m : cfg.vfs.mounts())
            if (m.guest == "/") root = m.host;
        if (!nest::applyAndroidProfile(cfg, androidState, root)) {
            perror("nest: android profile");
            return 125;
        }
        setenv("NEST_STATE_DIR", androidState.c_str(), 1);
        // Display (graphics, step 1): the guest's composer connects to /run/user/1000/wayland-0.
        mkdir((androidState + "/run").c_str(), 0755);
        cfg.vfs.bind("/run/user/1000", androidState + "/run", false, true);
        // Graphics step 2: the phone's own GPU driver (--gpu), else the guest's SwiftShader.
        nest::GpuSetup gpu;
        if (useGpu) {
            gpu = nest::setupHostGpu(cfg);
            for (auto& n : gpu.notes) fprintf(stderr, "nest: gpu: %s\n", n.c_str());
            fprintf(stderr, "nest: gpu: %s\n", gpu.ok ? "host GPU driver in use" : "software rendering (SwiftShader)");
        }
        if (!gpu.ok) {  // leftovers of a GPU run must not reach a software run
            nest::cleanGpuPlaceholders(cfg);
            std::string frag;
            if (cfg.vfs.resolve("/vendor/etc/vintf/manifest/nest-gpu.xml", "/", false, frag)) unlink(frag.c_str());
            if (cfg.vfs.resolve("/vendor/etc/init/nest-gpu-allocator.rc", "/", false, frag)) unlink(frag.c_str());
        }
        // Properties the display needs, appended to the vendor's build.prop (a private copy).
        std::string vendorProp;
        if (cfg.vfs.resolve("/vendor/build.prop", "/", true, vendorProp)) {
            std::string copy = androidState + "/vendor-build.prop";
            std::string text;
            if (FILE* f = fopen(vendorProp.c_str(), "re")) {
                char buf[4096];
                size_t n;
                while ((n = fread(buf, 1, sizeof(buf), f)) > 0) text.append(buf, n);
                fclose(f);
            }
            if (!text.empty() && text.back() != '\n') text += '\n';
            text += "# Matryoshka Nest display\nro.sf.lcd_density=" + std::to_string(display.dpi) + "\n";
            // Shared memory: memfd where the kernel has sealing for it (5.1+; libcutils checks and
            // falls back to /dev/ashmem otherwise, e.g. on a 4.14 phone kernel).
            text += "sys.use_memfd=true\n";
            // Waydroid's vendor HALs: the sensors HAL registers its stub only when asked (it exits
            // otherwise, and system_server's watchdog then kills it waiting for sensors); one window.
            text += "waydroid.stub_sensors_hal=1\nwaydroid.multi_windows=false\n";
            // What Waydroid's host side normally sets: buffer type for the composer HAL (without it
            // hwcomposer.waydroid presents nothing), software EGL.
            if (gpu.ok) {
                for (auto& pl : gpu.props) text += pl + "\n";
            } else {
                text += "ro.hardware.gralloc=default\nro.hardware.egl=swiftshader\n";
            }
            // the whole Android screen in one window (default "none": hwcomposer shows nothing)
            text += "waydroid.active_apps=Waydroid\n";
            // No A/B checkpoints in a VM: vold's key storage otherwise waits for the checkpoint
            // commit (vold.checkpoint_committed, set only after boot) while holding its lock, and
            // the user unlock, hence the boot, waits for vold -> system_server's watchdog fires.
            text += "vold.checkpoint_committed=1\n";
            // Codec2 linear buffers from gralloc (BLOB) instead of ION, only where the host has no
            // /dev/ion (newer kernels): Codec2 crashes its user (system_server) without one of them.
            if (access("/dev/ion", R_OK) != 0) text += "debug.stagefright.c2-poolmask=786432\n";
            // Memory: a 4 GB phone hosts this whole Android. Low-RAM mode keeps fewer apps cached,
            // trims caches and lightens the UI (the guest's lmkd cannot read the host's memory
            // state, so nothing else would make room); modest Java heaps.
            text += "ro.config.low_ram=true\ndalvik.vm.heapgrowthlimit=128m\ndalvik.vm.heapsize=256m\n";
            if (FILE* f = fopen(copy.c_str(), "we")) {
                fwrite(text.data(), 1, text.size(), f);
                fclose(f);
                cfg.vfs.bind("/vendor/build.prop", copy, true, true);
            }
        }
        // Speed: shorter UI animations once booted (software rendering draws every animation frame).
        std::string initDir;
        if (cfg.vfs.resolve("/vendor/etc/init", "/", true, initDir)) {
            if (FILE* f = fopen((initDir + "/matryoshka-nest.rc").c_str(), "we")) {
                fputs("# Matryoshka Nest: faster UI on a software renderer\n"
                      "on property:sys.boot_completed=1\n"
                      "    exec - system system -- /system/bin/settings put global window_animation_scale 0.5\n"
                      "    exec - system system -- /system/bin/settings put global transition_animation_scale 0.5\n"
                      "    exec - system system -- /system/bin/settings put global animator_duration_scale 0.5\n",
                      f);
                fclose(f);
            }
        }
    }
    if (!preload.empty()) {
        // Fast path: the preload library must be visible inside the guest at its host path, and
        // reads the live mount table from a shared file (NEST_STATE_DIR or /tmp).
        cfg.preloadHost = preload;
        cfg.androidPreload = !androidState.empty();
        if (!cfg.androidPreload) {
            for (const std::string& lib : {preload, cfg.preload32Host}) {
                if (lib.empty()) continue;
                std::string dir = lib.substr(0, lib.rfind('/'));
                cfg.vfs.bind(dir, dir, true);
            }
        }
        const char* st = getenv("NEST_STATE_DIR");
        std::string vfsFile = std::string(st && *st ? st : "/tmp") + "/nest-vfs-" + std::to_string(getpid());
        cfg.vfs.bind(vfsFile, vfsFile, true);
        cfg.vfsSharePath = vfsFile;
        cfg.injectEnv = {"NEST_VFS_FILE=" + vfsFile};
    }
    {
        const char* st = getenv("NEST_STATE_DIR");
        if (st && *st) cfg.stateDir = st;
    }
    if (!binderSock.empty()) cfg.injectEnv.push_back("NEST_BINDER_SOCK=" + binderSock);
    // Host identity of every guest file (virtual ownership in the preload's stat wrappers).
    cfg.injectEnv.push_back("NEST_HOST_UID=" + std::to_string(getuid()));
    if (getenv("NEST_HIDE_EMU_THREADS")) cfg.injectEnv.push_back("NEST_HIDE_EMU_THREADS=1");  // bench (qemu-user)
    // bench: extra variables for every guest program, ';'-separated (e.g. QEMU_STRACE=1;QEMU_LOG_FILENAME=/tmp/q-%d)
    if (const char* extra = getenv("NEST_BENCH_ENV")) {
        std::string all = extra;
        for (size_t at = 0; at < all.size();) {
            size_t end = all.find(';', at);
            if (end == std::string::npos) end = all.size();
            if (end > at) cfg.injectEnv.push_back(all.substr(at, end - at));
            at = end + 1;
        }
    }
    cfg.injectEnv.push_back("NEST_HOST_GID=" + std::to_string(getgid()));
    // Guest logcat tee: liblog's connect to /dev/socket/logdw reaches the tee, which keeps a copy
    // in STATE/logcat.txt and forwards to the guest logd's socket (created by guest init's bind).
    nest::GuestLog guestLog;
    if (!androidState.empty()) {
        std::string logdw = androidState + "/dev/socket/logdw", tee = androidState + "/logtee";
        cfg.connectRedirect[logdw] = tee;
        guestLog.start(tee, androidState + "/logcat.txt", logdw);
    }
    timespec t0{}, t1{};
    clock_gettime(CLOCK_MONOTONIC, &t0);
    const int logLevel = cfg.verbose;
    nest::Tracer tracer(std::move(cfg));
    // Userspace binder driver for the guest; reports each process's *virtual* euid.
    std::unique_ptr<nest::vbinder::Broker> broker;
    std::thread brokerThread;
    if (!binderSock.empty()) {
        broker.reset(new nest::vbinder::Broker(binderSock, [&tracer](int pid) { return tracer.credsOf(pid).euid; }));
        if (!broker->listen()) {
            perror("nest: binder socket");
            return 125;
        }
        brokerThread = std::thread([&] { broker->serve(); });
    }
    // Guest display: Wayland server for the composer HAL, frames into STATE/display.fb, touch
    // input from the app as datagrams on STATE/input.sock ("d|u|m ID X Y").
    std::unique_ptr<nest::WaylandServer> wayland;
    int inputFd = -1;
    std::thread inputThread;
    if (!androidState.empty()) {
        wayland.reset(new nest::WaylandServer(androidState + "/run/wayland-0", androidState + "/display.fb", display));
        if (!wayland->start()) {
            perror("nest: wayland display");
            wayland.reset();
        } else {
            std::string in = androidState + "/input.sock";
            unlink(in.c_str());
            inputFd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
            sockaddr_un sa{};
            sa.sun_family = AF_UNIX;
            if (in.size() < sizeof(sa.sun_path)) memcpy(sa.sun_path, in.c_str(), in.size() + 1);
            if (inputFd >= 0 && bind(inputFd, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) == 0) {
                inputThread = std::thread([&] {
                    char msg[64];
                    for (;;) {
                        ssize_t n = recv(inputFd, msg, sizeof(msg) - 1, 0);
                        if (n <= 0) break;
                        msg[n] = 0;
                        char kind = 0;
                        int id = 0;
                        float x = 0, y = 0;
                        if (sscanf(msg, "%c %d %f %f", &kind, &id, &x, &y) < 2) continue;
                        wayland->sendTouch(kind == 'd' ? 0 : kind == 'u' ? 1 : 2, id, x, y);
                    }
                });
            }
        }
    }
    // Tester log: host memory and display progress every 30 s (low memory explains SIGKILLs from
    // the phone's own memory killer, which the guest never sees coming).
    if (!androidState.empty() && logLevel) {
        std::thread([&] {
            for (int t = 30;; t += 30) {
                sleep(30);
                long availKb = -1, swapFreeKb = -1;
                if (FILE* f = fopen("/proc/meminfo", "re")) {
                    char line[128];
                    while (fgets(line, sizeof(line), f)) {
                        sscanf(line, "MemAvailable: %ld kB", &availKb);
                        sscanf(line, "SwapFree: %ld kB", &swapFreeKb);
                    }
                    fclose(f);
                }
                if (t % 60 == 0) {
                    // processes of this uid that are not simply running/sleeping (zombie, stopped,
                    // disk wait): ActivityManager treats Z/X as dead
                    std::string odd;
                    int total = 0;
                    if (DIR* d = opendir("/proc")) {
                        while (dirent* e = readdir(d)) {
                            if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
                            char path[64], buf[512] = {};
                            snprintf(path, sizeof(path), "/proc/%s/stat", e->d_name);
                            struct stat pst;
                            if (stat(path, &pst) != 0 || pst.st_uid != getuid()) continue;
                            int fd = open(path, O_RDONLY | O_CLOEXEC);
                            if (fd < 0) continue;
                            ssize_t n = read(fd, buf, sizeof(buf) - 1);
                            close(fd);
                            const char* close_ = n > 0 ? strrchr(buf, ')') : nullptr;
                            if (!close_ || !close_[1] || !close_[2]) continue;
                            total++;
                            char st = close_[2];
                            if (st == 'S' || st == 'R' || st == 'I') continue;
                            const char* open_ = strchr(buf, '(');
                            odd += " " + std::string(e->d_name) + ":" + st + ":" +
                                   (open_ ? std::string(open_ + 1, close_) : std::string("?"));
                        }
                        closedir(d);
                    }
                    fprintf(stderr, "nest: ps total=%d not-running/sleeping:%s\n", total, odd.empty() ? " none" : odd.c_str());
                    // Storage daemon and its helpers: system_server's watchdog kills everything when
                    // vold stops answering, so show where each of their threads waits.
                    if (DIR* d = opendir("/proc")) {
                        while (dirent* e = readdir(d)) {
                            if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
                            char path[96], cmd[1024] = {};
                            snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
                            int fd = open(path, O_RDONLY | O_CLOEXEC);
                            if (fd < 0) continue;
                            ssize_t n = read(fd, cmd, sizeof(cmd) - 1);
                            close(fd);
                            if (n <= 0) continue;
                            for (ssize_t k = 0; k < n; k++) if (!cmd[k]) cmd[k] = ' ';
                            if (!strstr(cmd, "/system/bin/vold") && !strstr(cmd, "/system/bin/sgdisk") &&
                                !strstr(cmd, "/system/bin/vdc"))
                                continue;
                            std::string line = std::string("nest: threads ") + e->d_name + " (" + cmd + "):";
                            snprintf(path, sizeof(path), "/proc/%s/task", e->d_name);
                            if (DIR* td = opendir(path)) {
                                while (dirent* t = readdir(td)) {
                                    if (t->d_name[0] < '0' || t->d_name[0] > '9') continue;
                                    char f[160], wchan[64] = {}, sc[96] = {};
                                    snprintf(f, sizeof(f), "/proc/%s/task/%s/wchan", e->d_name, t->d_name);
                                    if ((fd = open(f, O_RDONLY | O_CLOEXEC)) >= 0) { ssize_t r = read(fd, wchan, sizeof(wchan) - 1); (void)r; close(fd); }
                                    snprintf(f, sizeof(f), "/proc/%s/task/%s/syscall", e->d_name, t->d_name);
                                    if ((fd = open(f, O_RDONLY | O_CLOEXEC)) >= 0) {
                                        ssize_t r = read(fd, sc, sizeof(sc) - 1);
                                        close(fd);
                                        if (r > 0 && sc[r - 1] == '\n') sc[r - 1] = 0;
                                        if (char* sp = strchr(sc, ' ')) if ((sp = strchr(sp + 1, ' '))) if ((sp = strchr(sp + 1, ' '))) *sp = 0;
                                    }
                                    line += std::string(" ") + t->d_name + "[" + wchan + " " + sc + "]";
                                }
                                closedir(td);
                            }
                            fprintf(stderr, "%s\n", line.c_str());
                        }
                        closedir(d);
                    }
                }
                // Speed profile of the last 30 s: CPU per process (guest and engine threads) and the
                // tracer's slow-path stops per syscall / per process.
                {
                    static std::map<std::string, unsigned long long> lastCpu;
                    std::vector<std::pair<unsigned long long, std::string>> cpu;
                    auto scan = [&](const std::string& dir, const std::string& prefix) {
                        DIR* d = opendir(dir.c_str());
                        if (!d) return;
                        while (dirent* e = readdir(d)) {
                            if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
                            std::string base = dir + "/" + e->d_name;
                            struct stat pst;
                            if (stat(base.c_str(), &pst) != 0 || pst.st_uid != getuid()) continue;
                            char buf[512] = {};
                            int fd = open((base + "/stat").c_str(), O_RDONLY | O_CLOEXEC);
                            if (fd < 0) continue;
                            ssize_t n = read(fd, buf, sizeof(buf) - 1);
                            close(fd);
                            const char* cp = n > 0 ? strrchr(buf, ')') : nullptr;
                            if (!cp) continue;
                            unsigned long long ut = 0, stt = 0;
                            // fields after ')': state(3) ... utime(14) stime(15)
                            if (sscanf(cp + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &ut, &stt) != 2) continue;
                            const char* op = strchr(buf, '(');
                            std::string name = prefix + std::string(e->d_name) + ":" + (op ? std::string(op + 1, cp) : "?");
                            unsigned long long total = ut + stt, prev = lastCpu.count(name) ? lastCpu[name] : 0;
                            lastCpu[name] = total;
                            if (total > prev) cpu.push_back({total - prev, name});
                        }
                        closedir(d);
                    };
                    scan("/proc", "");
                    scan("/proc/self/task", "engine-thread ");
                    std::sort(cpu.rbegin(), cpu.rend());
                    long hz = sysconf(_SC_CLK_TCK);
                    std::string line;
                    for (size_t k = 0; k < cpu.size() && k < 10; k++) {
                        char b[160];
                        snprintf(b, sizeof(b), " %s=%.0f%%", cpu[k].second.c_str(), 100.0 * cpu[k].first / hz / 30.0);
                        line += b;
                    }
                    fprintf(stderr, "nest: cpu (30s, %% of one core):%s\n", line.c_str());
                    auto& prof = tracer.profile();
                    static uint32_t lastSys[2][nest::TracerProfile::kSlots];
                    std::vector<std::pair<uint32_t, int>> hot;
                    for (int c = 0; c < 2; c++)
                        for (int k = 0; k < nest::TracerProfile::kSlots; k++) {
                            uint32_t v = prof.bySyscall[c][k].load(std::memory_order_relaxed);
                            if (v > lastSys[c][k]) hot.push_back({v - lastSys[c][k], c * 10000 + k});
                            lastSys[c][k] = v;
                        }
                    std::sort(hot.rbegin(), hot.rend());
                    line.clear();
                    uint64_t sum = 0;
                    for (auto& h : hot) sum += h.first;
                    for (size_t k = 0; k < hot.size() && k < 12; k++) {
                        char b[48];
                        snprintf(b, sizeof(b), " %s%d=%u", hot[k].second >= 10000 ? "c" : "", hot[k].second % 10000, hot[k].first);
                        line += b;
                    }
                    fprintf(stderr, "nest: slow-path stops (30s) total=%llu by syscall:%s\n", static_cast<unsigned long long>(sum), line.c_str());
                    static uint32_t lastPid[65536];
                    std::vector<std::pair<uint32_t, int>> hp;
                    for (int k = 0; k < 65536; k++) {
                        uint32_t v = prof.byPid[k].load(std::memory_order_relaxed);
                        if (v > lastPid[k]) hp.push_back({v - lastPid[k], k});
                        lastPid[k] = v;
                    }
                    std::sort(hp.rbegin(), hp.rend());
                    line.clear();
                    for (size_t k = 0; k < hp.size() && k < 8; k++) {
                        char path[48], name[64] = "?";
                        snprintf(path, sizeof(path), "/proc/%d/comm", hp[k].second);
                        int fd = open(path, O_RDONLY | O_CLOEXEC);
                        if (fd >= 0) {
                            ssize_t n = read(fd, name, sizeof(name) - 1);
                            close(fd);
                            if (n > 0) name[n - 1] = 0;
                        }
                        char b[96];
                        snprintf(b, sizeof(b), " %d(%s)=%u", hp[k].second, name, hp[k].first);
                        line += b;
                    }
                    fprintf(stderr, "nest: slow-path stops by process:%s\n", line.c_str());
                }
                fprintf(stderr, "nest: t=%ds mem_avail=%ldMB swap_free=%ldMB frames=%llu\n", t, availKb / 1024,
                        swapFreeKb / 1024, static_cast<unsigned long long>(wayland ? wayland->frames() : 0));
            }
        }).detach();
    }
    int rc = tracer.run(args[0], args, env);
    if (inputFd >= 0) {
        shutdown(inputFd, SHUT_RDWR);
        close(inputFd);
    }
    if (inputThread.joinable()) inputThread.detach();
    if (wayland && stats) fprintf(stderr, "nest: display frames=%llu\n", (unsigned long long)wayland->frames());
    if (wayland) wayland->stop();
    clock_gettime(CLOCK_MONOTONIC, &t1);
    if (stats) {
        const auto& s = tracer.stats();
        double ms = (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
        fprintf(stderr,
                "nest: exit=%d time=%.1fms processes=%llu stops=%llu paths=%llu execs=%llu exec_rewrites=%llu faked=%llu denied=%llu env=%llu emulated=%llu sigsys=%llu mounts=%llu\n",
                rc, ms, (unsigned long long)s.processes, (unsigned long long)s.stops,
                (unsigned long long)s.pathRewrites, (unsigned long long)s.execs,
                (unsigned long long)s.execRewrites, (unsigned long long)s.faked, (unsigned long long)s.denied, (unsigned long long)s.envInjections,
                (unsigned long long)s.emulated, (unsigned long long)s.sigsys, (unsigned long long)s.mounts);
    }
    if (broker) {
        broker->stop();
        brokerThread.join();
    }
    if (rc < 0) {
        fprintf(stderr, "nest: failed to start the guest (ptrace/seccomp unavailable?)\n");
        return 125;
    }
    return rc;
}
