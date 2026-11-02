// vbinder wire protocol: guest process (client shim in libnest_preload.so) <-> broker.
//
// The guest opens /dev/binder (or hwbinder / vndbinder); the shim connects a "process"
// connection to the broker instead. Each guest thread that issues binder ioctls gets its own
// "thread" connection, because binder state (transaction stacks, replies) is per thread.
// Sockets are AF_UNIX SOCK_SEQPACKET: one message per request, fds travel as SCM_RIGHTS.
//
// Transaction payloads are NOT sent over the socket: the broker reads them straight from the
// sender's memory (process_vm_readv) and writes them into the receiver's shared buffer — one
// copy, like the kernel driver.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>

namespace nest::vbinder {

constexpr uint32_t kMagic = 0x564e4252;  // "RBNV"
constexpr uint32_t kVersion = 2;
constexpr int kMaxFds = 64;               // per message
constexpr uint32_t kMaxReadSize = 64 * 1024;
constexpr uint32_t kMaxWriteSize = 64 * 1024;

enum class Ctx : uint32_t { Binder = 0, HwBinder = 1, VndBinder = 2 };

enum class ConnKind : uint32_t { Process = 1, Thread = 2 };

struct Hello {
    uint32_t magic = kMagic;
    uint32_t version = kVersion;
    ConnKind kind;
    Ctx ctx;
    int32_t pid;
    int32_t tid;
};

enum class Op : uint32_t {
    Mmap = 1,       // process conn: memfd attached; broker maps it and acks
    Ioctl = 2,      // thread conn: binder ioctl
};

struct MmapReq {
    Op op = Op::Mmap;
    uint32_t pad = 0;
    uint64_t size;
    uint64_t clientAddr;  // where the guest mapped the memfd
};

// Followed by `writeSize` bytes of the write buffer (BINDER_WRITE_READ) or the ioctl argument.
struct IoctlReq {
    Op op = Op::Ioctl;
    uint32_t cmd;         // ioctl request (low 32 bits)
    uint64_t argSize;     // bytes of inline argument (non-WRITE_READ ioctls)
    uint64_t writeSize;   // bytes of write buffer that follow
    uint64_t readSize;    // capacity of the guest read buffer
    uint32_t nfds;        // fds attached, in object order of the write buffer
    uint32_t pad = 0;
};

// Followed by `readBytes` bytes for the guest read buffer (or the ioctl out-argument), then
// `nfixups` uint64 offsets (relative to the mmap region) where the guest must store the numbers
// of the attached fds, in order.
struct IoctlResp {
    int32_t ret;          // 0 or -errno
    uint32_t nfixups;
    uint64_t writeConsumed;
    uint64_t readBytes;
    uint32_t flags;       // kRespDrainWake
    uint32_t pad = 0;
};

// poll()/epoll on the binder fd (servicemanager's Looper): the process connection — the fd the
// guest holds — becomes readable through a 1-byte wake message the broker sends when work is
// queued that no blocked thread picks up. At most one is outstanding; a response flagged
// kRespDrainWake tells the client to consume it because no work is left.
constexpr uint32_t kRespDrainWake = 1;

}  // namespace nest::vbinder
