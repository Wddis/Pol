// Minimal Wayland compositor for the guest's display (graphics, step 1).
//
// The guest's composer HAL (Waydroid's hwcomposer, Apache-2.0) presents frames as a Wayland client.
// This server speaks just enough of the core protocol and xdg-shell for it: shared-memory buffers
// (wl_shm, software rendering with SwiftShader), surfaces and subsurfaces, one output and a seat.
// Each committed frame of the toplevel window is copied into a shared framebuffer file that the
// app maps and draws; touch input travels back as wl_touch events (sendTouch).
//
// Framebuffer file layout: FbHeader, then height rows of width*4 bytes.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

namespace nest {

struct FbHeader {
    uint32_t magic;   // kFbMagic
    uint32_t width, height, stride;
    uint32_t format;  // wl_shm format of the pixels (ARGB8888 = 0, XRGB8888 = 1, ABGR8888, XBGR8888)
    uint32_t pad;
    std::atomic<uint64_t> seq;  // incremented after each complete frame
    uint64_t reserved[4];
};
constexpr uint32_t kFbMagic = 0x3142464e;  // "NFB1"

struct DisplayConfig {
    int width = 720, height = 1440, dpi = 320, refreshHz = 60;
};

class WaylandServer {
public:
    // socketPath: host path the guest connects to; fbPath: shared framebuffer file.
    WaylandServer(std::string socketPath, std::string fbPath, DisplayConfig cfg);
    ~WaylandServer();
    bool start();  // listens and starts the server thread
    void stop();

    // Touch input from the app (coordinates in display pixels). down/up/motion.
    void sendTouch(int action, int id, float x, float y);

    uint64_t frames() const;
    int clients() const;

private:
    struct Impl;
    std::unique_ptr<Impl> d_;
};

}  // namespace nest
