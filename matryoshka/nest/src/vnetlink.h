// Virtual netlink for the guest (part of libnest_preload.so).
//
// An untrusted app may not use most netlink families (route, xfrm, netfilter, nflog, sock_diag,
// kernel uevents), and the guest must not reconfigure the phone's real network anyway. A guest
// netlink socket is an in-process AF_UNIX datagram socketpair: the guest holds one end, the
// emulation the other. Requests are answered the way the kernel answers a host without those
// objects: acknowledgements (NLMSG_ERROR 0) for changes, NLMSG_DONE for dumps, ENOENT for
// generic-netlink family lookups. Multicast listeners simply never get events.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>

namespace nest::vnl {

// socket(): returns the guest fd, or -2 when the call is not a netlink socket.
int socketCall(int domain, int type, int protocol);
bool isVirtual(int fd);

// After the guest sent `data` on a virtual socket: queue the kernel's replies.
void onSend(int fd, const void* data, size_t len);
// recvmsg/recvfrom on a virtual socket: present the sender as the kernel (sockaddr_nl, pid 0).
void fixSource(int fd, sockaddr* addr, socklen_t* addrlen);
// getsockname(): sockaddr_nl with our pid.
int sockName(int fd, sockaddr* addr, socklen_t* addrlen);
void onClose(int fd);

}  // namespace nest::vnl
