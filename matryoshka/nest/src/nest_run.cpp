// nest-run: command-line supervisor for Nest guests.
//
// On Android the app launches it as libnest_run.so from its native library directory (the only
// place apps may execute code from regardless of targetSdk). On Linux it is used by the tests.
//
//   nest-run --probe DIR                       run the capability probe
//   nest-run [-b GUEST=HOST[:ro]]... [-w GUEST_CWD] [-0|-n] [-v]... [-s] [-E K=V]... [-c] -- PROGRAM [ARGS...]
//
//   -b   bind a host directory/file at a guest path ("-b /=rootfs" sets the guest root)
//   -w   guest working directory (default "/")
//   -0   fake root identity (default) ; -n  real identity
//   -v   verbose (repeat for more)    ; -s  print statistics to stderr on exit
//   -E   add/override an environment variable ; -c  start with a clean environment
//
// SPDX-License-Identifier: Apache-2.0
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "probe.h"
#include "tracer.h"

extern char** environ;

static void usage() {
    fprintf(stderr, "usage: nest-run [-b GUEST=HOST[:ro]]... [-w CWD] [-0|-n] [-v] [-s] [-E K=V]... [-c] -- PROG [ARGS]\n");
}

int main(int argc, char** argv) {
    // Capability probe mode: nest-run --probe <writable dir>
    if (argc == 3 && strcmp(argv[1], "--probe") == 0) {
        fputs(nest::runProbe(argv[2]).c_str(), stdout);
        return 0;
    }
    nest::TracerConfig cfg;
    bool stats = false, clean = false;
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

    timespec t0{}, t1{};
    clock_gettime(CLOCK_MONOTONIC, &t0);
    nest::Tracer tracer(std::move(cfg));
    int rc = tracer.run(args[0], args, env);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    if (stats) {
        const auto& s = tracer.stats();
        double ms = (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
        fprintf(stderr,
                "nest: exit=%d time=%.1fms processes=%llu stops=%llu paths=%llu execs=%llu exec_rewrites=%llu faked=%llu\n",
                rc, ms, (unsigned long long)s.processes, (unsigned long long)s.stops,
                (unsigned long long)s.pathRewrites, (unsigned long long)s.execs,
                (unsigned long long)s.execRewrites, (unsigned long long)s.faked);
    }
    if (rc < 0) {
        fprintf(stderr, "nest: failed to start the guest (ptrace/seccomp unavailable?)\n");
        return 125;
    }
    return rc;
}
