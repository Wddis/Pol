// SPDX-License-Identifier: Apache-2.0
#include "vbinder.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <map>
#include <mutex>
#include <set>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

#include "vbinder_proto.h"

namespace nest::vbinder {

namespace {

constexpr uint64_t align8(uint64_t v) { return (v + 7) & ~uint64_t(7); }

struct Proc;
struct Thread;
struct Node;
struct Ref;
struct Txn;

struct Work {
    enum Type { TXN, COMPLETE, ERROR, NODE_INC, NODE_ACQ, NODE_REL, NODE_DEC, DEAD_BINDER, CLEAR_DEATH_DONE } type;
    Txn* txn = nullptr;
    uint32_t err = 0;
    uint64_t ptr = 0, cookie = 0;
};

struct Node {
    Proc* owner;
    uint64_t ptr, cookie;
    bool contextManager = false;
    int remoteStrong = 0;  // refs with strong > 0
    int remoteWeak = 0;    // refs that exist
    int tmpStrong = 0, tmpWeak = 0;  // holds of in-flight buffers
    bool hasStrong = false, hasWeak = false;  // owner was told BR_ACQUIRE / BR_INCREFS
    std::set<Ref*> refs;
};

struct Ref {
    Proc* proc;
    Node* node;
    uint32_t handle;
    int strong = 0, weak = 0;
    bool deathRequested = false, deathSent = false;
    uint64_t deathCookie = 0;
};

struct Hold {
    Ref* ref = nullptr;
    Node* node = nullptr;
    bool strong = false;
};

struct Buffer {
    Proc* proc;
    uint64_t off, size;
    std::vector<Hold> holds;
};

struct Txn {
    Thread* from = nullptr;  // waiting sender thread (two-way), null for oneway / after its death
    Proc* fromProc = nullptr;
    Proc* toProc = nullptr;
    Thread* toThread = nullptr;
    Txn* fromParent = nullptr;
    Txn* toParent = nullptr;
    Node* node = nullptr;     // target node (requests)
    bool isReply = false;
    uint32_t code = 0, flags = 0;
    int32_t senderPid = 0;
    uint32_t senderEuid = 0;
    Buffer* buf = nullptr;
    uint64_t dataSize = 0, offsetsSize = 0;
    std::vector<int> fds;          // to install in the receiver
    std::vector<uint64_t> fixups;  // where (region offset) the receiver stores each fd number
};

struct Thread {
    Proc* proc;
    int tid;
    int conn;
    std::deque<Work> todo;
    Txn* stack = nullptr;
    bool waiting = false;
    uint64_t readCap = 0, writeConsumed = 0;
    bool looper = false;
};

struct Proc {
    int pid;
    Ctx ctx;
    int conn;
    uint8_t* base = nullptr;
    uint64_t size = 0, clientBase = 0;
    std::map<uint64_t, uint64_t> freeList;  // off -> size
    std::map<uint64_t, Buffer*> buffers;    // off -> buffer
    std::map<uint64_t, Node*> nodes;        // ptr -> node
    std::map<uint32_t, Ref*> refs;          // handle -> ref
    std::map<Node*, Ref*> refByNode;
    std::map<int, Thread*> threads;
    std::deque<Work> todo;
    uint32_t nextHandle = 1;
    int maxThreads = 0, requested = 0, started = 0;
    bool dead = false;
    uint32_t hostUid = 0;
};

}  // namespace

struct Broker::Impl {
    std::string path;
    std::function<uint32_t(int)> euidOf;
    int listenFd = -1, epfd = -1, wakeFd = -1;
    std::atomic<bool> running{false};
    std::map<int, Proc*> procConns;      // conn fd -> proc
    std::map<int, Thread*> threadConns;  // conn fd -> thread
    std::set<int> pendingHello;
    std::map<std::pair<int, Ctx>, Proc*> procs;
    Node* mgr[3] = {nullptr, nullptr, nullptr};
    mutable std::mutex statsMu;
    BrokerStats st;

    // ---------------------------------------------------------------- memory

    bool allocBuf(Proc* p, uint64_t size, uint64_t& off) {
        size = std::max<uint64_t>(align8(size), 8);
        for (auto it = p->freeList.begin(); it != p->freeList.end(); ++it) {
            if (it->second < size) continue;
            off = it->first;
            uint64_t rest = it->second - size;
            p->freeList.erase(it);
            if (rest) p->freeList[off + size] = rest;
            return true;
        }
        return false;
    }

    void freeRange(Proc* p, uint64_t off, uint64_t size) {
        size = std::max<uint64_t>(align8(size), 8);
        auto it = p->freeList.emplace(off, size).first;
        auto next = std::next(it);
        if (next != p->freeList.end() && it->first + it->second == next->first) {
            it->second += next->second;
            p->freeList.erase(next);
        }
        if (it != p->freeList.begin()) {
            auto prev = std::prev(it);
            if (prev->first + prev->second == it->first) {
                prev->second += it->second;
                p->freeList.erase(it);
            }
        }
    }

    // ---------------------------------------------------------------- messaging

    static ssize_t recvWithFds(int fd, void* buf, size_t len, std::vector<int>& fds) {
        char ctrl[CMSG_SPACE(sizeof(int) * kMaxFds)];
        iovec iov{buf, len};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctrl;
        msg.msg_controllen = sizeof(ctrl);
        ssize_t n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
        if (n <= 0) return n;
        for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
            size_t k = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            int* p = reinterpret_cast<int*>(CMSG_DATA(c));
            for (size_t i = 0; i < k; i++) fds.push_back(p[i]);
        }
        return n;
    }

    static bool sendWithFds(int fd, const void* buf, size_t len, const std::vector<int>& fds) {
        iovec iov{const_cast<void*>(buf), len};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        std::vector<char> ctrl;
        if (!fds.empty()) {
            ctrl.resize(CMSG_SPACE(sizeof(int) * fds.size()));
            msg.msg_control = ctrl.data();
            msg.msg_controllen = ctrl.size();
            cmsghdr* c = CMSG_FIRSTHDR(&msg);
            c->cmsg_level = SOL_SOCKET;
            c->cmsg_type = SCM_RIGHTS;
            c->cmsg_len = CMSG_LEN(sizeof(int) * fds.size());
            memcpy(CMSG_DATA(c), fds.data(), sizeof(int) * fds.size());
        }
        return sendmsg(fd, &msg, MSG_NOSIGNAL) == static_cast<ssize_t>(len);
    }

    void sendResp(Thread* t, int ret, const std::vector<uint8_t>& read, const std::vector<int>& fds = {},
                  const std::vector<uint64_t>& fixups = {}) {
        IoctlResp r{};
        r.ret = ret;
        r.nfixups = static_cast<uint32_t>(fixups.size());
        r.writeConsumed = t->writeConsumed;
        r.readBytes = read.size();
        std::vector<uint8_t> msg(sizeof(r) + read.size() + fixups.size() * 8);
        memcpy(msg.data(), &r, sizeof(r));
        if (!read.empty()) memcpy(msg.data() + sizeof(r), read.data(), read.size());
        if (!fixups.empty()) memcpy(msg.data() + sizeof(r) + read.size(), fixups.data(), fixups.size() * 8);
        sendWithFds(t->conn, msg.data(), msg.size(), fds);
        for (int f : fds) close(f);
    }

    // ---------------------------------------------------------------- refs & nodes

    void queueProc(Proc* p, Work w) {
        p->todo.push_back(w);
        for (auto& [tid, t] : p->threads) {
            if (t->waiting && canTakeProcWork(t)) {
                deliver(t);
                return;
            }
        }
    }

    void queueThread(Thread* t, Work w) {
        t->todo.push_back(w);
        if (t->waiting) deliver(t);
    }

    static bool canTakeProcWork(Thread* t) { return !t->stack && t->todo.empty(); }

    void nodeChanged(Node* n) {
        if (n->contextManager || n->owner->dead) return;
        bool wantStrong = n->remoteStrong + n->tmpStrong > 0;
        bool wantWeak = wantStrong || n->remoteWeak + n->tmpWeak > 0;
        // Same ordering as the kernel: INCREFS before ACQUIRE, RELEASE before DECREFS.
        if (wantWeak && !n->hasWeak) {
            n->hasWeak = true;
            queueProc(n->owner, {Work::NODE_INC, nullptr, 0, n->ptr, n->cookie});
        }
        if (wantStrong && !n->hasStrong) {
            n->hasStrong = true;
            queueProc(n->owner, {Work::NODE_ACQ, nullptr, 0, n->ptr, n->cookie});
        }
        if (!wantStrong && n->hasStrong) {
            n->hasStrong = false;
            queueProc(n->owner, {Work::NODE_REL, nullptr, 0, n->ptr, n->cookie});
        }
        if (!wantWeak && n->hasWeak) {
            n->hasWeak = false;
            queueProc(n->owner, {Work::NODE_DEC, nullptr, 0, n->ptr, n->cookie});
        }
    }

    Node* getNode(Proc* p, uint64_t ptr, uint64_t cookie) {
        auto it = p->nodes.find(ptr);
        if (it != p->nodes.end()) return it->second;
        auto* n = new Node{p, ptr, cookie};
        p->nodes[ptr] = n;
        return n;
    }

    Ref* getRef(Proc* p, Node* n) {
        auto it = p->refByNode.find(n);
        if (it != p->refByNode.end()) return it->second;
        uint32_t h = n->contextManager ? 0 : p->nextHandle++;
        auto* r = new Ref{p, n, h};
        p->refs[h] = r;
        p->refByNode[n] = r;
        n->refs.insert(r);
        n->remoteWeak++;
        nodeChanged(n);
        return r;
    }

    void refUpdate(Ref* r, int dStrong, int dWeak) {
        int before = r->strong;
        r->strong = std::max(0, r->strong + dStrong);
        r->weak = std::max(0, r->weak + dWeak);
        Node* n = r->node;
        if (before == 0 && r->strong > 0) n->remoteStrong++;
        if (before > 0 && r->strong == 0) n->remoteStrong--;
        if (r->strong == 0 && r->weak == 0) {
            r->proc->refs.erase(r->handle);
            r->proc->refByNode.erase(n);
            n->refs.erase(r);
            n->remoteWeak--;
            delete r;
        }
        nodeChanged(n);
    }

    void releaseHolds(Buffer* b) {
        for (auto& h : b->holds) {
            if (h.ref) {
                refUpdate(h.ref, h.strong ? -1 : 0, h.strong ? 0 : -1);
            } else if (h.node) {
                (h.strong ? h.node->tmpStrong : h.node->tmpWeak)--;
                nodeChanged(h.node);
            }
        }
        b->holds.clear();
    }

    // ---------------------------------------------------------------- transactions

    // Copies the sender's data into a buffer of `to`, translating binder objects. Returns
    // BR_OK or the BR_* error for the sender.
    uint32_t buildBuffer(Proc* from, Proc* to, const binder_transaction_data& tr, uint64_t sgSize,
                         std::vector<int>& inFds, size_t& fdCursor, Txn* t) {
        if (!to->base) return BR_FAILED_REPLY;
        uint64_t dataSize = tr.data_size, offsSize = tr.offsets_size;
        if (offsSize % 8) return BR_FAILED_REPLY;
        uint64_t sgStart = align8(dataSize) + align8(offsSize);
        uint64_t total = sgStart + align8(sgSize);
        uint64_t off;
        if (!allocBuf(to, total, off)) return BR_FAILED_REPLY;
        auto* b = new Buffer{to, off, total};
        to->buffers[off] = b;
        t->buf = b;
        t->dataSize = dataSize;
        t->offsetsSize = offsSize;
        uint8_t* dst = to->base + off;
        auto fail = [&](uint32_t e) {
            releaseHolds(b);
            to->buffers.erase(off);
            freeRange(to, off, total);
            delete b;
            t->buf = nullptr;
            for (int f : t->fds) close(f);
            t->fds.clear();
            return e;
        };
        auto readFrom = [&](uint64_t addr, void* out, uint64_t len) {
            if (!len) return true;
            iovec l{out, len}, r{reinterpret_cast<void*>(addr), len};
            return process_vm_readv(from->pid, &l, 1, &r, 1, 0) == static_cast<ssize_t>(len);
        };
        if (!readFrom(tr.data.ptr.buffer, dst, dataSize)) return fail(BR_FAILED_REPLY);
        uint64_t* offs = reinterpret_cast<uint64_t*>(dst + align8(dataSize));
        if (!readFrom(tr.data.ptr.offsets, offs, offsSize)) return fail(BR_FAILED_REPLY);

        uint64_t sgCursor = sgStart;
        std::vector<uint64_t> copyOf(offsSize / 8, 0);  // PTR object index -> region offset of its copy
        uint64_t prevEnd = 0;
        for (uint64_t i = 0; i < offsSize / 8; i++) {
            uint64_t o = offs[i];
            if (o < prevEnd || o + sizeof(binder_object_header) > dataSize) return fail(BR_FAILED_REPLY);
            auto* hdr = reinterpret_cast<binder_object_header*>(dst + o);
            switch (hdr->type) {
                case BINDER_TYPE_BINDER:
                case BINDER_TYPE_WEAK_BINDER: {
                    if (o + sizeof(flat_binder_object) > dataSize) return fail(BR_FAILED_REPLY);
                    auto* fp = reinterpret_cast<flat_binder_object*>(hdr);
                    bool strong = hdr->type == BINDER_TYPE_BINDER;
                    Node* n = getNode(from, fp->binder, fp->cookie);
                    Ref* r = getRef(to, n);
                    refUpdate(r, strong ? 1 : 0, strong ? 0 : 1);
                    b->holds.push_back({r, nullptr, strong});
                    hdr->type = strong ? BINDER_TYPE_HANDLE : BINDER_TYPE_WEAK_HANDLE;
                    fp->binder = 0;
                    fp->handle = r->handle;
                    fp->cookie = 0;
                    prevEnd = o + sizeof(flat_binder_object);
                    break;
                }
                case BINDER_TYPE_HANDLE:
                case BINDER_TYPE_WEAK_HANDLE: {
                    if (o + sizeof(flat_binder_object) > dataSize) return fail(BR_FAILED_REPLY);
                    auto* fp = reinterpret_cast<flat_binder_object*>(hdr);
                    bool strong = hdr->type == BINDER_TYPE_HANDLE;
                    auto it = from->refs.find(fp->handle);
                    if (it == from->refs.end()) return fail(BR_FAILED_REPLY);
                    Node* n = it->second->node;
                    if (n->owner == to) {
                        hdr->type = strong ? BINDER_TYPE_BINDER : BINDER_TYPE_WEAK_BINDER;
                        fp->binder = n->ptr;
                        fp->cookie = n->cookie;
                        (strong ? n->tmpStrong : n->tmpWeak)++;
                        nodeChanged(n);
                        b->holds.push_back({nullptr, n, strong});
                    } else {
                        Ref* r = getRef(to, n);
                        refUpdate(r, strong ? 1 : 0, strong ? 0 : 1);
                        b->holds.push_back({r, nullptr, strong});
                        fp->binder = 0;
                        fp->handle = r->handle;
                        fp->cookie = 0;
                    }
                    prevEnd = o + sizeof(flat_binder_object);
                    break;
                }
                case BINDER_TYPE_FD: {
                    if (o + sizeof(binder_fd_object) > dataSize || fdCursor >= inFds.size()) return fail(BR_FAILED_REPLY);
                    t->fds.push_back(inFds[fdCursor]);
                    inFds[fdCursor++] = -1;
                    t->fixups.push_back(off + o + offsetof(binder_fd_object, fd));
                    prevEnd = o + sizeof(binder_fd_object);
                    break;
                }
                case BINDER_TYPE_PTR: {
                    if (o + sizeof(binder_buffer_object) > dataSize) return fail(BR_FAILED_REPLY);
                    auto* bp = reinterpret_cast<binder_buffer_object*>(hdr);
                    uint64_t len = bp->length;
                    if (sgCursor + align8(len) > total) return fail(BR_FAILED_REPLY);
                    if (!readFrom(bp->buffer, to->base + off + sgCursor, len)) return fail(BR_FAILED_REPLY);
                    uint64_t clientAddr = to->clientBase + off + sgCursor;
                    copyOf[i] = off + sgCursor;
                    if (bp->flags & BINDER_BUFFER_FLAG_HAS_PARENT) {
                        if (bp->parent >= i || !copyOf[bp->parent]) return fail(BR_FAILED_REPLY);
                        auto* parent = reinterpret_cast<binder_buffer_object*>(dst + offs[bp->parent]);
                        if (bp->parent_offset + 8 > parent->length) return fail(BR_FAILED_REPLY);
                        memcpy(to->base + copyOf[bp->parent] + bp->parent_offset, &clientAddr, 8);
                    }
                    bp->buffer = clientAddr;
                    sgCursor += align8(len);
                    prevEnd = o + sizeof(binder_buffer_object);
                    break;
                }
                case BINDER_TYPE_FDA: {
                    if (o + sizeof(binder_fd_array_object) > dataSize) return fail(BR_FAILED_REPLY);
                    auto* fa = reinterpret_cast<binder_fd_array_object*>(hdr);
                    if (fa->parent >= i || !copyOf[fa->parent]) return fail(BR_FAILED_REPLY);
                    for (uint64_t k = 0; k < fa->num_fds; k++) {
                        if (fdCursor >= inFds.size()) return fail(BR_FAILED_REPLY);
                        t->fds.push_back(inFds[fdCursor]);
                        inFds[fdCursor++] = -1;
                        t->fixups.push_back(copyOf[fa->parent] + fa->parent_offset + 4 * k);
                    }
                    prevEnd = o + sizeof(binder_fd_array_object);
                    break;
                }
                default:
                    return fail(BR_FAILED_REPLY);
            }
        }
        {
            std::lock_guard<std::mutex> g(statsMu);
            st.fdsPassed += t->fds.size();
        }
        return BR_OK;
    }

    void deadReply(Thread* t) {
        {
            std::lock_guard<std::mutex> g(statsMu);
            st.deadReplies++;
        }
        queueThread(t, {Work::ERROR, nullptr, BR_DEAD_REPLY});
    }

    void sendError(Thread* t, uint32_t err) {
        std::lock_guard<std::mutex> g(statsMu);
        if (err == BR_DEAD_REPLY) st.deadReplies++;
        else st.failedReplies++;
        t->todo.push_back({Work::ERROR, nullptr, err});
    }

    void doTransaction(Thread* t, const binder_transaction_data& tr, uint64_t sgSize, bool reply,
                       std::vector<int>& fds, size_t& fdCursor) {
        Proc* p = t->proc;
        if (reply) {
            Txn* in = t->stack;
            if (!in || in->toThread != t) {
                sendError(t, BR_FAILED_REPLY);
                return;
            }
            t->stack = in->toParent;
            Thread* target = in->from;
            Proc* targetProc = in->fromProc;
            Txn* senderParent = in->fromParent;
            delete in;
            t->todo.push_back({Work::COMPLETE});
            if (!target || targetProc->dead) return;  // nobody waits for it anymore
            auto* r = new Txn();
            r->isReply = true;
            r->fromProc = p;
            r->toProc = targetProc;
            r->code = tr.code;
            r->flags = tr.flags;
            r->senderPid = p->pid;
            r->senderEuid = euid(p);
            uint32_t res = buildBuffer(p, targetProc, tr, sgSize, fds, fdCursor, r);
            target->stack = senderParent;  // the sender's call is complete
            if (res != BR_OK) {
                delete r;
                queueThread(target, {Work::ERROR, nullptr, BR_FAILED_REPLY});
                return;
            }
            {
                std::lock_guard<std::mutex> g(statsMu);
                st.replies++;
            }
            queueThread(target, {Work::TXN, r});
            return;
        }

        Node* node = nullptr;
        if (tr.target.handle == 0) {
            node = mgr[static_cast<int>(p->ctx)];
        } else {
            auto it = p->refs.find(tr.target.handle);
            if (it != p->refs.end()) node = it->second->node;
        }
        if (!node) {
            sendError(t, BR_FAILED_REPLY);
            return;
        }
        if (node->owner->dead) {
            sendError(t, BR_DEAD_REPLY);
            return;
        }
        Proc* targetProc = node->owner;
        bool oneway = tr.flags & TF_ONE_WAY;
        Thread* targetThread = nullptr;
        if (!oneway) {
            // Nested call back into a process that is waiting for us: use that very thread.
            for (Txn* tmp = t->stack; tmp; tmp = tmp->fromParent) {
                if (tmp->from && tmp->from->proc == targetProc) {
                    targetThread = tmp->from;
                    break;
                }
            }
        }
        auto* x = new Txn();
        x->fromProc = p;
        x->toProc = targetProc;
        x->node = node;
        x->code = tr.code;
        x->flags = tr.flags;
        x->senderPid = p->pid;
        x->senderEuid = euid(p);
        uint32_t res = buildBuffer(p, targetProc, tr, sgSize, fds, fdCursor, x);
        if (res != BR_OK) {
            delete x;
            sendError(t, res);
            return;
        }
        t->todo.push_back({Work::COMPLETE});
        {
            std::lock_guard<std::mutex> g(statsMu);
            (oneway ? st.oneway : st.transactions)++;
        }
        if (!oneway) {
            x->from = t;
            x->fromParent = t->stack;
            t->stack = x;
        }
        if (targetThread) queueThread(targetThread, {Work::TXN, x});
        else queueProc(targetProc, {Work::TXN, x});
    }

    uint32_t euid(Proc* p) { return euidOf ? euidOf(p->pid) : p->hostUid; }

    // ---------------------------------------------------------------- write & read

    int processWrite(Thread* t, const uint8_t* buf, uint64_t size, std::vector<int>& fds) {
        Proc* p = t->proc;
        uint64_t pos = 0;
        size_t fdCursor = 0;
        while (pos + 4 <= size) {
            uint32_t cmd;
            memcpy(&cmd, buf + pos, 4);
            uint64_t argSize = _IOC_SIZE(cmd);
            if (pos + 4 + argSize > size) break;
            const uint8_t* arg = buf + pos + 4;
            pos += 4 + argSize;
            switch (cmd) {
                case BC_TRANSACTION:
                case BC_REPLY: {
                    binder_transaction_data tr;
                    memcpy(&tr, arg, sizeof(tr));
                    doTransaction(t, tr, 0, cmd == BC_REPLY, fds, fdCursor);
                    break;
                }
                case BC_TRANSACTION_SG:
                case BC_REPLY_SG: {
                    binder_transaction_data_sg tr;
                    memcpy(&tr, arg, sizeof(tr));
                    doTransaction(t, tr.transaction_data, tr.buffers_size, cmd == BC_REPLY_SG, fds, fdCursor);
                    break;
                }
                case BC_FREE_BUFFER: {
                    uint64_t ptr;
                    memcpy(&ptr, arg, 8);
                    uint64_t off = ptr - p->clientBase;
                    auto it = p->buffers.find(off);
                    if (it != p->buffers.end()) {
                        Buffer* b = it->second;
                        releaseHolds(b);
                        freeRange(p, b->off, b->size);
                        p->buffers.erase(it);
                        delete b;
                    }
                    break;
                }
                case BC_INCREFS:
                case BC_ACQUIRE:
                case BC_RELEASE:
                case BC_DECREFS: {
                    uint32_t h;
                    memcpy(&h, arg, 4);
                    Ref* r = nullptr;
                    auto it = p->refs.find(h);
                    if (it != p->refs.end()) r = it->second;
                    else if (h == 0 && mgr[static_cast<int>(p->ctx)]) r = getRef(p, mgr[static_cast<int>(p->ctx)]);
                    if (!r) break;
                    if (cmd == BC_INCREFS) refUpdate(r, 0, 1);
                    else if (cmd == BC_ACQUIRE) refUpdate(r, 1, 0);
                    else if (cmd == BC_RELEASE) refUpdate(r, -1, 0);
                    else refUpdate(r, 0, -1);
                    break;
                }
                case BC_INCREFS_DONE:
                case BC_ACQUIRE_DONE:
                case BC_DEAD_BINDER_DONE:
                    break;
                case BC_REGISTER_LOOPER:
                    t->looper = true;
                    if (p->requested > 0) p->requested--;
                    p->started++;
                    break;
                case BC_ENTER_LOOPER:
                    t->looper = true;
                    break;
                case BC_EXIT_LOOPER:
                    t->looper = false;
                    break;
                case BC_REQUEST_DEATH_NOTIFICATION:
                case BC_CLEAR_DEATH_NOTIFICATION: {
                    binder_handle_cookie hc;
                    memcpy(&hc, arg, sizeof(hc));
                    auto it = p->refs.find(hc.handle);
                    if (it == p->refs.end()) break;
                    Ref* r = it->second;
                    if (cmd == BC_REQUEST_DEATH_NOTIFICATION) {
                        r->deathRequested = true;
                        r->deathCookie = hc.cookie;
                        r->deathSent = false;
                        if (r->node->owner->dead) {
                            r->deathSent = true;
                            t->todo.push_back({Work::DEAD_BINDER, nullptr, 0, 0, hc.cookie});
                        }
                    } else {
                        r->deathRequested = false;
                        t->todo.push_back({Work::CLEAR_DEATH_DONE, nullptr, 0, 0, hc.cookie});
                    }
                    break;
                }
                default:
                    t->writeConsumed = pos - 4 - argSize;
                    return -EINVAL;
            }
        }
        for (size_t i = fdCursor; i < fds.size(); i++)
            if (fds[i] >= 0) close(fds[i]);
        t->writeConsumed = pos;
        return 0;
    }

    // Builds the read buffer for a waiting thread and answers its pending ioctl.
    void deliver(Thread* t) {
        Proc* p = t->proc;
        std::vector<uint8_t> out;
        std::vector<int> fds;
        std::vector<uint64_t> fixups;
        auto put32 = [&](uint32_t v) { out.insert(out.end(), reinterpret_cast<uint8_t*>(&v), reinterpret_cast<uint8_t*>(&v) + 4); };
        auto putRaw = [&](const void* d, size_t n) { out.insert(out.end(), static_cast<const uint8_t*>(d), static_cast<const uint8_t*>(d) + n); };
        put32(BR_NOOP);
        bool tookProcWork = false;
        while (true) {
            std::deque<Work>* q = nullptr;
            if (!t->todo.empty()) q = &t->todo;
            else if (canTakeProcWork(t) && !p->todo.empty() && !tookProcWork) q = &p->todo;
            if (!q) break;
            Work w = q->front();
            size_t need = 4 + (w.type == Work::TXN ? sizeof(binder_transaction_data) : 16);
            if (out.size() + need > t->readCap) break;
            q->pop_front();
            if (q == &p->todo) tookProcWork = true;
            bool stop = false;
            switch (w.type) {
                case Work::COMPLETE: put32(BR_TRANSACTION_COMPLETE); break;
                case Work::ERROR: put32(w.err); stop = true; break;
                case Work::NODE_INC:
                case Work::NODE_ACQ:
                case Work::NODE_REL:
                case Work::NODE_DEC: {
                    put32(w.type == Work::NODE_INC ? BR_INCREFS : w.type == Work::NODE_ACQ ? BR_ACQUIRE
                          : w.type == Work::NODE_REL ? BR_RELEASE : BR_DECREFS);
                    binder_ptr_cookie pc{w.ptr, w.cookie};
                    putRaw(&pc, sizeof(pc));
                    break;
                }
                case Work::DEAD_BINDER: {
                    put32(BR_DEAD_BINDER);
                    uint64_t c = w.cookie;
                    putRaw(&c, 8);
                    break;
                }
                case Work::CLEAR_DEATH_DONE: {
                    put32(BR_CLEAR_DEATH_NOTIFICATION_DONE);
                    uint64_t c = w.cookie;
                    putRaw(&c, 8);
                    break;
                }
                case Work::TXN: {
                    Txn* x = w.txn;
                    binder_transaction_data tr{};
                    if (!x->isReply) {
                        tr.target.ptr = x->node->ptr;
                        tr.cookie = x->node->cookie;
                    }
                    tr.code = x->code;
                    tr.flags = x->flags;
                    tr.sender_pid = x->senderPid;
                    tr.sender_euid = x->senderEuid;
                    tr.data_size = x->dataSize;
                    tr.offsets_size = x->offsetsSize;
                    tr.data.ptr.buffer = p->clientBase + x->buf->off;
                    tr.data.ptr.offsets = p->clientBase + x->buf->off + align8(x->dataSize);
                    put32(x->isReply ? BR_REPLY : BR_TRANSACTION);
                    putRaw(&tr, sizeof(tr));
                    fds = x->fds;
                    fixups = x->fixups;
                    if (!x->isReply && !(x->flags & TF_ONE_WAY)) {
                        x->toThread = t;
                        x->toParent = t->stack;
                        t->stack = x;
                    } else {
                        delete x;
                    }
                    stop = true;  // one transaction per read, like the kernel
                    break;
                }
            }
            if (stop) break;
        }
        // Ask for another looper thread when this process may run dry.
        bool otherIdle = false;
        for (auto& [tid, o] : p->threads)
            if (o != t && o->waiting && canTakeProcWork(o)) otherIdle = true;
        if (p->requested == 0 && !otherIdle && p->started < p->maxThreads && t->looper &&
            out.size() + 4 <= t->readCap) {
            p->requested++;
            put32(BR_SPAWN_LOOPER);
        }
        t->waiting = false;
        sendResp(t, 0, out, fds, fixups);
    }

    // ---------------------------------------------------------------- lifecycle

    void destroyThread(Thread* t) {
        Proc* p = t->proc;
        // Transactions this thread was serving: their senders get BR_DEAD_REPLY.
        for (Txn* x = t->stack; x;) {
            Txn* next = x->toThread == t ? x->toParent : x->fromParent;
            if (x->toThread == t && x->from) {
                Thread* s = x->from;
                s->stack = x->fromParent;
                deadReply(s);
                delete x;
            } else if (x->from == t) {
                x->from = nullptr;  // reply will be dropped
            }
            x = next;
        }
        t->stack = nullptr;
        for (auto& w : t->todo) {
            if (w.type != Work::TXN || !w.txn) continue;
            Txn* x = w.txn;
            if (!x->isReply && x->from) {  // a nested call addressed to this thread
                x->from->stack = x->fromParent;
                deadReply(x->from);
            }
            delete x;
        }
        p->threads.erase(t->tid);
        threadConns.erase(t->conn);
        epoll_ctl(epfd, EPOLL_CTL_DEL, t->conn, nullptr);
        close(t->conn);
        delete t;
    }

    void destroyProc(Proc* p) {
        if (p->dead) return;
        p->dead = true;
        for (int i = 0; i < 3; i++)
            if (mgr[i] && mgr[i]->owner == p) mgr[i] = nullptr;
        while (!p->threads.empty()) destroyThread(p->threads.begin()->second);
        // Two-way transactions still queued for this process: fail their senders.
        for (auto& w : p->todo) {
            if (w.type != Work::TXN || !w.txn) continue;
            Txn* x = w.txn;
            if (x->from) {
                x->from->stack = x->fromParent;
                deadReply(x->from);
            }
            delete x;
        }
        p->todo.clear();
        // Death notifications to everyone holding a reference to our nodes.
        for (auto& [ptr, n] : p->nodes) {
            for (Ref* r : n->refs) {
                if (r->deathRequested && !r->deathSent && !r->proc->dead) {
                    r->deathSent = true;
                    {
                        std::lock_guard<std::mutex> g(statsMu);
                        st.deathNotices++;
                    }
                    queueProc(r->proc, {Work::DEAD_BINDER, nullptr, 0, 0, r->deathCookie});
                }
            }
        }
        // Drop our references to other processes' nodes.
        for (auto& [h, r] : p->refs) {
            Node* n = r->node;
            n->refs.erase(r);
            if (r->strong > 0) n->remoteStrong--;
            n->remoteWeak--;
            delete r;
            nodeChanged(n);
        }
        p->refs.clear();
        p->refByNode.clear();
        for (auto& [off, b] : p->buffers) delete b;
        p->buffers.clear();
        if (p->base) munmap(p->base, p->size);
        p->base = nullptr;
        procConns.erase(p->conn);
        epoll_ctl(epfd, EPOLL_CTL_DEL, p->conn, nullptr);
        close(p->conn);
        procs.erase({p->pid, p->ctx});
        // Nodes stay allocated (owner->dead) so refs elsewhere keep a valid pointer.
    }

    // ---------------------------------------------------------------- connections

    void onHello(int fd, const Hello& h) {
        pendingHello.erase(fd);
        if (h.magic != kMagic || h.version != kVersion || static_cast<uint32_t>(h.ctx) > 2) {
            epoll_ctl(epfd, EPOLL_CTL_DEL, fd, nullptr);
            close(fd);
            return;
        }
        ucred cred{};
        socklen_t cl = sizeof(cred);
        getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &cl);
        if (h.kind == ConnKind::Process) {
            auto it = procs.find({h.pid, h.ctx});
            if (it != procs.end()) destroyProc(it->second);  // pid reused / reopened
            auto* p = new Proc();
            p->pid = h.pid;
            p->ctx = h.ctx;
            p->conn = fd;
            p->hostUid = cred.uid;
            procs[{h.pid, h.ctx}] = p;
            procConns[fd] = p;
            std::lock_guard<std::mutex> g(statsMu);
            st.processes++;
        } else {
            auto it = procs.find({h.pid, h.ctx});
            if (it == procs.end()) {
                epoll_ctl(epfd, EPOLL_CTL_DEL, fd, nullptr);
                close(fd);
                return;
            }
            Proc* p = it->second;
            auto old = p->threads.find(h.tid);
            if (old != p->threads.end()) destroyThread(old->second);
            auto* t = new Thread{p, h.tid, fd};
            p->threads[h.tid] = t;
            threadConns[fd] = t;
        }
    }

    void onProcMsg(Proc* p, const uint8_t* buf, size_t n, std::vector<int>& fds) {
        if (n < sizeof(MmapReq)) return;
        MmapReq req;
        memcpy(&req, buf, sizeof(req));
        int32_t ok = -EINVAL;
        if (req.op == Op::Mmap && fds.size() == 1 && !p->base && req.size > 0 && req.size <= (64u << 20)) {
            void* m = mmap(nullptr, req.size, PROT_READ | PROT_WRITE, MAP_SHARED, fds[0], 0);
            if (m != MAP_FAILED) {
                p->base = static_cast<uint8_t*>(m);
                p->size = req.size;
                p->clientBase = req.clientAddr;
                p->freeList[0] = req.size;
                ok = 0;
            }
        }
        for (int f : fds) close(f);
        fds.clear();
        send(p->conn, &ok, sizeof(ok), MSG_NOSIGNAL);
    }

    void onThreadMsg(Thread* t, const uint8_t* buf, size_t n, std::vector<int>& fds) {
        if (n < sizeof(IoctlReq)) return;
        IoctlReq req;
        memcpy(&req, buf, sizeof(req));
        const uint8_t* payload = buf + sizeof(req);
        Proc* p = t->proc;
        t->writeConsumed = 0;
        switch (req.cmd) {
            case static_cast<uint32_t>(BINDER_VERSION): {
                binder_version v{BINDER_CURRENT_PROTOCOL_VERSION};
                std::vector<uint8_t> out(sizeof(v));
                memcpy(out.data(), &v, sizeof(v));
                sendResp(t, 0, out);
                return;
            }
            case static_cast<uint32_t>(BINDER_SET_MAX_THREADS): {
                uint32_t m = 0;
                if (req.argSize >= 4) memcpy(&m, payload, 4);
                p->maxThreads = static_cast<int>(m);
                sendResp(t, 0, {});
                return;
            }
            case static_cast<uint32_t>(BINDER_SET_CONTEXT_MGR): {
                int c = static_cast<int>(p->ctx);
                int ret = -EBUSY;
                if (!mgr[c]) {
                    Node* n = getNode(p, 0, 0);
                    n->contextManager = true;
                    n->hasStrong = n->hasWeak = true;
                    mgr[c] = n;
                    ret = 0;
                }
                sendResp(t, ret, {});
                return;
            }
            case static_cast<uint32_t>(BINDER_THREAD_EXIT): {
                sendResp(t, 0, {});
                destroyThread(t);
                return;
            }
            case static_cast<uint32_t>(BINDER_WRITE_READ): {
                std::vector<int> f = fds;
                fds.clear();
                int ret = processWrite(t, payload, std::min<uint64_t>(req.writeSize, n - sizeof(req)), f);
                if (ret != 0) {
                    sendResp(t, ret, {});
                    return;
                }
                t->readCap = std::min<uint64_t>(req.readSize, kMaxReadSize);
                if (t->readCap == 0) {
                    sendResp(t, 0, {});
                    return;
                }
                bool hasWork = !t->todo.empty() || (canTakeProcWork(t) && !p->todo.empty());
                t->waiting = true;
                if (hasWork) deliver(t);
                return;  // otherwise the answer comes when work arrives
            }
            default:
                // BINDER_SET_CONTEXT_MGR_EXT (makes libbinder fall back to the plain variant),
                // debug queries, freeze, ... are not supported.
                sendResp(t, -EINVAL, {});
                return;
        }
    }

    void onReadable(int fd) {
        std::vector<uint8_t> buf(sizeof(IoctlReq) + kMaxWriteSize + 256);
        std::vector<int> fds;
        ssize_t n = recvWithFds(fd, buf.data(), buf.size(), fds);
        if (n <= 0) {
            for (int f : fds) close(f);
            if (pendingHello.count(fd)) {
                pendingHello.erase(fd);
                epoll_ctl(epfd, EPOLL_CTL_DEL, fd, nullptr);
                close(fd);
            } else if (auto it = threadConns.find(fd); it != threadConns.end()) {
                destroyThread(it->second);
            } else if (auto pt = procConns.find(fd); pt != procConns.end()) {
                destroyProc(pt->second);
            }
            return;
        }
        if (pendingHello.count(fd)) {
            Hello h{};
            if (static_cast<size_t>(n) >= sizeof(h)) memcpy(&h, buf.data(), sizeof(h));
            for (int f : fds) close(f);
            onHello(fd, h);
            return;
        }
        if (auto it = threadConns.find(fd); it != threadConns.end()) {
            onThreadMsg(it->second, buf.data(), static_cast<size_t>(n), fds);
        } else if (auto pt = procConns.find(fd); pt != procConns.end()) {
            onProcMsg(pt->second, buf.data(), static_cast<size_t>(n), fds);
        }
        for (int f : fds)
            if (f >= 0) close(f);
    }
};

Broker::Broker(std::string socketPath, std::function<uint32_t(int)> euidOf) : d_(new Impl) {
    d_->path = std::move(socketPath);
    d_->euidOf = std::move(euidOf);
}

Broker::~Broker() {
    stop();
    if (d_->listenFd >= 0) close(d_->listenFd);
    if (d_->epfd >= 0) close(d_->epfd);
    if (d_->wakeFd >= 0) close(d_->wakeFd);
    unlink(d_->path.c_str());
}

bool Broker::listen() {
    sockaddr_un sa{};
    sa.sun_family = AF_UNIX;
    if (d_->path.size() >= sizeof(sa.sun_path)) return false;
    memcpy(sa.sun_path, d_->path.c_str(), d_->path.size() + 1);
    unlink(d_->path.c_str());
    d_->listenFd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (d_->listenFd < 0) return false;
    if (bind(d_->listenFd, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) != 0) return false;
    if (::listen(d_->listenFd, 128) != 0) return false;
    d_->epfd = epoll_create1(EPOLL_CLOEXEC);
    d_->wakeFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    epoll_event ev{};
    ev.events = EPOLLIN;
    ev.data.fd = d_->listenFd;
    epoll_ctl(d_->epfd, EPOLL_CTL_ADD, d_->listenFd, &ev);
    ev.data.fd = d_->wakeFd;
    epoll_ctl(d_->epfd, EPOLL_CTL_ADD, d_->wakeFd, &ev);
    return true;
}

void Broker::serve() {
    d_->running = true;
    epoll_event evs[64];
    while (d_->running) {
        int n = epoll_wait(d_->epfd, evs, 64, -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = 0; i < n; i++) {
            int fd = evs[i].data.fd;
            if (fd == d_->wakeFd) {
                d_->running = false;
                break;
            }
            if (fd == d_->listenFd) {
                int c = accept4(d_->listenFd, nullptr, nullptr, SOCK_CLOEXEC);
                if (c < 0) continue;
                epoll_event ev{};
                ev.events = EPOLLIN;
                ev.data.fd = c;
                epoll_ctl(d_->epfd, EPOLL_CTL_ADD, c, &ev);
                d_->pendingHello.insert(c);
                continue;
            }
            d_->onReadable(fd);
        }
    }
}

void Broker::stop() {
    if (d_->wakeFd >= 0) {
        uint64_t one = 1;
        ssize_t ignored = write(d_->wakeFd, &one, sizeof(one));
        (void)ignored;
    }
}

BrokerStats Broker::stats() const {
    std::lock_guard<std::mutex> g(d_->statsMu);
    return d_->st;
}

}  // namespace nest::vbinder
