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
#include <cstdlib>
#include <cstring>
#include <ctime>
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
            if (FILE* f = fopen(copy.c_str(), "we")) {
                fwrite(text.data(), 1, text.size(), f);
                fclose(f);
                cfg.vfs.bind("/vendor/build.prop", copy, true, true);
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
