// SPDX-License-Identifier: Apache-2.0
#include "gpu_passthrough.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#if defined(__BIONIC__)
#include <sys/system_properties.h>
#endif

namespace nest {

namespace {

bool readable(const std::string& p) { return access(p.c_str(), R_OK) == 0; }

std::string hostProp(const char* name) {
#if defined(__BIONIC__)
    char v[PROP_VALUE_MAX] = {};
    __system_property_get(name, v);
    return v;
#else
    (void)name;
    return {};
#endif
}

template <typename Ehdr, typename Phdr, typename Dyn>
std::vector<std::string> neededOf(const std::vector<uint8_t>& f) {
    std::vector<std::string> out;
    if (f.size() < sizeof(Ehdr)) return out;
    const auto* eh = reinterpret_cast<const Ehdr*>(f.data());
    if (eh->e_phoff == 0 || eh->e_phoff + static_cast<uint64_t>(eh->e_phnum) * sizeof(Phdr) > f.size()) return out;
    const auto* ph = reinterpret_cast<const Phdr*>(f.data() + eh->e_phoff);
    // DT_STRTAB is a virtual address: map it to a file offset through the PT_LOAD segments.
    auto toOff = [&](uint64_t va) -> uint64_t {
        for (int i = 0; i < eh->e_phnum; i++)
            if (ph[i].p_type == PT_LOAD && va >= ph[i].p_vaddr && va < ph[i].p_vaddr + ph[i].p_filesz)
                return va - ph[i].p_vaddr + ph[i].p_offset;
        return UINT64_MAX;
    };
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type != PT_DYNAMIC || ph[i].p_offset + ph[i].p_filesz > f.size()) continue;
        const auto* dyn = reinterpret_cast<const Dyn*>(f.data() + ph[i].p_offset);
        size_t n = ph[i].p_filesz / sizeof(Dyn);
        uint64_t strtab = 0;
        for (size_t k = 0; k < n && dyn[k].d_tag != DT_NULL; k++)
            if (dyn[k].d_tag == DT_STRTAB) strtab = toOff(dyn[k].d_un.d_ptr);
        if (strtab == UINT64_MAX || strtab == 0) return out;
        for (size_t k = 0; k < n && dyn[k].d_tag != DT_NULL; k++) {
            if (dyn[k].d_tag != DT_NEEDED) continue;
            uint64_t o = strtab + dyn[k].d_un.d_val;
            if (o >= f.size()) continue;
            const char* s = reinterpret_cast<const char*>(f.data() + o);
            out.emplace_back(s, strnlen(s, f.size() - o));
        }
    }
    return out;
}

}  // namespace

std::vector<std::string> elfNeeded(const std::string& path) {
    std::vector<uint8_t> f;
    if (FILE* fp = fopen(path.c_str(), "re")) {
        uint8_t buf[65536];
        size_t n;
        while ((n = fread(buf, 1, sizeof(buf), fp)) > 0) f.insert(f.end(), buf, buf + n);
        fclose(fp);
    }
    if (f.size() < EI_NIDENT || memcmp(f.data(), ELFMAG, SELFMAG) != 0) return {};
    if (f[EI_CLASS] == ELFCLASS64) return neededOf<Elf64_Ehdr, Elf64_Phdr, Elf64_Dyn>(f);
    return neededOf<Elf32_Ehdr, Elf32_Phdr, Elf32_Dyn>(f);
}

GpuSetup setupHostGpu(TracerConfig& cfg, const std::string& hostVendor) {
    GpuSetup g;
    g.platform = hostProp("ro.board.platform");
    std::string egl = hostProp("ro.hardware.egl");
    if (egl.empty()) egl = "adreno";
    int fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        g.notes.push_back(std::string("no GPU device: ") + strerror(errno));
        return g;
    }
    close(fd);
    if (g.platform.empty()) {
        g.notes.push_back("no ro.board.platform");
        return g;
    }
    const std::string driver64 = hostVendor + "/lib64/egl/libEGL_" + egl + ".so";
    const std::string gralloc64 = hostVendor + "/lib64/hw/gralloc." + g.platform + ".so";
    if (!readable(driver64) || !readable(gralloc64)) {
        g.notes.push_back("host driver unreadable: " + driver64 + " / " + gralloc64);
        return g;
    }

    auto guestHost = [&](const std::string& guest, std::string& host) { return cfg.vfs.resolve(guest, "/", false, host); };
    auto guestHas = [&](const std::string& guest) {
        std::string h;
        struct stat st;
        return guestHost(guest, h) && stat(h.c_str(), &st) == 0 && st.st_size > 0;
    };
    int bound = 0;
    auto bindFile = [&](const std::string& guest, const std::string& host) {
        std::string placeholder;
        if (guestHost(guest, placeholder)) ensureMountPoint(placeholder, false);  // visible in listings
        cfg.vfs.bind(guest, host, true, true);
        bound++;
    };

    for (const char* lib : {"lib64", "lib"}) {
        const std::string hv = hostVendor + "/" + lib;
        const std::string gv = std::string("/vendor/") + lib;
        std::vector<std::string> todo;
        std::set<std::string> seen;
        // the EGL/GLES driver and its tools, and the gralloc module
        if (DIR* d = opendir((hv + "/egl").c_str())) {
            while (dirent* e = readdir(d)) {
                std::string n = e->d_name;
                if (n.find(".so") == std::string::npos || !readable(hv + "/egl/" + n)) continue;
                bindFile(gv + "/egl/" + n, hv + "/egl/" + n);
                todo.push_back(hv + "/egl/" + n);
            }
            closedir(d);
        }
        std::string gralloc = hv + "/hw/gralloc." + g.platform + ".so";
        if (readable(gralloc)) {
            bindFile(gv + "/hw/gralloc." + g.platform + ".so", gralloc);
            todo.push_back(gralloc);
        }
        // whatever those need from the host's vendor that the guest does not provide
        while (!todo.empty()) {
            std::string f = todo.back();
            todo.pop_back();
            for (const std::string& need : elfNeeded(f)) {
                if (!seen.insert(need).second) continue;
                bool provided = guestHas(gv + "/" + need) || guestHas(std::string("/system/") + lib + "/" + need) ||
                                guestHas(std::string("/system/apex/com.android.vndk.current/") + lib + "/" + need) ||
                                guestHas(std::string("/system/apex/com.android.runtime/") + lib + "/bionic/" + need);
                if (provided) continue;
                std::string host = hv + "/" + need;
                if (!readable(host)) {
                    g.notes.push_back(std::string(lib) + ": missing " + need);
                    continue;
                }
                bindFile(gv + "/" + need, host);
                todo.push_back(host);
            }
        }
    }
    std::string devPlaceholder;
    if (guestHost("/dev/kgsl-3d0", devPlaceholder)) ensureMountPoint(devPlaceholder, false);
    cfg.vfs.bind("/dev/kgsl-3d0", "/dev/kgsl-3d0", false, true);

    g.props = {"ro.hardware.egl=" + egl, "ro.hardware.gralloc=" + g.platform,
               // linear buffers: the composer reads frames back, compressed (UBWC) ones it cannot
               "vendor.gralloc.disable_ubwc=1", "debug.gralloc.gfx_ubwc_disable=1",
               "vendor.display.disable_ubwc=1"};
    g.notes.push_back("bound " + std::to_string(bound) + " host GPU files (" + g.platform + ", " + egl + ")");
    g.ok = true;
    return g;
}

}  // namespace nest
