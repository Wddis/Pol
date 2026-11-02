// Test helper speaking the raw binder protocol (the same ioctls and commands as Android's
// libbinder), to exercise the vbinder broker through libnest_preload.so.
//
//   binderprog manager   context manager: 1=ADD(obj) 2=GET 3=ECHO 4=QUIT (poll-manager: via poll())
//   binderprog server    registers node (0x1000, 0x2000): 10=pong 11=fd 13=nested callback 99=exit
//   binderprog client    finds the service and runs the scenario, printing one line per step
//
// SPDX-License-Identifier: Apache-2.0
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <linux/android/binder.h>
#include <string>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace {

int g_fd = -1;
uint64_t g_lastDeath = 0;  // death notifications may arrive while waiting for a reply

struct Reply {
    uint32_t cmd = 0;  // BR_REPLY, BR_DEAD_REPLY, BR_FAILED_REPLY
    std::string data;
    std::vector<flat_binder_object> objs;
};

struct Out {
    std::vector<uint8_t> b;
    void u32(uint32_t v) { b.insert(b.end(), reinterpret_cast<uint8_t*>(&v), reinterpret_cast<uint8_t*>(&v) + 4); }
    void raw(const void* p, size_t n) { b.insert(b.end(), static_cast<const uint8_t*>(p), static_cast<const uint8_t*>(p) + n); }
};

// Payload with an optional single object at offset 0 (stable storage for the duration of a call).
struct Parcel {
    std::vector<uint8_t> data;
    std::vector<binder_size_t> offsets;
    static Parcel bytes(const std::string& s) {
        Parcel p;
        p.data.assign(s.begin(), s.end());
        return p;
    }
    static Parcel object(const flat_binder_object& o, const std::string& tail = "") {
        Parcel p;
        p.data.resize(sizeof(o));
        memcpy(p.data.data(), &o, sizeof(o));
        p.data.insert(p.data.end(), tail.begin(), tail.end());
        p.offsets.push_back(0);
        return p;
    }
    static Parcel fd(int fd) {
        binder_fd_object f{};
        f.hdr.type = BINDER_TYPE_FD;
        f.fd = static_cast<uint32_t>(fd);
        Parcel p;
        p.data.resize(sizeof(f));
        memcpy(p.data.data(), &f, sizeof(f));
        p.offsets.push_back(0);
        return p;
    }
    binder_transaction_data txn(uint32_t handle, uint32_t code, uint32_t flags) const {
        binder_transaction_data tr{};
        tr.target.handle = handle;
        tr.code = code;
        tr.flags = flags;
        tr.data_size = data.size();
        tr.offsets_size = offsets.size() * sizeof(binder_size_t);
        tr.data.ptr.buffer = reinterpret_cast<binder_uintptr_t>(data.data());
        tr.data.ptr.offsets = reinterpret_cast<binder_uintptr_t>(offsets.data());
        return tr;
    }
};

bool talk(const Out& w, std::vector<uint8_t>& r) {
    binder_write_read bwr{};
    bwr.write_buffer = reinterpret_cast<binder_uintptr_t>(w.b.data());
    bwr.write_size = w.b.size();
    r.assign(512, 0);
    bwr.read_buffer = reinterpret_cast<binder_uintptr_t>(r.data());
    bwr.read_size = r.size();
    if (ioctl(g_fd, BINDER_WRITE_READ, &bwr) != 0) return false;
    r.resize(bwr.read_consumed);
    return true;
}

void writeOnly(const Out& w) {
    binder_write_read bwr{};
    bwr.write_buffer = reinterpret_cast<binder_uintptr_t>(w.b.data());
    bwr.write_size = w.b.size();
    ioctl(g_fd, BINDER_WRITE_READ, &bwr);
}

Reply readPayload(const binder_transaction_data& tr, uint32_t cmd) {
    Reply rep;
    rep.cmd = cmd;
    const auto* data = reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer);
    rep.data.assign(reinterpret_cast<const char*>(data), tr.data_size);
    const auto* offs = reinterpret_cast<const binder_size_t*>(tr.data.ptr.offsets);
    for (size_t i = 0; i < tr.offsets_size / sizeof(binder_size_t); i++) {
        flat_binder_object o;
        memcpy(&o, data + offs[i], sizeof(o));
        rep.objs.push_back(o);
    }
    if (!rep.objs.empty()) rep.data = rep.data.substr(sizeof(flat_binder_object));
    return rep;
}

using Handler = std::function<Parcel(const binder_transaction_data&, const Reply&)>;
Handler g_handler;  // serves incoming calls, also while waiting for a reply (nested calls)

void freeBuffer(Out& o, binder_uintptr_t buf) {
    o.u32(BC_FREE_BUFFER);
    o.raw(&buf, sizeof(buf));
}

// Processes one read buffer. Returns true once a reply/death arrives for the caller.
bool dispatch(const std::vector<uint8_t>& r, Reply* reply, uint64_t* deadCookie) {
    size_t p = 0;
    bool done = false;
    while (p + 4 <= r.size()) {
        uint32_t cmd;
        memcpy(&cmd, r.data() + p, 4);
        p += 4;
        switch (cmd) {
            case BR_NOOP:
            case BR_TRANSACTION_COMPLETE:
            case BR_SPAWN_LOOPER:
                break;
            case BR_INCREFS:
            case BR_ACQUIRE:
            case BR_RELEASE:
            case BR_DECREFS: {
                binder_ptr_cookie pc;
                memcpy(&pc, r.data() + p, sizeof(pc));
                p += sizeof(pc);
                if (cmd == BR_INCREFS || cmd == BR_ACQUIRE) {
                    Out o;
                    o.u32(cmd == BR_INCREFS ? BC_INCREFS_DONE : BC_ACQUIRE_DONE);
                    o.raw(&pc, sizeof(pc));
                    writeOnly(o);
                }
                break;
            }
            case BR_DEAD_BINDER: {
                uint64_t c;
                memcpy(&c, r.data() + p, 8);
                p += 8;
                g_lastDeath = c;
                if (deadCookie) {
                    *deadCookie = c;
                    done = true;
                }
                Out o;
                o.u32(BC_DEAD_BINDER_DONE);
                o.raw(&c, 8);
                writeOnly(o);
                break;
            }
            case BR_CLEAR_DEATH_NOTIFICATION_DONE:
                p += 8;
                break;
            case BR_DEAD_REPLY:
            case BR_FAILED_REPLY:
                if (reply) {
                    reply->cmd = cmd;
                    done = true;
                }
                break;
            case BR_REPLY: {
                binder_transaction_data tr;
                memcpy(&tr, r.data() + p, sizeof(tr));
                p += sizeof(tr);
                Reply got = readPayload(tr, BR_REPLY);
                if (reply) *reply = got;
                // Like libbinder: take references on received handles *before* freeing the buffer
                // (the buffer's temporary reference is dropped on BC_FREE_BUFFER).
                Out o;
                for (auto& obj : got.objs) {
                    if (obj.hdr.type != BINDER_TYPE_HANDLE) continue;
                    o.u32(BC_INCREFS);
                    o.u32(obj.handle);
                    o.u32(BC_ACQUIRE);
                    o.u32(obj.handle);
                }
                freeBuffer(o, tr.data.ptr.buffer);
                writeOnly(o);
                done = true;
                break;
            }
            case BR_TRANSACTION: {
                binder_transaction_data tr;
                memcpy(&tr, r.data() + p, sizeof(tr));
                p += sizeof(tr);
                Reply in = readPayload(tr, BR_TRANSACTION);
                Parcel out = g_handler ? g_handler(tr, in) : Parcel();
                if (tr.flags & TF_ONE_WAY) {
                    Out o;
                    freeBuffer(o, tr.data.ptr.buffer);
                    writeOnly(o);
                } else {
                    Out o;
                    freeBuffer(o, tr.data.ptr.buffer);
                    o.u32(BC_REPLY);
                    binder_transaction_data rt = out.txn(0, 0, 0);
                    o.raw(&rt, sizeof(rt));
                    std::vector<uint8_t> rr;
                    talk(o, rr);  // collects BR_TRANSACTION_COMPLETE for the reply
                    dispatch(rr, nullptr, nullptr);
                }
                break;
            }
            default:
                fprintf(stderr, "unexpected BR 0x%x\n", cmd);
                return true;
        }
    }
    return done;
}

Reply call(uint32_t handle, uint32_t code, const Parcel& p, uint32_t flags = 0) {
    Out o;
    o.u32(BC_TRANSACTION);
    binder_transaction_data tr = p.txn(handle, code, flags);
    o.raw(&tr, sizeof(tr));
    Reply rep;
    std::vector<uint8_t> r;
    if (!talk(o, r)) return rep;
    if (flags & TF_ONE_WAY) return rep;
    while (!dispatch(r, &rep, nullptr)) {
        if (!talk(Out(), r)) break;
    }
    return rep;
}

bool g_poll = false;  // serve like Android 11's servicemanager: epoll/poll on the binder fd

void serveForever() {
    Out o;
    o.u32(BC_ENTER_LOOPER);
    std::vector<uint8_t> r;
    if (g_poll) {
        writeOnly(o);  // IPCThreadState::setupPolling()
        for (;;) {
            pollfd pf{g_fd, POLLIN, 0};
            if (poll(&pf, 1, 8000) <= 0) {
                printf("poll-timeout\n");
                exit(5);
            }
            if (!talk(Out(), r)) return;  // handlePolledCommands(): work must be there
            dispatch(r, nullptr, nullptr);
        }
    }
    talk(o, r);
    for (;;) {
        dispatch(r, nullptr, nullptr);
        if (!talk(Out(), r)) return;
    }
}

flat_binder_object handleObj(uint32_t h) {
    flat_binder_object o{};
    o.hdr.type = BINDER_TYPE_HANDLE;
    o.handle = h;
    return o;
}

flat_binder_object nodeObj(uint64_t ptr, uint64_t cookie) {
    flat_binder_object o{};
    o.hdr.type = BINDER_TYPE_BINDER;
    o.binder = ptr;
    o.cookie = cookie;
    return o;
}

void refHandle(uint32_t h) {
    Out o;
    o.u32(BC_INCREFS);
    o.u32(h);
    o.u32(BC_ACQUIRE);
    o.u32(h);
    writeOnly(o);
}

bool openBinder() {
    g_fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    if (g_fd < 0) {
        perror("open /dev/binder");
        return false;
    }
    binder_version v{};
    if (ioctl(g_fd, BINDER_VERSION, &v) != 0 || v.protocol_version != BINDER_CURRENT_PROTOCOL_VERSION) {
        fprintf(stderr, "bad version\n");
        return false;
    }
    uint32_t threads = 4;
    ioctl(g_fd, BINDER_SET_MAX_THREADS, &threads);
    if (mmap(nullptr, 128 * 1024, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, g_fd, 0) == MAP_FAILED) {
        perror("mmap");
        return false;
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc < 2 || !openBinder()) return 2;
    std::string role = argv[1];

    if (role == "poll-manager") {
        g_poll = true;
        role = "manager";
    }
    if (role == "manager") {
        if (ioctl(g_fd, BINDER_SET_CONTEXT_MGR, 0) != 0) {
            perror("SET_CONTEXT_MGR");
            return 3;
        }
        uint32_t stored = 0;
        g_handler = [&](const binder_transaction_data& tr, const Reply& in) -> Parcel {
            switch (tr.code) {
                case 1:
                    if (!in.objs.empty() && in.objs[0].hdr.type == BINDER_TYPE_HANDLE) {
                        stored = in.objs[0].handle;
                        refHandle(stored);
                    }
                    return Parcel::bytes("added");
                case 2:
                    return Parcel::object(handleObj(stored));
                case 3:
                    return Parcel::bytes(std::string(in.data.rbegin(), in.data.rend()));
                case 4:
                    printf("manager-quit\n");
                    exit(0);
            }
            return Parcel::bytes("?");
        };
        printf("ready\n");
        serveForever();
        return 0;
    }

    if (role == "server") {
        g_handler = [](const binder_transaction_data& tr, const Reply& in) -> Parcel {
            if (tr.target.ptr != 0x1000 || tr.cookie != 0x2000) return Parcel::bytes("bad-target");
            switch (tr.code) {
                case 10:
                    return Parcel::bytes("pong:" + in.data + ":uid" + std::to_string(tr.sender_euid));
                case 11: {
                    int p[2];
                    if (pipe(p) != 0) return Parcel::bytes("pipe-failed");
                    if (write(p[1], "fd-ok", 5) != 5) return Parcel::bytes("write-failed");
                    close(p[1]);
                    return Parcel::fd(p[0]);  // the fd stays open here; the receiver gets its own
                }
                case 13: {
                    // Call back into the client, which is blocked waiting for *this* reply.
                    if (in.objs.empty()) return Parcel::bytes("no-callback");
                    Reply cb = call(in.objs[0].handle, 20, Parcel::bytes("hi"));
                    return Parcel::bytes("nested:" + cb.data);
                }
                case 99:
                    printf("server-exit\n");
                    exit(0);
            }
            return Parcel::bytes("?");
        };
        Reply r = call(0, 1, Parcel::object(nodeObj(0x1000, 0x2000)));
        printf("registered %s\n", r.data.c_str());
        serveForever();
        return 0;
    }

    if (role == "client") {
        g_handler = [](const binder_transaction_data& tr, const Reply&) -> Parcel {
            if (tr.code == 20 && tr.target.ptr == 0x3000) return Parcel::bytes("cb-ok");
            return Parcel::bytes("cb-bad");
        };
        Reply svc = call(0, 2, Parcel());
        if (svc.objs.empty() || svc.objs[0].hdr.type != BINDER_TYPE_HANDLE) {
            printf("no-service\n");
            return 4;
        }
        uint32_t h = svc.objs[0].handle;
        refHandle(h);
        printf("echo %s\n", call(0, 3, Parcel::bytes("abc")).data.c_str());
        printf("%s\n", call(h, 10, Parcel::bytes("ping")).data.c_str());

        Reply f = call(h, 11, Parcel());
        if (!f.objs.empty()) {
            int fd = static_cast<int>(reinterpret_cast<binder_fd_object*>(&f.objs[0])->fd);
            char buf[16] = {};
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            printf("%s\n", n > 0 ? buf : "fd-read-failed");
            close(fd);
        } else {
            printf("no-fd\n");
        }

        printf("%s\n", call(h, 13, Parcel::object(nodeObj(0x3000, 0x4000))).data.c_str());

        Out o;
        o.u32(BC_REQUEST_DEATH_NOTIFICATION);
        binder_handle_cookie hc{h, 0xdead};
        o.raw(&hc, sizeof(hc));
        writeOnly(o);
        Reply bye = call(h, 99, Parcel());  // the server exits without replying
        printf("%s\n", bye.cmd == BR_DEAD_REPLY ? "dead-reply" : "unexpected-reply");
        std::vector<uint8_t> r;
        while (!g_lastDeath && talk(Out(), r)) dispatch(r, nullptr, nullptr);
        printf("death 0x%llx\n", static_cast<unsigned long long>(g_lastDeath));
        Reply after = call(h, 10, Parcel::bytes("x"));
        printf("%s\n", after.cmd == BR_DEAD_REPLY ? "dead-after" : "alive?");
        call(0, 4, Parcel());
        return 0;
    }
    return 2;
}
