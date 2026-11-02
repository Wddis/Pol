// Guest log tee: listens where the guest's liblog connects for /dev/socket/logdw, appends readable
// lines to a host file (boot diagnostics, tester logs) and forwards each datagram unchanged to the
// guest's own logd socket once it exists.
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <atomic>
#include <string>
#include <thread>

namespace nest {

class GuestLog {
public:
    // forwardPath: the guest logd's socket (host path); empty = file only.
    bool start(const std::string& socketPath, const std::string& outFile, const std::string& forwardPath = "");
    void stop();
    ~GuestLog() { stop(); }

private:
    int fd_ = -1, out_ = -1, fwd_ = -1, wake_[2] = {-1, -1};
    std::string forward_;
    std::thread th_;
};

// Formats one liblog datagram ("logd_writer" wire format). Exposed for tests.
std::string formatLogdPacket(const unsigned char* p, size_t n);

}  // namespace nest
