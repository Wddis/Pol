// SPDX-License-Identifier: Apache-2.0
#include "netlink_emu.h"

#include <cerrno>
#include <cstring>

namespace nest {

namespace {
// linux/netlink.h values (kept local: the header differs between libcs).
struct NlHdr {
    uint32_t len;
    uint16_t type;
    uint16_t flags;
    uint32_t seq;
    uint32_t pid;
};
constexpr uint16_t kNlmsgError = 2, kNlmsgDone = 3;
constexpr uint16_t kFRequest = 1, kFMulti = 2, kFDump = 0x300;
constexpr int kNetlinkGeneric = 16;
constexpr uint16_t kGenlIdCtrl = 0x10;

void put(std::vector<uint8_t>& out, const void* p, size_t n) {
    const auto* b = static_cast<const uint8_t*>(p);
    out.insert(out.end(), b, b + n);
}
}  // namespace

std::vector<uint8_t> netlinkReplies(int proto, const uint8_t* buf, size_t len) {
    std::vector<uint8_t> out;
    size_t off = 0;
    while (off + sizeof(NlHdr) <= len) {
        NlHdr h;
        memcpy(&h, buf + off, sizeof(h));
        if (h.len < sizeof(NlHdr) || off + h.len > len) break;
        if (h.flags & kFRequest) {
            if ((h.flags & kFDump) == kFDump) {
                // No links, addresses, routes, sockets...: the dump is empty.
                NlHdr d{sizeof(NlHdr) + 4, kNlmsgDone, kFMulti, h.seq, 0};
                int32_t zero = 0;
                put(out, &d, sizeof(d));
                put(out, &zero, 4);
            } else {
                // Acknowledge. Generic netlink family lookups (nl80211 & co.) find nothing.
                int32_t err = (proto == kNetlinkGeneric && h.type == kGenlIdCtrl) ? -ENOENT : 0;
                NlHdr e{sizeof(NlHdr) + 4 + sizeof(NlHdr), kNlmsgError, 0, h.seq, 0};
                put(out, &e, sizeof(e));
                put(out, &err, 4);
                put(out, &h, sizeof(h));  // the request's header, as the kernel echoes it
            }
        }
        off += (h.len + 3) & ~3u;
    }
    return out;
}

}  // namespace nest
