// SPDX-License-Identifier: Apache-2.0
#include "vfs.h"

#include <algorithm>
#include <climits>
#include <sys/stat.h>
#include <unistd.h>

namespace nest {

namespace {

bool hasPrefix(const std::string& path, const std::string& prefix) {
    if (prefix == "/") return !path.empty() && path[0] == '/';
    return path.size() >= prefix.size() && path.compare(0, prefix.size(), prefix) == 0 &&
           (path.size() == prefix.size() || path[prefix.size()] == '/');
}

std::vector<std::string> split(const std::string& path) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < path.size()) {
        while (i < path.size() && path[i] == '/') i++;
        size_t j = i;
        while (j < path.size() && path[j] != '/') j++;
        if (j > i) out.emplace_back(path.substr(i, j - i));
        i = j;
    }
    return out;
}

std::string stripTrailing(std::string s) {
    while (s.size() > 1 && s.back() == '/') s.pop_back();
    return s;
}

}  // namespace

std::string Vfs::normalize(const std::string& path) {
    std::vector<std::string> stack;
    for (auto& c : split(path)) {
        if (c == ".") continue;
        if (c == "..") {
            if (!stack.empty()) stack.pop_back();
            continue;
        }
        stack.push_back(c);
    }
    if (stack.empty()) return "/";
    std::string out;
    for (auto& c : stack) {
        out += '/';
        out += c;
    }
    return out;
}

std::string Vfs::join(const std::string& base, const std::string& path) {
    if (!path.empty() && path[0] == '/') return normalize(path);
    return normalize(base + "/" + path);
}

void Vfs::bind(const std::string& guest, const std::string& host, bool readOnly) {
    std::string g = normalize(guest);
    std::string h = host.empty() ? host : stripTrailing(host);
    mounts_.erase(std::remove_if(mounts_.begin(), mounts_.end(), [&](const Mount& m) { return m.guest == g; }),
                  mounts_.end());
    mounts_.push_back({g, h, readOnly});
    std::stable_sort(mounts_.begin(), mounts_.end(),
                     [](const Mount& a, const Mount& b) { return a.guest.size() > b.guest.size(); });
}

bool Vfs::unbind(const std::string& guest) {
    std::string g = normalize(guest);
    auto before = mounts_.size();
    mounts_.erase(std::remove_if(mounts_.begin(), mounts_.end(), [&](const Mount& m) { return m.guest == g; }),
                  mounts_.end());
    return mounts_.size() != before;
}

std::string Vfs::mapLexical(const std::string& guestPath, const Mount** used) const {
    for (const auto& m : mounts_) {
        if (!hasPrefix(guestPath, m.guest)) continue;
        if (used) *used = &m;
        std::string rest = m.guest == "/" ? guestPath : guestPath.substr(m.guest.size());
        if (rest.empty() || rest == "/") return m.host.empty() ? "/nonexistent-mvm" : m.host;
        if (m.host == "/") return rest;
        return m.host + rest;
    }
    if (used) *used = nullptr;
    return guestPath;  // no root binding: identity
}

bool Vfs::resolve(const std::string& path, const std::string& guestCwd, bool followLast,
                  std::string& hostOut, std::string* guestOut) const {
    std::string start = (!path.empty() && path[0] == '/') ? path : guestCwd + "/" + path;
    std::vector<std::string> pending = split(start);
    std::reverse(pending.begin(), pending.end());  // use as a stack: back() = next component
    std::vector<std::string> done;                 // resolved guest components
    bool trailingSlash = path.size() > 1 && path.back() == '/';
    int links = 0;
    bool missing = false;  // once a component does not exist, stop probing the host

    while (!pending.empty()) {
        std::string c = pending.back();
        pending.pop_back();
        if (c == ".") continue;
        if (c == "..") {
            if (!done.empty()) done.pop_back();
            continue;
        }
        done.push_back(c);
        bool last = pending.empty();
        if (missing || (last && !followLast && !trailingSlash)) continue;

        std::string g;
        for (auto& d : done) g += "/" + d;
        std::string h = mapLexical(g);
        struct stat st;
        if (lstat(h.c_str(), &st) != 0) {
            missing = true;
            continue;
        }
        if (!S_ISLNK(st.st_mode)) continue;
        // Bind targets themselves are never treated as guest symlinks.
        const Mount* m = nullptr;
        mapLexical(g, &m);
        if (m && m->guest == g) continue;

        if (++links > 40) return false;
        char buf[PATH_MAX];
        ssize_t n = readlink(h.c_str(), buf, sizeof(buf) - 1);
        if (n < 0) continue;
        buf[n] = 0;
        std::string target(buf);
        done.pop_back();
        if (!target.empty() && target[0] == '/') done.clear();
        auto parts = split(target);
        for (auto it = parts.rbegin(); it != parts.rend(); ++it) pending.push_back(*it);
    }

    std::string g;
    for (auto& d : done) g += "/" + d;
    if (g.empty()) g = "/";
    if (guestOut) *guestOut = g;
    hostOut = mapLexical(g);
    if (trailingSlash && hostOut.back() != '/') hostOut += '/';
    return true;
}

bool Vfs::reverse(const std::string& hostPath, std::string& guestOut) const {
    // Prefer the most specific host directory.
    const Mount* best = nullptr;
    for (const auto& m : mounts_) {
        if (m.host.empty() || !hasPrefix(hostPath, m.host)) continue;
        if (!best || m.host.size() > best->host.size()) best = &m;
    }
    if (!best) return false;
    std::string rest = best->host == "/" ? hostPath : hostPath.substr(best->host.size());
    guestOut = normalize(best->guest + "/" + rest);
    return true;
}

}  // namespace nest
