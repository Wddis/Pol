// Userspace eBPF object store: maps and programs for a guest Android that cannot use the host
// kernel's bpf() (apps have no CAP_BPF / CAP_SYS_ADMIN).
//
// Android 11's bpfloader, netd, the traffic stats and time_in_state code create maps, pin them in
// /sys/fs/bpf, and read and write them from userspace. Maps behave exactly like the kernel's
// (hash and array semantics, update flags, per-CPU value layout, key iteration). Programs are
// accepted and pinned but never run: nothing in the host kernel executes guest bytecode, so their
// maps only change through userspace writes.
//
// Each object is backed by an (empty) file STATE/bpf/obj-<id>; the guest's fd for the object is
// an fd of that file, which is how later calls are matched to their object.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace nest {

// Kernel uapi values used here (linux/bpf.h).
namespace bpfc {
enum Cmd : uint32_t {
    MAP_CREATE = 0, MAP_LOOKUP_ELEM = 1, MAP_UPDATE_ELEM = 2, MAP_DELETE_ELEM = 3, MAP_GET_NEXT_KEY = 4,
    PROG_LOAD = 5, OBJ_PIN = 6, OBJ_GET = 7, PROG_ATTACH = 8, PROG_DETACH = 9, OBJ_GET_INFO_BY_FD = 15,
    PROG_QUERY = 16, RAW_TRACEPOINT_OPEN = 17, MAP_FREEZE = 22,
};
enum MapType : uint32_t {
    HASH = 1, ARRAY = 2, PROG_ARRAY = 3, PERF_EVENT_ARRAY = 4, PERCPU_HASH = 5, PERCPU_ARRAY = 6,
    LRU_HASH = 9, LRU_PERCPU_HASH = 10, ARRAY_OF_MAPS = 12, HASH_OF_MAPS = 13, DEVMAP = 14, CGROUP_ARRAY = 8,
};
constexpr uint64_t ANY = 0, NOEXIST = 1, EXIST = 2;
}  // namespace bpfc

struct BpfObject {
    bool isMap = true;
    uint32_t type = 0;  // map type or program type
    uint32_t keySize = 0, valueSize = 0, maxEntries = 0, flags = 0;
    std::string name;
    std::map<std::vector<uint8_t>, std::vector<uint8_t>> elems;  // stored in the userspace layout
};

class BpfStore {
public:
    explicit BpfStore(int possibleCpus = 1) : cpus_(possibleCpus < 1 ? 1 : possibleCpus) {}

    // Returns the new object's id (>= 1), or -errno for invalid map attributes.
    int create(BpfObject proto);
    bool exists(int id) const;
    BpfObject info(int id) const;  // copy (empty if missing)

    // Size of a value as userspace sees it: per-CPU maps carry one 8-byte-aligned slot per CPU.
    size_t userValueSize(const BpfObject& o) const;
    size_t userValueSize(int id) const;
    size_t keySize(int id) const;

    // Element operations with the kernel's errors (0 or -errno).
    int lookup(int id, const std::vector<uint8_t>& key, std::vector<uint8_t>& value) const;
    int update(int id, const std::vector<uint8_t>& key, const std::vector<uint8_t>& value, uint64_t flags);
    int remove(int id, const std::vector<uint8_t>& key);
    // key == nullptr (or a missing key) yields the first key.
    int nextKey(int id, const std::vector<uint8_t>* key, std::vector<uint8_t>& next) const;

private:
    static bool isArray(uint32_t t);
    static bool isPercpu(uint32_t t);
    int cpus_;
    int nextId_ = 1;
    std::map<int, BpfObject> objs_;
    mutable std::mutex mu_;
};

}  // namespace nest
