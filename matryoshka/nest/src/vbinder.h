// vbinder: an Android binder driver implemented in userspace.
//
// The guest cannot use the host kernel's /dev/binder (the host's servicemanager already owns its
// context), so the guest's three binder contexts (binder, hwbinder, vndbinder) are served by this
// broker. It follows the kernel driver's semantics: nodes and handles, per-thread transaction
// stacks (including nested calls), oneway calls, BR_ACQUIRE/BR_RELEASE refcounting, death
// notifications, fd passing, scatter-gather buffers (hwbinder) and thread-pool spawning.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace nest::vbinder {

struct BrokerStats {
    uint64_t processes = 0;
    uint64_t transactions = 0;
    uint64_t replies = 0;
    uint64_t oneway = 0;
    uint64_t failedReplies = 0;
    uint64_t deadReplies = 0;
    uint64_t deathNotices = 0;
    uint64_t fdsPassed = 0;
    uint64_t abandonedWaits = 0;  // a client sent a new request while its read was pending
};

class Broker {
public:
    // euidOf(pid) returns the guest's virtual effective uid of a process (from the tracer);
    // without it the host uid is reported.
    explicit Broker(std::string socketPath, std::function<uint32_t(int)> euidOf = nullptr);
    ~Broker();

    bool listen();   // creates the listening socket; false on error
    void serve();    // event loop; returns after stop()
    void stop();     // thread-safe
    BrokerStats stats() const;

private:
    struct Impl;
    std::unique_ptr<Impl> d_;
};

}  // namespace nest::vbinder
