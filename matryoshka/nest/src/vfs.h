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
#include <vector>

namespace nest {

struct Mount {
    std::string guest;   // normalized guest prefix, "/" or "/a/b" (no trailing slash)
    std::string host;    // host directory or file ("" = hidden / not present)
    bool readOnly = false;
};

class Vfs {
public:
    // Adds or replaces a binding. Later bindings of the same guest path replace earlier ones.
    void bind(const std::string& guest, const std::string& host, bool readOnly = false);
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

private:
    std::vector<Mount> mounts_;  // kept sorted by guest prefix length, descending
};

}  // namespace nest
