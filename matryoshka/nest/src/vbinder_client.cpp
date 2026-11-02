// SPDX-License-Identifier: Apache-2.0
#include "vbinder_client.h"

#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <mutex>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

#include "fastpath.h"
#include "vbinder_proto.h"

namespace nest::vbinder {

namespace {

constexpr int kMaxTrackedFd = 65536;

struct BinderFd {
    int procSock = -1;
    Ctx ctx = Ctx::Binder;
    pid_t pid = 0;
    void* map = nullptr;
    size_t mapSize = 0;
};

std::mutex g_mu;
BinderFd* g_fds[kMaxTrackedFd];  // fd -> state (only binder fds are non-null)
std::atomic<int> g_count{0};

pid_t myPid() { return static_cast<pid_t>(syscall(SYS_getpid)); }
pid_t myTid() { return static_cast<pid_t>(syscall(SYS_gettid)); }

int connectBroker(int cloexec) {
    const char* path = getenv("NEST_BINDER_SOCK");
    if (!path || !*path) return -1;
    int s = static_cast<int>(syscall(SYS_socket, AF_UNIX, SOCK_SEQPACKET | (cloexec ? SOCK_CLOEXEC : 0), 0));
    if (s < 0) return -1;
    sockaddr_un sa{};
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, path, sizeof(sa.sun_path) - 1);
    // Tagged: the broker socket is a host path, it must not be translated by the tracer.
    if (taggedSyscall(SYS_connect, s, reinterpret_cast<long>(&sa), sizeof(sa)) != 0) {
        syscall(SYS_close, s);
        return -1;
    }
    return s;
}

bool sendHello(int s, ConnKind kind, Ctx ctx) {
    Hello h{};
    h.kind = kind;
    h.ctx = ctx;
    h.pid = myPid();
    h.tid = myTid();
    return send(s, &h, sizeof(h), MSG_NOSIGNAL) == sizeof(h);
}

bool sendFds(int s, const void* buf, size_t len, const std::vector<int>& fds) {
    iovec iov{const_cast<void*>(buf), len};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    std::vector<char> ctrl;
    if (!fds.empty()) {
        ctrl.resize(CMSG_SPACE(sizeof(int) * fds.size()));
        msg.msg_control = ctrl.data();
        msg.msg_controllen = ctrl.size();
        cmsghdr* c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * fds.size());
        memcpy(CMSG_DATA(c), fds.data(), sizeof(int) * fds.size());
    }
    ssize_t n;
    do n = sendmsg(s, &msg, MSG_NOSIGNAL);
    while (n < 0 && errno == EINTR);
    return n == static_cast<ssize_t>(len);
}

ssize_t recvFds(int s, void* buf, size_t len, std::vector<int>& fds) {
    char ctrl[CMSG_SPACE(sizeof(int) * kMaxFds)];
    iovec iov{buf, len};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl;
    msg.msg_controllen = sizeof(ctrl);
    ssize_t n;
    do n = recvmsg(s, &msg, MSG_CMSG_CLOEXEC);
    while (n < 0 && errno == EINTR);  // a blocked binder read survives signals, like the driver
    if (n <= 0) return n;
    for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        size_t k = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        int* p = reinterpret_cast<int*>(CMSG_DATA(c));
        for (size_t i = 0; i < k; i++) fds.push_back(p[i]);
    }
    return n;
}

// Per-thread broker connections, one per binder context; closed at thread exit so the broker
// forgets the thread (like BINDER_THREAD_EXIT).
struct ThreadConns {
    int sock[3] = {-1, -1, -1};
    pid_t pid = 0;
    ~ThreadConns() {
        for (int s : sock)
            if (s >= 0) syscall(SYS_close, s);
    }
};
thread_local ThreadConns t_conns;

int threadSock(Ctx ctx) {
    int i = static_cast<int>(ctx);
    if (t_conns.pid != myPid()) {  // forked: the inherited connections belong to the parent
        for (int& s : t_conns.sock) s = -1;
        t_conns.pid = myPid();
    }
    if (t_conns.sock[i] >= 0) return t_conns.sock[i];
    int s = connectBroker(1);
    if (s < 0) return -1;
    if (!sendHello(s, ConnKind::Thread, ctx)) {
        syscall(SYS_close, s);
        return -1;
    }
    t_conns.sock[i] = s;
    return s;
}

BinderFd* lookup(int fd) {
    if (fd < 0 || fd >= kMaxTrackedFd || g_count.load(std::memory_order_relaxed) == 0) return nullptr;
    return g_fds[fd];
}

// fds referenced by the transactions in a write buffer, in the order the broker consumes them.
void collectFds(const uint8_t* buf, size_t size, std::vector<int>& fds) {
    size_t pos = 0;
    while (pos + 4 <= size) {
        uint32_t cmd;
        memcpy(&cmd, buf + pos, 4);
        size_t argSize = _IOC_SIZE(cmd);
        if (pos + 4 + argSize > size) break;
        const uint8_t* arg = buf + pos + 4;
        pos += 4 + argSize;
        if (cmd != BC_TRANSACTION && cmd != BC_REPLY && cmd != BC_TRANSACTION_SG && cmd != BC_REPLY_SG) continue;
        binder_transaction_data tr;
        memcpy(&tr, arg, sizeof(tr));
        if (!tr.offsets_size || !tr.data.ptr.offsets || !tr.data.ptr.buffer) continue;
        const auto* data = reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer);
        const auto* offs = reinterpret_cast<const binder_size_t*>(tr.data.ptr.offsets);
        size_t n = tr.offsets_size / sizeof(binder_size_t);
        for (size_t i = 0; i < n; i++) {
            if (offs[i] + sizeof(binder_object_header) > tr.data_size) break;
            auto* hdr = reinterpret_cast<const binder_object_header*>(data + offs[i]);
            if (hdr->type == BINDER_TYPE_FD) {
                fds.push_back(static_cast<int>(reinterpret_cast<const binder_fd_object*>(hdr)->fd));
            } else if (hdr->type == BINDER_TYPE_FDA) {
                auto* fa = reinterpret_cast<const binder_fd_array_object*>(hdr);
                if (fa->parent >= i) continue;
                auto* parent = reinterpret_cast<const binder_buffer_object*>(data + offs[fa->parent]);
                const auto* arr = reinterpret_cast<const uint32_t*>(parent->buffer + fa->parent_offset);
                for (binder_size_t k = 0; k < fa->num_fds; k++) fds.push_back(static_cast<int>(arr[k]));
            }
        }
    }
}

int roundTrip(BinderFd* b, const IoctlReq& req, const void* payload, size_t payloadLen,
              const std::vector<int>& fds, std::vector<uint8_t>& readOut, IoctlResp& resp) {
    int s = threadSock(b->ctx);
    if (s < 0) return -EIO;
    std::vector<uint8_t> msg(sizeof(req) + payloadLen);
    memcpy(msg.data(), &req, sizeof(req));
    if (payloadLen) memcpy(msg.data() + sizeof(req), payload, payloadLen);
    if (!sendFds(s, msg.data(), msg.size(), fds)) return -EIO;

    std::vector<uint8_t> in(sizeof(IoctlResp) + kMaxReadSize + kMaxFds * 8);
    std::vector<int> got;
    ssize_t n = recvFds(s, in.data(), in.size(), got);
    if (n < static_cast<ssize_t>(sizeof(IoctlResp))) {
        for (int f : got) syscall(SYS_close, f);
        return -EIO;
    }
    memcpy(&resp, in.data(), sizeof(resp));
    if (resp.flags & kRespDrainWake) {  // no work left: the fd must stop polling readable
        uint8_t w[8];
        recv(b->procSock, w, sizeof(w), MSG_DONTWAIT);
    }
    readOut.assign(in.begin() + sizeof(resp), in.begin() + sizeof(resp) + resp.readBytes);
    // Store the numbers of the fds we just received where the transaction expects them.
    const uint8_t* fix = in.data() + sizeof(resp) + resp.readBytes;
    for (uint32_t i = 0; i < resp.nfixups && i < got.size(); i++) {
        uint64_t off;
        memcpy(&off, fix + i * 8, 8);
        if (b->map && off + 4 <= b->mapSize) {
            uint32_t v = static_cast<uint32_t>(got[i]);
            memcpy(static_cast<uint8_t*>(b->map) + off, &v, 4);
        }
    }
    return 0;
}

bool isBinderPath(const char* path, Ctx& ctx) {
    if (!path) return false;
    const char* p = path;
    if (strncmp(p, "/dev/binderfs/", 14) == 0) p += 13;  // "/binder" etc.
    else if (strncmp(p, "/dev/", 5) == 0) p += 4;
    else return false;
    if (strcmp(p, "/binder") == 0) ctx = Ctx::Binder;
    else if (strcmp(p, "/hwbinder") == 0) ctx = Ctx::HwBinder;
    else if (strcmp(p, "/vndbinder") == 0) ctx = Ctx::VndBinder;
    else return false;
    return true;
}

}  // namespace

int clientOpen(const char* path, int flags) {
    Ctx ctx;
    if (!isBinderPath(path, ctx)) return -2;
    const char* sock = getenv("NEST_BINDER_SOCK");
    if (!sock || !*sock) return -2;
    int s = connectBroker(flags & O_CLOEXEC);
    if (s < 0) {
        errno = ENOENT;
        return -1;
    }
    if (s >= kMaxTrackedFd || !sendHello(s, ConnKind::Process, ctx)) {
        syscall(SYS_close, s);
        errno = EMFILE;
        return -1;
    }
    auto* b = new BinderFd();
    b->procSock = s;
    b->ctx = ctx;
    b->pid = myPid();
    std::lock_guard<std::mutex> g(g_mu);
    g_fds[s] = b;
    g_count++;
    return s;
}

bool clientIsBinderFd(int fd) { return lookup(fd) != nullptr; }

void* clientMmap(void* addr, size_t len, int prot, int flags, int fd, off_t off) {
    BinderFd* b = lookup(fd);
    if (!b || b->map || len == 0) {
        errno = EINVAL;
        return MAP_FAILED;
    }
    int mfd = static_cast<int>(syscall(SYS_memfd_create, "vbinder", MFD_CLOEXEC));
    if (mfd < 0) return MAP_FAILED;
    if (ftruncate(mfd, static_cast<off_t>(len)) != 0) {
        syscall(SYS_close, mfd);
        return MAP_FAILED;
    }
    // Shared and writable for us (fd numbers are patched in place); the guest only reads it.
    int mflags = MAP_SHARED | (flags & MAP_FIXED);
#if defined(SYS_mmap2)
    void* m = reinterpret_cast<void*>(syscall(SYS_mmap2, addr, len, PROT_READ | PROT_WRITE, mflags, mfd, 0));
#else
    void* m = reinterpret_cast<void*>(syscall(SYS_mmap, addr, len, PROT_READ | PROT_WRITE, mflags, mfd, 0));
#endif
    if (m == MAP_FAILED) {
        syscall(SYS_close, mfd);
        errno = ENOMEM;
        return MAP_FAILED;
    }
    MmapReq req{};
    req.size = len;
    req.clientAddr = reinterpret_cast<uint64_t>(m);
    int32_t ack = -1;
    bool ok = sendFds(b->procSock, &req, sizeof(req), {mfd});
    ssize_t got = 0;
    while (ok && (got = recv(b->procSock, &ack, sizeof(ack), 0)) == 1) {
        // a poll wake ahead of the ack (work queued before the mapping existed): skip it
    }
    ok = ok && got == sizeof(ack) && ack == 0;
    syscall(SYS_close, mfd);
    if (!ok) {
        syscall(SYS_munmap, m, len);
        errno = ENOMEM;
        return MAP_FAILED;
    }
    b->map = m;
    b->mapSize = len;
    (void)prot;
    (void)off;
    return m;
}

int clientIoctl(int fd, unsigned long request, void* arg) {
    BinderFd* b = lookup(fd);
    if (!b) {
        errno = EBADF;
        return -1;
    }
    uint32_t cmd = static_cast<uint32_t>(request);
    IoctlReq req{};
    req.cmd = cmd;
    std::vector<uint8_t> read;
    IoctlResp resp{};
    int rc;
    if (cmd == static_cast<uint32_t>(BINDER_WRITE_READ)) {
        auto* bwr = static_cast<binder_write_read*>(arg);
        const uint8_t* wbuf = reinterpret_cast<const uint8_t*>(bwr->write_buffer) + bwr->write_consumed;
        size_t wlen = bwr->write_size > bwr->write_consumed ? bwr->write_size - bwr->write_consumed : 0;
        if (wlen > kMaxWriteSize) {
            errno = EINVAL;
            return -1;
        }
        std::vector<int> fds;
        if (wlen) collectFds(wbuf, wlen, fds);
        req.writeSize = wlen;
        req.readSize = bwr->read_size > bwr->read_consumed ? bwr->read_size - bwr->read_consumed : 0;
        req.nfds = static_cast<uint32_t>(fds.size());
        rc = roundTrip(b, req, wbuf, wlen, fds, read, resp);
        if (rc == 0) {
            bwr->write_consumed += resp.writeConsumed;
            if (!read.empty()) {
                memcpy(reinterpret_cast<uint8_t*>(bwr->read_buffer) + bwr->read_consumed, read.data(), read.size());
                bwr->read_consumed += read.size();
            }
        }
    } else {
        size_t argSize = _IOC_SIZE(cmd);
        // Some ioctls (e.g. BINDER_SET_CONTEXT_MGR) are called with a null/ignored argument.
        bool in = (_IOC_DIR(cmd) & _IOC_WRITE) && arg;
        bool out = _IOC_DIR(cmd) & _IOC_READ;
        req.argSize = in ? argSize : 0;
        rc = roundTrip(b, req, in ? arg : nullptr, in ? argSize : 0, {}, read, resp);
        if (rc == 0 && out && arg && !read.empty()) memcpy(arg, read.data(), std::min(read.size(), argSize));
    }
    if (rc < 0) {
        errno = -rc;
        return -1;
    }
    if (resp.ret < 0) {
        errno = -resp.ret;
        return -1;
    }
    return 0;
}

void clientClose(int fd) {
    BinderFd* b = lookup(fd);
    if (!b) return;
    {
        std::lock_guard<std::mutex> g(g_mu);
        g_fds[fd] = nullptr;
        g_count--;
    }
    // The fd itself (the process connection) is closed by the caller's close(); unmapping the
    // region is the guest's business (munmap), exactly like with the kernel driver.
    delete b;
}

}  // namespace nest::vbinder
