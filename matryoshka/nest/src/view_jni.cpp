// Direct viewer for the guest's screen (app side, JNI): receives the frames the engine's Wayland
// server announces on STATE/display.sock (see ViewerFrame in wayland_server.h), maps the composer's
// shared-memory pools itself and copies each frame straight into the view's window buffer: one
// copy per frame, no file in between, no work on the app's UI thread; scaling is the compositor's.
//
// SPDX-License-Identifier: Apache-2.0
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <jni.h>
#include <map>
#include <poll.h>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>

#include "wayland_server.h"

namespace {

constexpr int32_t kWindowRgba = 1;  // AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM
constexpr int32_t kWindowBgra = 5;  // HAL_PIXEL_FORMAT_BGRA_8888: the composer's ARGB8888 bytes as they are

struct Viewer {
    ANativeWindow* win = nullptr;
    std::string path;
    std::atomic<bool> stop{false};
    std::atomic<uint64_t> frames{0};
    std::thread th;
    int fd = -1;
    std::map<uint32_t, std::pair<uint8_t*, size_t>> pools;
    uint32_t curW = 0, curH = 0;
    int32_t windowFormat = -1;
    bool bgraFailed = false;

    void dropPools() {
        for (auto& [id, p] : pools) munmap(p.first, p.second);
        pools.clear();
    }

    void disconnect() {
        if (fd >= 0) close(fd);
        fd = -1;
        dropPools();
    }

    bool connectEngine() {
        int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        sockaddr_un sa{};
        sa.sun_family = AF_UNIX;
        if (s < 0 || path.size() >= sizeof(sa.sun_path)) {
            if (s >= 0) close(s);
            return false;
        }
        memcpy(sa.sun_path, path.c_str(), path.size() + 1);
        if (connect(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) != 0) {
            close(s);
            return false;
        }
        fd = s;
        return true;
    }

    // One message; returns false when the connection is gone. `got` is set for a frame.
    bool receive(nest::ViewerFrame& f, bool& got, int flags) {
        got = false;
        char cbuf[CMSG_SPACE(sizeof(int))];
        iovec iov{&f, sizeof(f)};
        msghdr m{};
        m.msg_iov = &iov;
        m.msg_iovlen = 1;
        m.msg_control = cbuf;
        m.msg_controllen = sizeof(cbuf);
        ssize_t n = recvmsg(fd, &m, flags | MSG_CMSG_CLOEXEC);
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        if (n != static_cast<ssize_t>(sizeof(f)) || f.magic != nest::kViewerMagic) return false;
        for (cmsghdr* c = CMSG_FIRSTHDR(&m); c; c = CMSG_NXTHDR(&m, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
            int pfd;
            memcpy(&pfd, CMSG_DATA(c), sizeof(int));
            auto old = pools.find(f.poolId);
            if (old != pools.end()) {
                munmap(old->second.first, old->second.second);
                pools.erase(old);
            }
            void* map = mmap(nullptr, f.poolSize, PROT_READ, MAP_SHARED, pfd, 0);
            close(pfd);
            if (map != MAP_FAILED) pools[f.poolId] = {static_cast<uint8_t*>(map), f.poolSize};
        }
        got = true;
        return true;
    }

    void present(const nest::ViewerFrame& f) {
        auto p = pools.find(f.poolId);
        if (p == pools.end()) return;
        if (f.width == 0 || f.height == 0 || f.stride < f.width * 4 ||
            f.offset + static_cast<uint64_t>(f.stride) * f.height > p->second.second)
            return;
        bool srcBgra = f.format == 0 || f.format == 1;  // wl_shm ARGB/XRGB: bytes B,G,R,A
        bool opaque = f.format == 1 || f.format == 0x34324258;
        if (f.width != curW || f.height != curH || windowFormat < 0) {
            int32_t want = srcBgra && !bgraFailed ? kWindowBgra : kWindowRgba;
            if (ANativeWindow_setBuffersGeometry(win, static_cast<int32_t>(f.width), static_cast<int32_t>(f.height), want) != 0 &&
                want == kWindowBgra) {
                bgraFailed = true;
                want = kWindowRgba;
                ANativeWindow_setBuffersGeometry(win, static_cast<int32_t>(f.width), static_cast<int32_t>(f.height), want);
            }
            windowFormat = want;
            curW = f.width;
            curH = f.height;
        }
        ANativeWindow_Buffer b;
        if (ANativeWindow_lock(win, &b, nullptr) != 0) {
            if (windowFormat == kWindowBgra) {  // the window cannot hold BGRA after all
                bgraFailed = true;
                windowFormat = -1;
            }
            return;
        }
        const uint8_t* src = p->second.first + f.offset;
        auto* dst = static_cast<uint8_t*>(b.bits);
        uint32_t w = std::min<uint32_t>(f.width, static_cast<uint32_t>(b.width));
        uint32_t h = std::min<uint32_t>(f.height, static_cast<uint32_t>(b.height));
        bool swap = srcBgra != (windowFormat == kWindowBgra);
        uint32_t alpha = opaque ? 0xff000000u : 0;
        for (uint32_t y = 0; y < h; y++) {
            const uint32_t* s = reinterpret_cast<const uint32_t*>(src + static_cast<size_t>(y) * f.stride);
            uint32_t* d = reinterpret_cast<uint32_t*>(dst + static_cast<size_t>(y) * b.stride * 4);
            if (!swap && !alpha) {
                memcpy(d, s, static_cast<size_t>(w) * 4);
            } else if (!swap) {
                for (uint32_t x = 0; x < w; x++) d[x] = s[x] | alpha;
            } else {
                for (uint32_t x = 0; x < w; x++) {
                    uint32_t v = s[x];
                    d[x] = (v & 0xff00ff00u) | ((v & 0xffu) << 16) | ((v >> 16) & 0xffu) | alpha;
                }
            }
        }
        ANativeWindow_unlockAndPost(win);
        frames++;
    }

    void run() {
        while (!stop) {
            if (fd < 0 && !connectEngine()) {
                usleep(300000);
                continue;
            }
            pollfd pf{fd, POLLIN, 0};
            int r = poll(&pf, 1, 300);
            if (r <= 0) continue;
            nest::ViewerFrame f{}, last{};
            bool any = false, got = false;
            if (!receive(f, got, 0)) {
                disconnect();
                continue;
            }
            if (got) {
                last = f;
                any = true;
            }
            // skip to the newest frame (pool fds of the skipped ones are still mapped)
            bool alive = true;
            for (;;) {
                if (!receive(f, got, MSG_DONTWAIT)) {
                    alive = false;
                    break;
                }
                if (!got) break;
                last = f;
                any = true;
            }
            if (any) present(last);
            if (!alive) disconnect();
        }
        disconnect();
    }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_dev_matryoshka_vm_core_nest_NestNativeView_start(JNIEnv* env, jclass, jobject surface,
                                                                                        jstring socketPath) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return 0;
    auto* v = new Viewer();
    v->win = win;
    const char* p = env->GetStringUTFChars(socketPath, nullptr);
    v->path = p;
    env->ReleaseStringUTFChars(socketPath, p);
    v->th = std::thread([v] { v->run(); });
    return reinterpret_cast<jlong>(v);
}

extern "C" JNIEXPORT void JNICALL Java_dev_matryoshka_vm_core_nest_NestNativeView_stop(JNIEnv*, jclass, jlong handle) {
    auto* v = reinterpret_cast<Viewer*>(handle);
    if (!v) return;
    v->stop = true;
    if (v->th.joinable()) v->th.join();
    ANativeWindow_release(v->win);
    delete v;
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_matryoshka_vm_core_nest_NestNativeView_frames(JNIEnv*, jclass, jlong handle) {
    auto* v = reinterpret_cast<Viewer*>(handle);
    return v ? static_cast<jlong>(v->frames.load()) : 0;
}
