// SPDX-License-Identifier: Apache-2.0
#include "vnetlink.h"

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <linux/netlink.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace nest::vnl {

namespace {

constexpr int kMaxFd = 65536;
std::atomic<int> g_peer[kMaxFd];    // guest fd -> emulation end (+1; 0 = not virtual)
std::atomic<int> g_proto[kMaxFd];   // netlink protocol of the socket

constexpr int kNetlinkGeneric = 16;
constexpr uint16_t kGenlIdCtrl = 0x10;  // GENL_ID_CTRL

int peerOf(int fd) { return fd >= 0 && fd < kMaxFd ? g_peer[fd].load(std::memory_order_acquire) - 1 : -1; }

void reply(int peer, const void* msg, size_t len) {
    syscall(SYS_sendto, peer, msg, len, MSG_DONTWAIT | MSG_NOSIGNAL, nullptr, 0);
}

void sendDone(int peer, const nlmsghdr& req) {
    struct {
        nlmsghdr h;
        int32_t status;
    } m{};
    m.h.nlmsg_len = sizeof(m);
    m.h.nlmsg_type = NLMSG_DONE;
    m.h.nlmsg_flags = NLM_F_MULTI;
    m.h.nlmsg_seq = req.nlmsg_seq;
    m.h.nlmsg_pid = req.nlmsg_pid;
    reply(peer, &m, sizeof(m));
}

void sendError(int peer, const nlmsghdr& req, int error) {
    struct {
        nlmsghdr h;
        nlmsgerr e;
    } m{};
    m.h.nlmsg_len = sizeof(m);
    m.h.nlmsg_type = NLMSG_ERROR;
    m.h.nlmsg_seq = req.nlmsg_seq;
    m.h.nlmsg_pid = req.nlmsg_pid;
    m.e.error = error;
    m.