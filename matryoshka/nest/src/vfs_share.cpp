// SPDX-License-Identifier: Apache-2.0
#include "vfs_share.h"

#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

namespace nest {

namespace {
std::atomic<uint64_t>* genOf(const void* map) {
    return reinterpret_cast<std::atomic<uint64_t>*>(const_cast<void*>(map));
}
}  // namespace

bool VfsShareWriter::open(const std::string& path) {
    fd_ = ::open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd_ < 0) return false;
    if (ftruncate(fd_, kVfsShareSize) != 0) return false;
    map_ = mmap(nullptr, kVfsShareSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    return map_ != MAP_FAILED;
}

bool VfsShareWriter::publish(const std::string& text) {
    if (!map_ || map_ == MAP_FAILED || text.size() > kVfsShareSize - kVfsShareHeader) return false;
    auto* gen = genOf(map_);
    gen->store(++gen_ | 1, std::memory_order_release);  // odd: update in progress
    gen_ |= 1;
    auto* base = static_cast<uint8_t*>(map_);
    uint32_t len = static_cast<uint32_t>(text.size());
    memcpy(base + 8, &len, 4);
    memcpy(base + kVfsShareHeader, text.data(), text.size());
    std::atomic_thread_fence(std::memory_order_release);
    gen->store(++gen_, std::memory_order_release);  // even: consistent
    return true;
}

VfsShareWriter::~VfsShareWriter() {
    if (map_ && map_ != MAP_FAILED) munmap(map_, kVfsShareSize);
    if (fd_ >= 0) close(fd_);
}

bool VfsShareReader::attach(int fd) {
    void* m = mmap(nullptr, kVfsShareSize, PROT_READ, MAP_SHARED, fd, 0);
    if (m == MAP_FAILED) return false;
    map_ = static_cast<const uint8_t*>(m);
    return true;
}

bool VfsShareReader::changed() const {
    return map_ && genOf(map_)->load(std::memory_order_acquire) != seen_;
}

bool VfsShareReader::read(std::string& out) {
    if (!map_) return false;
    for (int attempt = 0; attempt < 1000; attempt++) {
        uint64_t g1 = genOf(map_)->load(std::memory_order_acquire);
        if (g1 & 1) continue;  // writer busy
        uint32_t len;
        memcpy(&len, map_ + 8, 4);
        if (len > kVfsShareSize - kVfsShareHeader) continue;
        std::string text(reinterpret_cast<const char*>(map_ + kVfsShareHeader), len);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (genOf(map_)->load(std::memory_order_acquire) != g1) continue;
        out.swap(text);
        seen_ = g1;
        return true;
    }
    return false;
}

}  // namespace nest
