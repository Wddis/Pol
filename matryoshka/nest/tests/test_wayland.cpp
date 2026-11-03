// The engine's Wayland server against a raw-protocol client doing what Waydroid's hwcomposer does:
// registry, wl_shm pool + buffer, xdg toplevel (configure gives the screen size), attach + commit,
// frame callback; then a touch event back to the client.
// SPDX-License-Identifier: Apache-2.0
#include <cstring>
#include <fcntl.h>
#include <map>
#include <poll.h>
#include <string>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

#include "test.h"
#include "wayland_server.h"

namespace {

struct Wire {
    int fd = -1;
    std::vector<uint8_t> in;

    void send(uint32_t id, uint16_t op, const std::vector<uint32_t>& args, int passFd = -1, const char* str = nullptr,
              size_t strAt = 0) {
        std::vector<uint32_t> words;
        for (size_t k = 0; k < args.size(); k++) {
            if (str && k == strAt) {
                uint32_t n = static_cast<uint32_t>(strlen(str) + 1);
                words.push_back(n);
                std::vector<uint32_t> s((n + 3) / 4, 0);
                memcpy(s.data(), str, n);
                words.insert(words.end(), s.begin(), s.end());
            }
            words.push_back(args[k]);
        }
        std::vector<uint32_t> msg{id, static_cast<uint32_t>(((8 + words.size() * 4) << 16) | op)};
        msg.insert(msg.end(), words.begin(), words.end());
        iovec iov{msg.data(), msg.size() * 4};
        msghdr m{};
        m.msg_iov = &iov;
        m.msg_iovlen = 1;
        char cbuf[CMSG_SPACE(sizeof(int))];
        if (passFd >= 0) {
            m.msg_control = cbuf;
            m.msg_controllen = sizeof(cbuf);
            cmsghdr* c = CMSG_FIRSTHDR(&m);
            c->cmsg_level = SOL_SOCKET;
            c->cmsg_type = SCM_RIGHTS;
            c->cmsg_len = CMSG_LEN(sizeof(int));
            memcpy(CMSG_DATA(c), &passFd, sizeof(int));
        }
        sendmsg(fd, &m, 0);
    }

    // Collects events until `want(id, op, body)` returns true (or timeout).
    template <typename F>
    bool until(F want, int ms = 3000) {
        for (int waited = 0; waited < ms; waited += 50) {
            size_t off = 0;
            while (in.size() - off >= 8) {
                uint32_t id, h;
                memcpy(&id, in.data() + off, 4);
                memcpy(&h, in.data() + off + 4, 4);
                uint32_t size = h >> 16;
                if (in.size() - off < size) break;
                std::vector<uint8_t> body(in.begin() + off + 8, in.begin() + off + size);
                off += size;
                if (want(id, static_cast<uint16_t>(h & 0xffff), body)) {
                    in.erase(in.begin(), in.begin() + off);
                    return true;
                }
            }
            in.erase(in.begin(), in.begin() + off);
            pollfd p{fd, POLLIN, 0};
            if (poll(&p, 1, 50) > 0) {
                uint8_t b[4096];
                ssize_t n = recv(fd, b, sizeof(b), 0);
                if (n <= 0) return false;
                in.insert(in.end(), b, b + n);
            }
        }
        return false;
    }
};

uint32_t word(const std::vector<uint8_t>& b, size_t i) {
    uint32_t v = 0;
    if (b.size() >= (i + 1) * 4) memcpy(&v, b.data() + i * 4, 4);
    return v;
}

}  // namespace

TEST(wayland_server_presents_shm_frames_and_sends_touch) {
    char dir[] = "/tmp/nest-wl-XXXXXX";
    EXPECT_TRUE(mkdtemp(dir) != nullptr);
    std::string sock = std::string(dir) + "/wayland-0", fbPath = std::string(dir) + "/display.fb";
    nest::DisplayConfig cfg;
    cfg.width = 64;
    cfg.height = 32;
    nest::WaylandServer server(sock, fbPath, cfg);
    EXPECT_TRUE(server.start());

    Wire w;
    w.fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    sockaddr_un sa{};
    sa.sun_family = AF_UNIX;
    memcpy(sa.sun_path, sock.c_str(), sock.size() + 1);
    EXPECT_EQ(connect(w.fd, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)), 0);

    // registry: collect global names
    w.send(1, 1, {2});
    std::map<std::string, uint32_t> globals;
    w.until([&](uint32_t id, uint16_t op, const std::vector<uint8_t>& b) {
        if (id == 2 && op == 0) {
            uint32_t len = word(b, 1);
            globals[std::string(reinterpret_cast<const char*>(b.data() + 8), len ? len - 1 : 0)] = word(b, 0);
        }
        return globals.size() >= 6;
    });
    EXPECT_TRUE(globals.count("wl_compositor") && globals.count("wl_shm") && globals.count("xdg_wm_base") &&
                globals.count("wl_seat"));
    // bind: compositor=3, shm=4, wm_base=5, seat=6 (string is the 2nd argument)
    w.send(2, 0, {globals["wl_compositor"], 4, 3}, -1, "wl_compositor", 1);
    w.send(2, 0, {globals["wl_shm"], 1, 4}, -1, "wl_shm", 1);
    w.send(2, 0, {globals["xdg_wm_base"], 2, 5}, -1, "xdg_wm_base", 1);
    w.send(2, 0, {globals["wl_seat"], 5, 6}, -1, "wl_seat", 1);
    w.send(6, 2, {7});  // seat.get_touch -> 7

    // surface 8, xdg_surface 9, toplevel 10: configure must say 64x32
    w.send(3, 0, {8});
    w.send(5, 2, {9, 8});
    w.send(9, 1, {10});
    int32_t cw = 0, ch = 0;
    uint32_t cserial = 0;
    EXPECT_TRUE(w.until([&](uint32_t id, uint16_t op, const std::vector<uint8_t>& b) {
        if (id == 10 && op == 0) { cw = static_cast<int32_t>(word(b, 0)); ch = static_cast<int32_t>(word(b, 1)); }
        if (id == 9 && op == 0) { cserial = word(b, 0); return true; }
        return false;
    }));
    EXPECT_EQ(cw, 64);
    EXPECT_EQ(ch, 32);
    w.send(9, 4, {cserial});

    // shm pool from a memfd, ABGR8888 buffer filled with a pattern
    int mfd = static_cast<int>(syscall(SYS_memfd_create, "wl-test", 0));
    size_t size = 64 * 32 * 4;
    EXPECT_EQ(ftruncate(mfd, static_cast<off_t>(size)), 0);
    auto* px = static_cast<uint32_t*>(mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, mfd, 0));
    for (int i = 0; i < 64 * 32; i++) px[i] = 0xff000000u | static_cast<uint32_t>(i);
    w.send(4, 0, {11, static_cast<uint32_t>(size)}, mfd);
    w.send(11, 0, {12, 0, 64, 32, 64 * 4, 0x34324241});
    w.send(8, 3, {13});        // frame callback 13
    w.send(8, 1, {12, 0, 0});  // attach
    w.send(8, 6, {});          // commit
    bool released = false, done = false;
    EXPECT_TRUE(w.until([&](uint32_t id, uint16_t op, const std::vector<uint8_t>&) {
        if (id == 12 && op == 0) released = true;
        if (id == 13 && op == 0) done = true;
        return released && done;
    }));
    EXPECT_EQ(server.frames(), 1u);

    int fbfd = open(fbPath.c_str(), O_RDONLY | O_CLOEXEC);
    void* fbm = mmap(nullptr, sizeof(nest::FbHeader) + size, PROT_READ, MAP_SHARED, fbfd, 0);
    const auto* hdr = static_cast<const nest::FbHeader*>(fbm);
    const auto* fpx = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(fbm) + sizeof(nest::FbHeader));
    EXPECT_EQ(hdr->magic, nest::kFbMagic);
    EXPECT_EQ(hdr->seq.load(), 1u);
    EXPECT_EQ(fpx[0], 0xff000000u);
    EXPECT_EQ(fpx[64 * 31 + 63], 0xff000000u | (64u * 31 + 63));

    // touch from the app reaches the client's wl_touch
    server.sendTouch(0, 0, 10.5f, 20.0f);
    int32_t tx = 0;
    EXPECT_TRUE(w.until([&](uint32_t id, uint16_t op, const std::vector<uint8_t>& b) {
        if (id == 7 && op == 0) { tx = static_cast<int32_t>(word(b, 4)); return true; }
        return false;
    }));
    EXPECT_EQ(tx, static_cast<int32_t>(10.5f * 256));

    munmap(fbm, sizeof(nest::FbHeader) + size);
    close(fbfd);
    munmap(px, size);
    close(mfd);
    close(w.fd);
    server.stop();
}

TEST(wayland_server_hands_frames_to_a_direct_viewer) {
    char dir[] = "/tmp/nest-wlv-XXXXXX";
    EXPECT_TRUE(mkdtemp(dir) != nullptr);
    std::string sock = std::string(dir) + "/wayland-0", fbPath = std::string(dir) + "/display.fb";
    nest::DisplayConfig cfg;
    cfg.width = 16;
    cfg.height = 8;
    nest::WaylandServer server(sock, fbPath, cfg);
    EXPECT_TRUE(server.start());
    // the viewer connects first
    int v = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    sockaddr_un vaddr{};
    vaddr.sun_family = AF_UNIX;
    std::string vpath = std::string(dir) + "/display.sock";
    memcpy(vaddr.sun_path, vpath.c_str(), vpath.size() + 1);
    EXPECT_EQ(connect(v, reinterpret_cast<sockaddr*>(&vaddr), sizeof(vaddr)), 0);
    usleep(100000);

    Wire w;
    w.fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    sockaddr_un sa{};
    sa.sun_family = AF_UNIX;
    memcpy(sa.sun_path, sock.c_str(), sock.size() + 1);
    EXPECT_EQ(connect(w.fd, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)), 0);
    w.send(1, 1, {2});
    std::map<std::string, uint32_t> globals;
    w.until([&](uint32_t id, uint16_t op, const std::vector<uint8_t>& b) {
        if (id == 2 && op == 0) {
            uint32_t len = word(b, 1);
            globals[std::string(reinterpret_cast<const char*>(b.data() + 8), len ? len - 1 : 0)] = word(b, 0);
        }
        return globals.size() >= 6;
    });
    w.send(2, 0, {globals["wl_compositor"], 4, 3}, -1, "wl_compositor", 1);
    w.send(2, 0, {globals["wl_shm"], 1, 4}, -1, "wl_shm", 1);
    w.send(3, 0, {8});  // surface (full-size buffers are the screen)
    int mfd = static_cast<int>(syscall(SYS_memfd_create, "wl-v", 0));
    size_t size = 16 * 8 * 4;
    EXPECT_EQ(ftruncate(mfd, static_cast<off_t>(size)), 0);
    auto* px = static_cast<uint32_t*>(mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, mfd, 0));
    for (int i = 0; i < 16 * 8; i++) px[i] = 0x11223344u + static_cast<uint32_t>(i);
    w.send(4, 0, {11, static_cast<uint32_t>(size)}, mfd);
    w.send(11, 0, {12, 0, 16, 8, 16 * 4, 0});
    w.send(8, 1, {12, 0, 0});
    w.send(8, 6, {});
    // the viewer gets the frame and the pool fd, and sees the client's pixels through it
    nest::ViewerFrame f{};
    char cbuf[CMSG_SPACE(sizeof(int))];
    iovec iov{&f, sizeof(f)};
    msghdr m{};
    m.msg_iov = &iov;
    m.msg_iovlen = 1;
    m.msg_control = cbuf;
    m.msg_controllen = sizeof(cbuf);
    pollfd p{v, POLLIN, 0};
    EXPECT_TRUE(poll(&p, 1, 3000) == 1);
    EXPECT_EQ(recvmsg(v, &m, 0), static_cast<ssize_t>(sizeof(f)));
    EXPECT_EQ(f.magic, nest::kViewerMagic);
    EXPECT_EQ(f.width, 16u);
    int pfd = -1;
    for (cmsghdr* c = CMSG_FIRSTHDR(&m); c; c = CMSG_NXTHDR(&m, c)) memcpy(&pfd, CMSG_DATA(c), sizeof(int));
    EXPECT_TRUE(pfd >= 0);
    auto* vm = static_cast<const uint32_t*>(mmap(nullptr, f.poolSize, PROT_READ, MAP_SHARED, pfd, 0));
    EXPECT_EQ(vm[f.offset / 4 + 5], 0x11223344u + 5);
    munmap(const_cast<uint32_t*>(vm), f.poolSize);
    close(pfd);
    close(v);
    munmap(px, size);
    close(mfd);
    close(w.fd);
    server.stop();
}
