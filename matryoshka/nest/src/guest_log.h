// Early guest log sink: listens on the guest's /dev/socket/logdw (liblog's datagram socket) and
// appends readable lines to a host file until the guest's own logd takes the socket over.
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <atomic>
#include <string>
#include <thread>

namespace nest {

class GuestLog {
public:
    bool start(const std::string& socketPath, const std::string& outFile);
    void stop();
    ~GuestLog() { stop(); }

private:
    int fd_ = -1, out_ = -1, wake_[2] = {-1, -1};
    std::thread th_;
};

// Formats one liblog datagram ("logd_writer" wire format). Exposed for tests.
std::string formatLogdPacket(const unsigned char* p, size_t n);

}  // namespace nest
