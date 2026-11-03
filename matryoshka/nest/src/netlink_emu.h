// Userspace netlink peer for the guest (virtual network, step 2).
//
// An untrusted app may not use most netlink families, and a VM has no real kernel networking
// anyway. Guest daemons (netd, wificond) still talk netlink: they send requests and wait for the
// kernel's acknowledgement. The preload gives them a socketpair instead of a netlink socket and
// answers every request here, the way a kernel with no interfaces and no rules would accept it:
// dumps end immediately (NLMSG_DONE), other requests are acknowledged (NLMSG_ERROR, error 0).
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace nest {

// Replies for one buffer of netlink messages sent on a socket of protocol `proto`.
std::vector<uint8_t> netlinkReplies(int proto, const uint8_t* buf, size_t len);

}  // namespace nest
