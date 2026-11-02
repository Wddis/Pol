// SPDX-License-Identifier: Apache-2.0
#include "android_profile.h"

#include <cstdio>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <unistd.h>

namespace nest {

namespace {

void mkdirs(const std::string& path) {
    for (size_t i = 1; i <= path.size(); i++)
        if (i == path.size() || path[i] == '/') mkdir(path.substr(0, i).c_str(), 0755);
}

bool writeFile(const std::string& path, const std::string& content) {
    FILE* f = fopen(path.c_str(), "w");
    if (!f) return false;
    fputs(content.c_str(), f);
    fclose(f);
    return true;
}

bool exists(const std::string& p) { return access(p.c_str(), F_OK) == 0; }

}  // namespace

bool applyAndroidProfile(TracerConfig& cfg, const std::string& state, const std::string& rootfs) {
    mkdirs(state + "/dev/socket");
    mkdirs(state + "/proc");
    mkdirs(state + "/empty");
    mkdirs(state + "/linkerconfig");
    mkdirs(state + "/mnt");

    utsname u{};
    uname(&u);
    bool ok = true;
    ok &= writeFile(state + "/proc/version",
                    std::string("Linux version ") + u.release + " (matryoshka@nest) #1 SMP PREEMPT\n");
    // No "selinuxfs" here: libselinux then treats SELinux as disabled inside the guest (the host's
    // policy still applies to every guest process).
    ok &= writeFile(state + "/proc/filesystems",
                    "nodev\tsysfs\nnodev\ttmpfs\nnodev\tbdev\nnodev\tproc\nnodev\tcgroup\nnodev\tcgroup2\n"
                    "nodev\tdevpts\nnodev\tbinder\nnodev\tbpf\n\text4\n\tvfat\nnodev\tfuse\n");
    ok &= writeFile(state + "/proc/cmdline",
                    "androidboot.hardware=waydroid androidboot.selinux=permissive androidboot.mvm=nest\n");
    ok &= writeFile(state + "/kmsg", "");
    // SELinux process attributes seen by the guest (getcon/setcon); the host's stay untouched.
    mkdirs(state + "/attr");
    for (const char* a : {"current", "exec", "fscreate", "keycreate", "sockcreate", "prev"})
        ok &= writeFile(state + "/attr/" + a, std::string(a) == "current" || std::string(a) == "prev" ? "u:r:init:s0" : "");
    if (!ok) return false;

    // The guest's own /dev (init creates sockets and the property area in it), with the host
    // devices an app is allowed to use bound in.
    cfg.vfs.bind("/dev", state + "/dev");
    for (const char* d : {"null", "zero", "full", "random", "urandom", "tty", "ptmx", "ashmem", "tun"}) {
        std::string host = std::string("/dev/") + d;
        if (!exists(host)) continue;
        ensureMountPoint(state + "/dev/" + d, false);
        cfg.vfs.bind(std::string("/dev/") + d, host, false, true);
    }
    // Binder device nodes: opened through the vbinder client, but they must exist for access()
    // checks (ProcessState falls back to /dev/binder for a missing /dev/vndbinder).
    for (const char* d : {"binder", "hwbinder", "vndbinder"}) {
        ok &= writeFile(state + "/dev/" + d, "");
        chmod((state + "/dev/" + d).c_str(), 0666);
    }
    mkdirs(state + "/dev/pts");
    cfg.vfs.bind("/dev/pts", "/dev/pts", false, true);
    ensureMountPoint(state + "/dev/kmsg", false);
    cfg.vfs.bind("/dev/kmsg", state + "/kmsg", false, true);
    cfg.vfs.setAppend("/dev/kmsg");

    cfg.vfs.bind("/proc", "/proc");
    for (const char* f : {"version", "filesystems", "cmdline"})
        cfg.vfs.bind(std::string("/proc/") + f, state + "/proc/" + f, false, true);
    cfg.vfs.bind("/sys", "/sys");
    cfg.vfs.bind("/sys/fs/selinux", state + "/empty", false, true);
    cfg.vfs.bind("/linkerconfig", state + "/linkerconfig");

    // Flattened APEXes: what apexd/init would bind at /apex/<name>.
    std::string apexDir = rootfs + "/system/apex";
    if (DIR* d = opendir(apexDir.c_str())) {
        while (dirent* e = readdir(d)) {
            std::string n = e->d_name;
            if (n == "." || n == ".." || n.find(".apex") != std::string::npos) continue;
            ensureMountPoint(rootfs + "/apex/" + n, true);
            cfg.vfs.bind("/apex/" + n, apexDir + "/" + n);
        }
        closedir(d);
    }
    cfg.stateDir = state;
    cfg.procAttrDir = state + "/attr";
    cfg.virtualInitPid = true;  // init is pid 1
    return true;
}

}  // namespace nest
