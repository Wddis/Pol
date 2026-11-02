// Live mount table shared between the tracer (writer) and every guest process's fast path
// (readers). Guest init mounts things all the time; a process must see a new mount right away,
// not only the table it inherited at exec.
//
// File layout (fixed size, mapped MAP_SHARED by readers):
//   u64 generation   odd while the writer is updating (seqlock)
//   u32 length       bytes of text that follow
//   u32 reserved
//   text             Vfs::serialize() output
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace nest {

constexpr size_t kVfsShareSize = 256 * 1024;
constexpr size_t kVfsShareHeader = 16;

// Writer side (tracer / supervisor). The file must already have kVfsShareSize bytes.
class VfsShareWriter {
public:
    bool open(const std::string& path);  // creates/truncates the file
    bool publish(const std::string& text);
    ~VfsShareWriter();

private:
    int fd_ = -1;
    void* map_ = nullptr;
    uint64_t gen_ = 0;
};

// Reader side. `attach` maps the file; `changed` is a cheap check (one atomic load);
// `read` returns a consistent snapshot of the text.
class VfsShareReader {
public:
    bool attach(int fd);
    bool changed() const;
    bool read(std::string& out);

private:
    const uint8_t* map_ = nullptr;
    uint64_t seen_ = ~0ull;
};

}  // namespace nest
