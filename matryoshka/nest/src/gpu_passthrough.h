// GPU passthrough (graphics, step 2): the guest renders with the phone's own GPU driver.
//
// The guest is Android 11 like the phone (same VNDK), and an app may open the GPU device and read
// the vendor's graphics libraries. So the guest gets the host's GPU device node, the vendor EGL/GLES
// driver (/vendor/lib*/egl) and the vendor gralloc module (/vendor/lib*/hw/gralloc.<platform>.so),
// plus every vendor library those need that the guest's own vendor does not have. With
// ro.hardware.gralloc=<platform> Waydroid's composer treats the buffers as "android" type and reads
// each frame back through EGL into shared memory (it has no wlegl from us), which our Wayland server
// already presents.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>
#include <vector>

#include "tracer.h"

namespace nest {

struct GpuSetup {
    bool ok = false;
    std::string platform;               // ro.board.platform of the host (gralloc module name)
    std::vector<std::string> props;     // build.prop lines for the guest
    std::vector<std::string> notes;     // for the log
};

// Binds the host's GPU driver into the guest view. `hostVendor` is the host's /vendor; guest paths
// are resolved through cfg.vfs. Nothing is changed when a requirement is missing (ok == false).
GpuSetup setupHostGpu(TracerConfig& cfg, const std::string& hostVendor = "/vendor");

// Removes empty placeholder libraries left by earlier GPU runs.
void cleanGpuPlaceholders(TracerConfig& cfg);

// DT_NEEDED entries of an ELF shared object (32- or 64-bit).
std::vector<std::string> elfNeeded(const std::string& path);

}  // namespace nest
