// Environment a guest Android 11 userspace expects from its kernel and first-stage init, built
// without root: device nodes the app may use, a private /dev, /dev/kmsg into a log file,
// /proc files the host's SELinux hides from apps, SELinux reported as absent, flattened APEXes.
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>

#include "tracer.h"

namespace nest {

// Prepares `stateDir` and adds the bindings to cfg. `rootfsHost` is the host directory bound at
// "/" (used to mount the flattened APEXes early).
bool applyAndroidProfile(TracerConfig& cfg, const std::string& stateDir, const std::string& rootfsHost);

}  // namespace nest
