// SPDX-License-Identifier: Apache-2.0
#include "guest_log.h"

#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

namespace nest {

std::string formatLogdPacket(const unsigned char* p, size_t n) {
    // android_log_header_t (packed): u8 log id, u16 tid, u32 tv_sec, u32 tv_nsec
    constexpr size_t kHeader = 11;
    if (n <= kHeader) return {};
    uint8_t id = p[0];
    uint16_t tid;
    memcpy(&tid, p + 1, 2);
    const unsigned char* body = p + kHeader;
    size_t len = n - kHeader;
    static const char kPrio[] = "??VDIWEFS";
    if (id == 2 || id == 5) return {};  // binary event / security logs
    if (len < 2) return {};
    char prio = body[0] < sizeof(kPrio) - 1 ? kPrio[body[0]] : '?';
    const char* tag = reinterpret_cast<const char*>(body + 1);
    size_t tagLen = strnlen(tag, len - 1);
    std::string msg;
    if (1 + tagLen + 1 < len) msg.assign(reinterpret_cast<const char*>(body + 1 + tagLen + 1), len - 1 - tagLen - 1);
    while (!msg.empty() && (msg.back() == '\0' || msg.back() == '\n')) msg.pop_back();
    char head[96];
    snprintf(head, sizeof(head), "%c/%.*s(%u): ", prio, static_cast<int>(tagLen), tag, tid);
    return head + msg + "\n";
}

bool GuestLog::start(const std::string& socketPath, const std::string& outFile, const std::string& forwardPath) {
    forward_ = forwardPath;
    sockaddr_un sa{};
    sa.sun_family = AF_UNIX;
    if (socketPath.size() >= sizeof(sa.sun_path)) return false;
    memcpy(sa.sun_path, socketPath.c_str(), socketPath.size() + 1);
    unlink(socketPath.c_str());
    fd_ = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd_ < 0 || bind(fd_, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) != 0) return false;
    chmod(socketPath.c_str(), 0666);
    out_ = open(outFile.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (out_ < 0 || pipe(wake_) != 0) return false;
    if (!forward_.empty()) fwd_ = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    th_ = std::thread([this] {
        unsigned char buf[5 * 1024];
        for (;;) {
            pollfd pf[2] = {{fd_, POLLIN, 0}, {wake_[0], POLLIN, 0}};
            if (poll(pf, 2, -1) < 0) continue;
            if (pf[1].revents) break;
            ssize_t n = recv(fd_, buf, sizeof(buf), 0);
            if (n <= 0) continue;
            if (fwd_ >= 0) {  // to the guest's logd (dropped while it is not up, like liblog does)
                sockaddr_un to{};
                to.sun_family = AF_UNIX;
                if (forward_.size() < sizeof(to.sun_path)) {
                    memcpy(to.sun_path, forward_.c_str(), forward_.size() + 1);
                    sendto(fwd_, buf, static_cast<size_t>(n), MSG_DONTWAIT, reinterpret_cast<sockaddr*>(&to), sizeof(to));
                }
            }
            std::string line = formatLogdPacket(buf, static_cast<size_t>(n));
            if (!line.empty()) {
                ssize_t w = write(out_, line.data(), line.size());
                (void)w;
            }
        }
    });
    return true;
}

void GuestLog::stop() {
    if (wake_[1] >= 0) {
        ssize_t w = write(wake_[1], "x", 1);
        (void)w;
    }
    if (th_.joinable()) th_.join();
    for (int f : {fd_, out_, fwd_, wake_[0], wake_[1]})
        if (f >= 0) close(f);
    fd_ = out_ = fwd_ = wake_[0] = wake_[1] = -1;
}

}  // namespace nest
