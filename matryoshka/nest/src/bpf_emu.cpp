// SPDX-License-Identifier: Apache-2.0
#include "bpf_emu.h"

#include <cerrno>
#include <cstring>

namespace nest {

bool BpfStore::isArray(uint32_t t) {
    return t == bpfc::ARRAY || t == bpfc::PERCPU_ARRAY || t == bpfc::PROG_ARRAY || t == bpfc::PERF_EVENT_ARRAY ||
           t == bpfc::CGROUP_ARRAY || t == bpfc::ARRAY_OF_MAPS || t == bpfc::DEVMAP;
}

bool BpfStore::isPercpu(uint32_t t) {
    return t == bpfc::PERCPU_HASH || t == bpfc::PERCPU_ARRAY || t == bpfc::LRU_PERCPU_HASH;
}

int BpfStore::create(BpfObject proto) {
    if (proto.isMap) {
        if (proto.valueSize == 0 || proto.maxEntries == 0 || proto.keySize == 0) return -EINVAL;
        if (isArray(proto.type) && proto.keySize != 4) return -EINVAL;
        if (proto.keySize > 512 || proto.valueSize > (1u << 20)) return -E2BIG;
    }
    proto.elems.clear();
    std::lock_guard<std::mutex> g(mu_);
    int id = nextId_++;
    objs_[id] = std::move(proto);
    return id;
}

bool BpfStore::exists(int id) const {
    std::lock_guard<std::mutex> g(mu_);
    return objs_.count(id) != 0;
}

BpfObject BpfStore::info(int id) const {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    if (it == objs_.end()) return {};
    BpfObject o = it->second;
    o.elems.clear();
    return o;
}

size_t BpfStore::userValueSize(const BpfObject& o) const {
    if (!isPercpu(o.type)) return o.valueSize;
    return ((o.valueSize + 7) & ~7u) * static_cast<size_t>(cpus_);
}

size_t BpfStore::userValueSize(int id) const {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    return it == objs_.end() ? 0 : userValueSize(it->second);
}

size_t BpfStore::keySize(int id) const {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    return it == objs_.end() ? 0 : it->second.keySize;
}

static uint32_t arrayIndex(const std::vector<uint8_t>& key) {
    uint32_t i = 0;
    memcpy(&i, key.data(), key.size() < 4 ? key.size() : 4);
    return i;
}

int BpfStore::lookup(int id, const std::vector<uint8_t>& key, std::vector<uint8_t>& value) const {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    if (it == objs_.end() || !it->second.isMap) return -EBADF;
    const BpfObject& o = it->second;
    if (key.size() != o.keySize) return -EINVAL;
    if (isArray(o.type) && arrayIndex(key) >= o.maxEntries) return -ENOENT;
    auto e = o.elems.find(key);
    if (e != o.elems.end()) {
        value = e->second;
        return 0;
    }
    if (isArray(o.type)) {  // array slots always exist, zero-initialized
        value.assign(userValueSize(o), 0);
        return 0;
    }
    return -ENOENT;
}

int BpfStore::update(int id, const std::vector<uint8_t>& key, const std::vector<uint8_t>& value, uint64_t flags) {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    if (it == objs_.end() || !it->second.isMap) return -EBADF;
    BpfObject& o = it->second;
    if (key.size() != o.keySize || value.size() != userValueSize(o)) return -EINVAL;
    uint64_t op = flags & 3;
    if (op == 3) return -EINVAL;
    if (isArray(o.type)) {
        if (arrayIndex(key) >= o.maxEntries) return -E2BIG;
        if (op == bpfc::NOEXIST) return -EEXIST;  // every array slot exists
        o.elems[key] = value;
        return 0;
    }
    bool present = o.elems.count(key) != 0;
    if (op == bpfc::NOEXIST && present) return -EEXIST;
    if (op == bpfc::EXIST && !present) return -ENOENT;
    if (!present && o.elems.size() >= o.maxEntries) {
        if (o.type != bpfc::LRU_HASH && o.type != bpfc::LRU_PERCPU_HASH) return -E2BIG;
        o.elems.erase(o.elems.begin());  // LRU maps evict instead of failing
    }
    o.elems[key] = value;
    return 0;
}

int BpfStore::remove(int id, const std::vector<uint8_t>& key) {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    if (it == objs_.end() || !it->second.isMap) return -EBADF;
    BpfObject& o = it->second;
    if (key.size() != o.keySize) return -EINVAL;
    if (isArray(o.type)) return -EINVAL;  // array elements cannot be deleted
    return o.elems.erase(key) ? 0 : -ENOENT;
}

int BpfStore::nextKey(int id, const std::vector<uint8_t>* key, std::vector<uint8_t>& next) const {
    std::lock_guard<std::mutex> g(mu_);
    auto it = objs_.find(id);
    if (it == objs_.end() || !it->second.isMap) return -EBADF;
    const BpfObject& o = it->second;
    if (key && key->size() != o.keySize) return -EINVAL;
    if (isArray(o.type)) {
        uint32_t i = 0;
        if (key) {
            uint32_t cur = arrayIndex(*key);
            i = cur >= o.maxEntries ? 0 : cur + 1;
        }
        if (i >= o.maxEntries) return -ENOENT;
        next.assign(4, 0);
        memcpy(next.data(), &i, 4);
        return 0;
    }
    if (o.elems.empty()) return -ENOENT;
    if (!key) {
        next = o.elems.begin()->first;
        return 0;
    }
    auto e = o.elems.find(*key);
    if (e == o.elems.end()) {  // like the kernel: a missing key restarts the iteration
        next = o.elems.begin()->first;
        return 0;
    }
    ++e;
    if (e == o.elems.end()) return -ENOENT;
    next = e->first;
    return 0;
}

}  // namespace nest
