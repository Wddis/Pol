// Nest — userspace virtualization engine for Matryoshka.
// vfs: maps the guest's view of the filesystem onto host directories.
//
// The guest sees "/" (its rootfs), "/data" (per-VM data), "/proc", "/dev", ... Each entry of the
// mount table binds a guest path prefix to a host directory, longest prefix wins. Paths are
// resolved component by component so that symlinks inside the guest (which are usually absolute,
// e.g. /system/bin/sh -> toybox, /sdcard -> /storage/self/primary) are followed *inside* the guest
// view instead of escaping to the host.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>
#include <sys/stat.h>
#include <vector>

namespace nest {

// Host filesystem primitives used while resolving paths. The in-process fast path supplies
// versions that bypass interception; the tracer uses plain libc.
struct HostFs {
    int (*lstat)(const char* path, struct stat* st);
    long (*readlink)(const char* path, char* buf, unsigned long size);
};
const HostFs& libcHostFs();

struct Mount {
    std::string guest;   // normalized guest prefix, "/" or "/a/b" (no trailing slash)
    std::string host;    // host directory or file ("" = hidden / not present)
    bool readOnly = false;
    // Engine infrastructure (device nodes, generated /proc files, the preload library): stays
    // visible when the guest mounts something over a parent directory.
    bool sticky = false;
    // Virtual cgroup filesystem: control files (tasks, cgroup.procs, ...) of new groups appear on
    // first write, like the kernel creates them with the directory.
    bool cgroup = false;
    // Stands in for a record device (/dev/kmsg): every write-open appends.
    bool append = false;
};

class Vfs {
public:
    // Adds or replaces a binding. Later bindings of the same guest path replace earlier ones.
    void bind(const std::string& guest, const std::string& host, bool readOnly = false, bool sticky = false);
    // A new mount on `guest` covers everything mounted below it (as in the kernel): drops those
    // bindings, except sticky ones. Returns how many were hidden.
    size_t hideUnder(const std::string& guest);
    void setCgroup(const std::string& guest);
    void setAppend(const std::string& guest);
    bool isAppendHost(const std::string& hostPath) const;
    // True if the host path lies in a virtual cgroup filesystem.
    bool isCgroupHost(const std::string& hostPath) const;
    // Removes a binding (used for virtual umount).
    bool unbind(const std::string& guest);
    const std::vector<Mount>& mounts() const { return mounts_; }

    // Lexical normalization of an absolute guest path: collapses "//", ".", "..". Never goes above "/".
    static std::string normalize(const std::string& path);
    // Joins a base directory with a (possibly relative) path and normalizes.
    static std::string join(const std::string& base, const std::string& path);

    // Pure prefix mapping of a normalized guest path to a host path (no symlink resolution).
    std::string mapLexical(const std::string& guestPath, const Mount** used = nullptr) const;

    // Resolves a guest path (absolute, or relative to guestCwd) to a host path, following guest
    // symlinks in all components; the last one only if followLast. Missing components are kept
    // as-is so that create/mkdir work. Returns false on symlink loops (ELOOP).
    bool resolve(const std::string& path, const std::string& guestCwd, bool followLast,
                 std::string& hostOut, std::string* guestOut = nullptr) const;

    // Host path -> guest path (for getcwd, readlink("/proc/self/exe"), ...). Returns false if the
    // host path is not visible in the guest.
    bool reverse(const std::string& hostPath, std::string& guestOut) const;

    void setHostFs(const HostFs* fs) { fs_ = fs; }

    // Text form used to hand the mount table to guest processes: "guest\thost\tro\n" per line.
    std::string serialize() const;
    bool deserialize(const std::string& text);

private:
    const HostFs* fs_ = &libcHostFs();
    std::vector<Mount> mounts_;  // kept sorted by guest prefix length, descending
};

}  // namespace nest
