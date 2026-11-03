// SPDX-License-Identifier: Apache-2.0
#include "wayland_server.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <deque>
#include <fcntl.h>
#include <map>
#include <memory>
#include <mutex>
#include <poll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace nest {

namespace {

enum class T {
    Display, Registry, Callback, Compositor, Surface, Region, Shm, ShmPool, Buffer, Subcompositor,
    Subsurface, Seat, Pointer, Keyboard, Touch, Output, XdgWmBase, XdgPositioner, XdgSurface, XdgToplevel,
};

struct Pool {
    int fd = -1;
    void* map = MAP_FAILED;
    size_t size = 0;
    ~Pool() {
        if (map != MAP_FAILED) munmap(map, size);
        if (fd >= 0) close(fd);
    }
    void remap(size_t n) {
        if (map != MAP_FAILED) munmap(map, size);
        size = n;
        map = n ? mmap(nullptr, n, PROT_READ, MAP_SHARED, fd, 0) : MAP_FAILED;
    }
};

struct Obj {
    T type;
    uint32_t version = 1;
    // buffer
    std::shared_ptr<Pool> pool;
    int32_t offset = 0, width = 0, height = 0, stride = 0;
    uint32_t format = 0;
    // surface
    uint32_t pendingBuffer = 0;
    bool attachPending = false;
    uint32_t buffer = 0;
    std::vector<uint32_t> pendingFrames;
    bool toplevel = false;
    // xdg_surface / toplevel -> surface id
    uint32_t surface = 0;
};

struct Global {
    const char* iface;
    uint32_t version;
    T type;
};
const Global kGlobals[] = {
    {"wl_compositor", 4, T::Compositor}, {"wl_subcompositor", 1, T::Subcompositor}, {"wl_shm", 1, T::Shm},
    {"xdg_wm_base", 2, T::XdgWmBase},   {"wl_seat", 5, T::Seat},                   {"wl_output", 3, T::Output},
};

// wl_shm formats: ARGB8888, XRGB8888 (enum 0/1), ABGR8888, XBGR8888, RGB565 (fourcc).
const uint32_t kFormats[] = {0, 1, 0x34324241, 0x34324258, 0x36314752};

struct Client {
    int fd = -1;
    std::map<uint32_t, Obj> objs;
    std::deque<int> fds;
    uint32_t screenSurface = 0;  // surface whose buffer last became the screen
    std::vector<uint8_t> in;
    ~Client() {
        for (int f : fds) close(f);
        if (fd >= 0) close(fd);
    }
};

class Msg {
public:
    Msg(uint32_t id, uint16_t op) : id_(id), op_(op) {}
    Msg& u(uint32_t v) {
        put(&v, 4);
        return *this;
    }
    Msg& i(int32_t v) { return u(static_cast<uint32_t>(v)); }
    Msg& s(const char* str) {
        uint32_t n = static_cast<uint32_t>(strlen(str) + 1);
        u(n);
        put(str, n);
        pad();
        return *this;
    }
    Msg& arr(const std::vector<uint32_t>& a) {
        u(static_cast<uint32_t>(a.size() * 4));
        for (uint32_t v : a) u(v);
        return *this;
    }
    std::vector<uint8_t> bytes() const {
        std::vector<uint8_t> out(8 + body_.size());
        uint32_t h1 = static_cast<uint32_t>(((8 + body_.size()) << 16) | op_);
        memcpy(out.data(), &id_, 4);
        memcpy(out.data() + 4, &h1, 4);
        if (!body_.empty()) memcpy(out.data() + 8, body_.data(), body_.size());
        return out;
    }

private:
    void put(const void* p, size_t n) {
        const auto* b = static_cast<const uint8_t*>(p);
        body_.insert(body_.end(), b, b + n);
    }
    void pad() {
        while (body_.size() % 4) body_.push_back(0);
    }
    uint32_t id_;
    uint16_t op_;
    std::vector<uint8_t> body_;
};

uint32_t nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint32_t>(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

// Argument reader for one request body.
struct Args {
    const uint8_t* p;
    size_t n, off = 0;
    bool ok = true;
    uint32_t u() {
        if (off + 4 > n) { ok = false; return 0; }
        uint32_t v;
        memcpy(&v, p + off, 4);
        off += 4;
        return v;
    }
    int32_t i() { return static_cast<int32_t>(u()); }
    std::string s() {
        uint32_t len = u();
        if (!ok || off + len > n) { ok = false; return {}; }
        std::string out(reinterpret_cast<const char*>(p + off), len ? len - 1 : 0);
        off += (len + 3) & ~3u;
        return out;
    }
};

}  // namespace

struct WaylandServer::Impl {
    std::string socketPath, fbPath;
    DisplayConfig cfg;
    int listenFd = -1, wake[2] = {-1, -1};
    std::thread th;
    std::atomic<bool> running{false};
    std::vector<std::unique_ptr<Client>> clients;
    uint8_t* fb = nullptr;
    size_t fbSize = 0;
    std::atomic<uint64_t> frameCount{0};
    uint32_t serial = 1;
    std::mutex touchMu;
    struct TouchEv { int action, id; float x, y; };
    std::deque<TouchEv> touches;

    void send(Client& c, const Msg& m) {
        auto b = m.bytes();
        ssize_t w = ::send(c.fd, b.data(), b.size(), MSG_NOSIGNAL | MSG_DONTWAIT);
        (void)w;
    }
    void deleteId(Client& c, uint32_t id) { send(c, Msg(1, 1).u(id)); }

    bool openFb() {
        int fd = open(fbPath.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
        if (fd < 0) return false;
        fbSize = sizeof(FbHeader) + static_cast<size_t>(cfg.width) * cfg.height * 4;
        if (ftruncate(fd, static_cast<off_t>(fbSize)) != 0) { close(fd); return false; }
        void* m = mmap(nullptr, fbSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        close(fd);
        if (m == MAP_FAILED) return false;
        fb = static_cast<uint8_t*>(m);
        auto* h = reinterpret_cast<FbHeader*>(fb);
        h->width = cfg.width;
        h->height = cfg.height;
        h->stride = cfg.width * 4;
        h->format = 0x34324241;
        h->magic = kFbMagic;
        return true;
    }

    void present(const Obj& buf) {
        if (!fb || !buf.pool || buf.pool->map == MAP_FAILED) return;
        auto* h = reinterpret_cast<FbHeader*>(fb);
        int w = std::min(buf.width, cfg.width), hgt = std::min(buf.height, cfg.height);
        size_t need = static_cast<size_t>(buf.offset) + static_cast<size_t>(buf.stride) * buf.height;
        if (w <= 0 || hgt <= 0 || need > buf.pool->size) return;
        const uint8_t* src = static_cast<const uint8_t*>(buf.pool->map) + buf.offset;
        uint8_t* dst = fb + sizeof(FbHeader);
        bool rgb565 = buf.format == 0x36314752;
        for (int y = 0; y < hgt; y++) {
            if (rgb565) {  // expand to ABGR8888
                const uint16_t* s = reinterpret_cast<const uint16_t*>(src + static_cast<size_t>(y) * buf.stride);
                uint8_t* d = dst + static_cast<size_t>(y) * h->stride;
                for (int x = 0; x < w; x++) {
                    uint16_t px = s[x];
                    d[x * 4 + 0] = static_cast<uint8_t>(((px >> 11) & 31) * 255 / 31);
                    d[x * 4 + 1] = static_cast<uint8_t>(((px >> 5) & 63) * 255 / 63);
                    d[x * 4 + 2] = static_cast<uint8_t>((px & 31) * 255 / 31);
                    d[x * 4 + 3] = 255;
                }
            } else {
                memcpy(dst + static_cast<size_t>(y) * h->stride, src + static_cast<size_t>(y) * buf.stride,
                       static_cast<size_t>(w) * 4);
            }
        }
        h->format = rgb565 ? 0x34324241 : buf.format;
        h->seq.fetch_add(1, std::memory_order_release);
        frameCount++;
    }

    void bindGlobal(Client& c, uint32_t name, uint32_t version, uint32_t id) {
        if (name < 1 || name > sizeof(kGlobals) / sizeof(kGlobals[0])) return;
        const Global& g = kGlobals[name - 1];
        Obj o{g.type};
        o.version = std::min(version, g.version);
        c.objs[id] = o;
        switch (g.type) {
            case T::Shm:
                for (uint32_t f : kFormats) send(c, Msg(id, 0).u(f));
                break;
            case T::Seat:
                send(c, Msg(id, 0).u(1 | 4));  // pointer | touch
                if (o.version >= 2) send(c, Msg(id, 1).s("seat0"));
                break;
            case T::Output: {
                int mmw = cfg.width * 254 / (cfg.dpi * 10), mmh = cfg.height * 254 / (cfg.dpi * 10);
                send(c, Msg(id, 0).i(0).i(0).i(mmw).i(mmh).i(0).s("Matryoshka").s("Nest display").i(0));
                send(c, Msg(id, 1).u(3).i(cfg.width).i(cfg.height).i(cfg.refreshHz * 1000));
                if (o.version >= 2) {
                    send(c, Msg(id, 3).i(1));
                    send(c, Msg(id, 2));
                }
                break;
            }
            default:
                break;
        }
    }

    void configureToplevel(Client& c, uint32_t xdgSurface, uint32_t toplevel) {
        send(c, Msg(toplevel, 0).i(cfg.width).i(cfg.height).arr({1, 4}));  // maximized, activated
        send(c, Msg(xdgSurface, 0).u(serial++));
    }

    void commit(Client& c, uint32_t sid) {
        auto it = c.objs.find(sid);
        if (it == c.objs.end()) return;
        Obj& s = it->second;
        if (s.attachPending) {
            s.buffer = s.pendingBuffer;
            s.attachPending = false;
            auto b = c.objs.find(s.buffer);
            if (b != c.objs.end() && b->second.type == T::Buffer) {
                bool screen = s.toplevel || (b->second.width >= cfg.width && b->second.height >= cfg.height);
                if (screen) {
                    present(b->second);
                    c.screenSurface = sid;
                }
                send(c, Msg(s.buffer, 0));  // wl_buffer.release: the copy is done
            }
        }
        uint32_t t = nowMs();
        for (uint32_t cb : s.pendingFrames) {
            send(c, Msg(cb, 0).u(t));
            deleteId(c, cb);
            c.objs.erase(cb);
        }
        s.pendingFrames.clear();
    }

    void request(Client& c, uint32_t id, uint16_t op, Args& a) {
        auto it = c.objs.find(id);
        if (it == c.objs.end()) return;
        T type = it->second.type;
        switch (type) {
            case T::Display:
                if (op == 0) {  // sync
                    uint32_t cb = a.u();
                    send(c, Msg(cb, 0).u(serial++));
                    deleteId(c, cb);
                } else if (op == 1) {  // get_registry
                    uint32_t reg = a.u();
                    c.objs[reg] = Obj{T::Registry};
                    for (uint32_t n = 0; n < sizeof(kGlobals) / sizeof(kGlobals[0]); n++)
                        send(c, Msg(reg, 0).u(n + 1).s(kGlobals[n].iface).u(kGlobals[n].version));
                }
                break;
            case T::Registry:
                if (op == 0) {
                    uint32_t name = a.u();
                    std::string iface = a.s();
                    uint32_t ver = a.u(), nid = a.u();
                    if (a.ok) bindGlobal(c, name, ver, nid);
                }
                break;
            case T::Compositor:
                if (op == 0) c.objs[a.u()] = Obj{T::Surface};
                else if (op == 1) c.objs[a.u()] = Obj{T::Region};
                break;
            case T::Shm:
                if (op == 0) {
                    uint32_t pid = a.u();
                    int32_t size = a.i();
                    if (c.fds.empty()) break;
                    auto p = std::make_shared<Pool>();
                    p->fd = c.fds.front();
                    c.fds.pop_front();
                    p->remap(static_cast<size_t>(std::max(size, 0)));
                    Obj o{T::ShmPool};
                    o.pool = p;
                    c.objs[pid] = o;
                }
                break;
            case T::ShmPool:
                if (op == 0) {
                    Obj b{T::Buffer};
                    uint32_t bid = a.u();
                    b.offset = a.i();
                    b.width = a.i();
                    b.height = a.i();
                    b.stride = a.i();
                    b.format = a.u();
                    b.pool = it->second.pool;
                    c.objs[bid] = b;
                } else if (op == 1) {
                    c.objs.erase(id);
                    deleteId(c, id);
                } else if (op == 2) {
                    int32_t size = a.i();
                    if (it->second.pool) it->second.pool->remap(static_cast<size_t>(std::max(size, 0)));
                }
                break;
            case T::Surface:
                if (op == 0) {
                    c.objs.erase(id);
                    deleteId(c, id);
                } else if (op == 1) {
                    it->second.pendingBuffer = a.u();
                    it->second.attachPending = true;
                } else if (op == 3) {
                    uint32_t cb = a.u();
                    c.objs[cb] = Obj{T::Callback};
                    c.objs[id].pendingFrames.push_back(cb);
                } else if (op == 6) {
                    commit(c, id);
                }
                break;
            case T::Subcompositor:
                if (op == 1) c.objs[a.u()] = Obj{T::Subsurface};
                else if (op == 0) { c.objs.erase(id); deleteId(c, id); }
                break;
            case T::XdgWmBase:
                if (op == 1) c.objs[a.u()] = Obj{T::XdgPositioner};
                else if (op == 2) {
                    uint32_t xs = a.u(), sid = a.u();
                    Obj o{T::XdgSurface};
                    o.surface = sid;
                    c.objs[xs] = o;
                }
                break;
            case T::XdgSurface:
                if (op == 1) {
                    uint32_t tl = a.u();
                    Obj o{T::XdgToplevel};
                    o.surface = it->second.surface;
                    c.objs[tl] = o;
                    auto s = c.objs.find(o.surface);
                    if (s != c.objs.end()) s->second.toplevel = true;
                    configureToplevel(c, id, tl);
                } else if (op == 0) {
                    c.objs.erase(id);
                    deleteId(c, id);
                }
                break;
            case T::Seat:
                if (op == 0) c.objs[a.u()] = Obj{T::Pointer};
                else if (op == 1) c.objs[a.u()] = Obj{T::Keyboard};
                else if (op == 2) c.objs[a.u()] = Obj{T::Touch};
                break;
            case T::Buffer:
            case T::Region:
            case T::Subsurface:
            case T::XdgPositioner:
            case T::XdgToplevel:
            case T::Pointer:
            case T::Keyboard:
            case T::Touch:
            case T::Output:
                // destroy/release is opcode 0 for all of these (wl_pointer: 1, kept as is)
                if (op == 0 && type != T::Pointer && type != T::Output) {
                    c.objs.erase(id);
                    deleteId(c, id);
                }
                break;
            default:
                break;
        }
    }

    bool readClient(Client& c) {
        uint8_t buf[16384];
        char cbuf[CMSG_SPACE(sizeof(int) * 28)];
        iovec iov{buf, sizeof(buf)};
        msghdr m{};
        m.msg_iov = &iov;
        m.msg_iovlen = 1;
        m.msg_control = cbuf;
        m.msg_controllen = sizeof(cbuf);
        ssize_t n = recvmsg(c.fd, &m, MSG_CMSG_CLOEXEC);
        if (n <= 0) return false;
        for (cmsghdr* h = CMSG_FIRSTHDR(&m); h; h = CMSG_NXTHDR(&m, h)) {
            if (h->cmsg_level == SOL_SOCKET && h->cmsg_type == SCM_RIGHTS) {
                size_t k = (h->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                const int* f = reinterpret_cast<const int*>(CMSG_DATA(h));
                for (size_t i = 0; i < k; i++) c.fds.push_back(f[i]);
            }
        }
        c.in.insert(c.in.end(), buf, buf + n);
        size_t off = 0;
        while (c.in.size() - off >= 8) {
            uint32_t id, h1;
            memcpy(&id, c.in.data() + off, 4);
            memcpy(&h1, c.in.data() + off + 4, 4);
            uint32_t size = h1 >> 16;
            if (size < 8) return false;
            if (c.in.size() - off < size) break;
            Args a{c.in.data() + off + 8, size - 8};
            request(c, id, static_cast<uint16_t>(h1 & 0xffff), a);
            off += size;
        }
        c.in.erase(c.in.begin(), c.in.begin() + static_cast<long>(off));
        return true;
    }

    void flushTouches() {
        std::deque<TouchEv> evs;
        {
            std::lock_guard<std::mutex> g(touchMu);
            evs.swap(touches);
        }
        for (auto& e : evs) {
            for (auto& cp : clients) {
                Client& c = *cp;
                // Focus: the toplevel window, else the surface that presented the screen, else any
                // surface. wl_touch.down's surface is non-nullable: without one the event is dropped
                // (a null object makes libwayland disconnect the client, and the composer HAL aborts).
                uint32_t surf = 0, any = 0;
                for (auto& kv : c.objs) {
                    if (kv.second.type != T::Surface) continue;
                    if (kv.second.toplevel) surf = kv.first;
                    any = kv.first;
                }
                if (!surf && c.objs.count(c.screenSurface) && c.objs[c.screenSurface].type == T::Surface) surf = c.screenSurface;
                if (!surf) surf = any;
                if (!surf) continue;
                for (auto& kv : c.objs) {
                    if (kv.second.type != T::Touch) continue;
                    int32_t fx = static_cast<int32_t>(e.x * 256), fy = static_cast<int32_t>(e.y * 256);
                    if (e.action == 0) send(c, Msg(kv.first, 0).u(serial++).u(nowMs()).u(surf).i(e.id).i(fx).i(fy));
                    else if (e.action == 1) send(c, Msg(kv.first, 1).u(serial++).u(nowMs()).i(e.id));
                    else send(c, Msg(kv.first, 2).u(nowMs()).i(e.id).i(fx).i(fy));
                    send(c, Msg(kv.first, 3));  // frame
                }
            }
        }
    }

    void loop() {
        while (running) {
            std::vector<pollfd> pf;
            pf.push_back({listenFd, POLLIN, 0});
            pf.push_back({wake[0], POLLIN, 0});
            for (auto& c : clients) pf.push_back({c->fd, POLLIN, 0});
            if (poll(pf.data(), pf.size(), -1) < 0) {
                if (errno == EINTR) continue;
                break;
            }
            if (pf[1].revents) {
                char b[64];
                ssize_t r = read(wake[0], b, sizeof(b));
                (void)r;
                if (!running) break;
                flushTouches();
            }
            if (pf[0].revents & POLLIN) {
                int fd = accept4(listenFd, nullptr, nullptr, SOCK_CLOEXEC);
                if (fd >= 0) {
                    auto c = std::make_unique<Client>();
                    c->fd = fd;
                    c->objs[1] = Obj{T::Display};
                    clients.push_back(std::move(c));
                }
            }
            for (size_t i = 2; i < pf.size(); i++) {
                if (!pf[i].revents) continue;
                Client* c = clients[i - 2].get();
                if (!(pf[i].revents & POLLIN) || !readClient(*c)) c->fd = -c->fd - 2;  // mark dead
            }
            clients.erase(std::remove_if(clients.begin(), clients.end(),
                                         [](const std::unique_ptr<Client>& c) {
                                             if (c->fd >= 0) return false;
                                             c->fd = -c->fd - 2;
                                             return true;
                                         }),
                          clients.end());
        }
    }
};

WaylandServer::WaylandServer(std::string socketPath, std::string fbPath, DisplayConfig cfg) : d_(new Impl) {
    d_->socketPath = std::move(socketPath);
    d_->fbPath = std::move(fbPath);
    d_->cfg = cfg;
}

WaylandServer::~WaylandServer() {
    stop();
    if (d_->fb) munmap(d_->fb, d_->fbSize);
}

bool WaylandServer::start() {
    if (!d_->openFb()) return false;
    sockaddr_un sa{};
    sa.sun_family = AF_UNIX;
    if (d_->socketPath.size() >= sizeof(sa.sun_path)) return false;
    memcpy(sa.sun_path, d_->socketPath.c_str(), d_->socketPath.size() + 1);
    unlink(d_->socketPath.c_str());
    d_->listenFd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (d_->listenFd < 0 || bind(d_->listenFd, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) != 0 ||
        listen(d_->listenFd, 8) != 0)
        return false;
    chmod(d_->socketPath.c_str(), 0777);
    if (pipe2(d_->wake, O_CLOEXEC) != 0) return false;
    d_->running = true;
    d_->th = std::thread([this] { d_->loop(); });
    return true;
}

void WaylandServer::stop() {
    if (!d_->running) return;
    d_->running = false;
    ssize_t w = write(d_->wake[1], "x", 1);
    (void)w;
    if (d_->th.joinable()) d_->th.join();
    d_->clients.clear();
    for (int f : {d_->listenFd, d_->wake[0], d_->wake[1]})
        if (f >= 0) close(f);
    d_->listenFd = d_->wake[0] = d_->wake[1] = -1;
}

void WaylandServer::sendTouch(int action, int id, float x, float y) {
    {
        std::lock_guard<std::mutex> g(d_->touchMu);
        d_->touches.push_back({action, id, x, y});
    }
    ssize_t w = write(d_->wake[1], "t", 1);
    (void)w;
}

uint64_t WaylandServer::frames() const { return d_->frameCount.load(); }
int WaylandServer::clients() const { return static_cast<int>(d_->clients.size()); }

}  // namespace nest
