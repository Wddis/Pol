// SPDX-License-Identifier: Apache-2.0
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include "test.h"
#include "vfs.h"

using nest::Vfs;

static std::string tmpdir() {
    char tpl[] = "/tmp/nestvfsXXXXXX";
    return mkdtemp(tpl);
}

TEST(vfs_normalize) {
    EXPECT_EQ(Vfs::normalize("/"), "/");
    EXPECT_EQ(Vfs::normalize("//a///b/"), "/a/b");
    EXPECT_EQ(Vfs::normalize("/a/./b/../c"), "/a/c");
    EXPECT_EQ(Vfs::normalize("/../../x"), "/x");
    EXPECT_EQ(Vfs::join("/a/b", "../c"), "/a/c");
    EXPECT_EQ(Vfs::join("/a/b", "/abs"), "/abs");
}

TEST(vfs_longest_prefix_wins) {
    Vfs v;
    v.bind("/", "/r");
    v.bind("/data", "/d");
    v.bind("/data/media", "/m");
    EXPECT_EQ(v.mapLexical("/system/bin/sh"), "/r/system/bin/sh");
    EXPECT_EQ(v.mapLexical("/data"), "/d");
    EXPECT_EQ(v.mapLexical("/data/app/x"), "/d/app/x");
    EXPECT_EQ(v.mapLexical("/data/media/0"), "/m/0");
    EXPECT_EQ(v.mapLexical("/datax"), "/r/datax");  // prefix must end at a component boundary
}

TEST(vfs_identity_binds) {
    Vfs v;
    v.bind("/", "/r");
    v.bind("/proc", "/proc");
    EXPECT_EQ(v.mapLexical("/proc/self/exe"), "/proc/self/exe");
}

TEST(vfs_rebind_replaces) {
    Vfs v;
    v.bind("/", "/a");
    v.bind("/", "/b");
    EXPECT_EQ(v.mounts().size(), 1u);
    EXPECT_EQ(v.mapLexical("/x"), "/b/x");
    v.bind("/m", "/mm");
    EXPECT_TRUE(v.unbind("/m"));
    EXPECT_EQ(v.mapLexical("/m/x"), "/b/m/x");
}

TEST(vfs_reverse) {
    Vfs v;
    v.bind("/", "/r");
    v.bind("/data", "/d");
    v.bind("/proc", "/proc");
    std::string g;
    EXPECT_TRUE(v.reverse("/r/system/bin", g));
    EXPECT_EQ(g, "/system/bin");
    EXPECT_TRUE(v.reverse("/d/local/tmp", g));
    EXPECT_EQ(g, "/data/local/tmp");
    EXPECT_TRUE(v.reverse("/r", g));
    EXPECT_EQ(g, "/");
    EXPECT_TRUE(!v.reverse("/elsewhere", g));
}

TEST(vfs_resolve_symlinks_stay_inside_guest) {
    std::string root = tmpdir();
    mkdir((root + "/system").c_str(), 0755);
    mkdir((root + "/system/bin").c_str(), 0755);
    // Absolute guest symlink: must resolve inside the rootfs, not on the host.
    (void)!symlink("/system/bin/toybox", (root + "/system/bin/ls").c_str());
    (void)!symlink("toybox", (root + "/system/bin/cat").c_str());
    (void)!symlink("/system/bin", (root + "/bin").c_str());
    FILE* f = fopen((root + "/system/bin/toybox").c_str(), "w");
    fclose(f);

    Vfs v;
    v.bind("/", root);
    std::string host, guest;
    EXPECT_TRUE(v.resolve("/system/bin/ls", "/", true, host, &guest));
    EXPECT_EQ(host, root + "/system/bin/toybox");
    EXPECT_EQ(guest, "/system/bin/toybox");

    EXPECT_TRUE(v.resolve("/system/bin/ls", "/", false, host));
    EXPECT_EQ(host, root + "/system/bin/ls");  // lstat semantics

    EXPECT_TRUE(v.resolve("/bin/cat", "/", true, host));
    EXPECT_EQ(host, root + "/system/bin/toybox");

    EXPECT_TRUE(v.resolve("cat", "/bin", true, host));  // relative to a guest cwd that is a symlink
    EXPECT_EQ(host, root + "/system/bin/toybox");

    EXPECT_TRUE(v.resolve("/new/dir/file", "/", true, host));  // missing components kept
    EXPECT_EQ(host, root + "/new/dir/file");

    EXPECT_TRUE(v.resolve("/../../system", "/", true, host));  // cannot escape the guest root
    EXPECT_EQ(host, root + "/system");
}

TEST(vfs_resolve_loop) {
    std::string root = tmpdir();
    (void)!symlink("/b", (root + "/a").c_str());
    (void)!symlink("/a", (root + "/b").c_str());
    Vfs v;
    v.bind("/", root);
    std::string host;
    EXPECT_TRUE(!v.resolve("/a/x", "/", true, host));
}

TEST(vfs_resolve_across_binds) {
    std::string root = tmpdir(), data = tmpdir();
    (void)!symlink("/data/media/0", (root + "/sdcard").c_str());
    mkdir((data + "/media").c_str(), 0755);
    mkdir((data + "/media/0").c_str(), 0755);
    Vfs v;
    v.bind("/", root);
    v.bind("/data", data);
    std::string host;
    EXPECT_TRUE(v.resolve("/sdcard/Download", "/", true, host));
    EXPECT_EQ(host, data + "/media/0/Download");
}

TEST(vfs_serialize_roundtrip) {
    Vfs v;
    v.bind("/", "/r");
    v.bind("/data", "/d", true);
    Vfs w;
    EXPECT_TRUE(w.deserialize(v.serialize()));
    EXPECT_EQ(w.mounts().size(), 2u);
    EXPECT_EQ(w.mapLexical("/data/x"), "/d/x");
    EXPECT_TRUE(w.mounts()[0].readOnly);
}

TEST(vfs_reverse_prefers_canonical_guest_path) {
    Vfs v;
    v.bind("/", "/r");
    v.bind("/r", "/r");  // identity alias of the same host directory
    std::string g;
    EXPECT_TRUE(v.reverse("/r/system/bin/sh", g));
    EXPECT_EQ(g, "/system/bin/sh");
}

// Host /proc magic links (fd/N, exe, cwd) must be left to the kernel, never followed as guest
// symlinks: bionic implements fchmodat(AT_SYMLINK_NOFOLLOW) via chmod("/proc/self/fd/N").
TEST(vfs_does_not_follow_proc_magic_links) {
    Vfs v;
    v.bind("/", tmpdir());
    v.bind("/proc", "/proc");
    std::string host;
    EXPECT_TRUE(v.resolve("/proc/self/exe", "/", true, host));
    EXPECT_EQ(host, "/proc/self/exe");
}

#include "guest_log.h"
TEST(guest_log_formats_liblog_packet) {
    unsigned char pkt[64] = {};
    pkt[0] = 0;               // main log
    pkt[1] = 42; pkt[2] = 0;  // tid 42
    size_t n = 11;
    pkt[n++] = 6;             // ERROR
    memcpy(pkt + n, "apexd\0boom\n", 11);
    n += 11;
    EXPECT_EQ(nest::formatLogdPacket(pkt, n), "E/apexd(42): boom\n");
}

#include <sys/socket.h>
#include <sys/un.h>
#include <fstream>
#include <sstream>
TEST(guest_log_tee_keeps_copy_and_forwards_to_logd) {
    char dir[] = "/tmp/nest-tee-XXXXXX";
    EXPECT_TRUE(mkdtemp(dir) != nullptr);
    std::string d = dir, logd = d + "/logdw", tee = d + "/tee", out = d + "/logcat.txt";
    auto addr = [](const std::string& p) {
        sockaddr_un a{};
        a.sun_family = AF_UNIX;
        memcpy(a.sun_path, p.c_str(), p.size() + 1);
        return a;
    };
    int logdFd = socket(AF_UNIX, SOCK_DGRAM, 0);
    sockaddr_un la = addr(logd);
    EXPECT_EQ(bind(logdFd, reinterpret_cast<sockaddr*>(&la), sizeof(la)), 0);
    nest::GuestLog g;
    EXPECT_TRUE(g.start(tee, out, logd));
    unsigned char pkt[32] = {};
    pkt[1] = 7;
    size_t n = 11;
    pkt[n++] = 4;  // INFO
    memcpy(pkt + n, "lmkd\0hi", 8);
    n += 8;
    int c = socket(AF_UNIX, SOCK_DGRAM, 0);
    sockaddr_un ta = addr(tee);
    EXPECT_EQ(connect(c, reinterpret_cast<sockaddr*>(&ta), sizeof(ta)), 0);
    EXPECT_EQ(send(c, pkt, n, 0), static_cast<ssize_t>(n));
    unsigned char got[64];
    timeval tv{2, 0};
    setsockopt(logdFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    EXPECT_EQ(recv(logdFd, got, sizeof(got), 0), static_cast<ssize_t>(n));
    EXPECT_EQ(memcmp(got, pkt, n), 0);
    g.stop();
    std::ifstream f(out);
    std::stringstream ss;
    ss << f.rdbuf();
    EXPECT_EQ(ss.str(), "I/lmkd(7): hi\n");
    close(c);
    close(logdFd);
}

TEST(vfs_mount_over_parent_hides_submounts_but_not_sticky) {
    nest::Vfs v;
    v.bind("/", "/r");
    v.bind("/apex/com.a", "/r/system/apex/com.a");
    v.bind("/apex/lib.so", "/state/preload/x", true, true);
    v.bind("/apexes", "/other");
    EXPECT_EQ(v.hideUnder("/apex"), 1u);
    v.bind("/apex", "/state/mnt/1");
    EXPECT_EQ(v.mapLexical("/apex/com.a/x"), "/state/mnt/1/com.a/x");
    EXPECT_EQ(v.mapLexical("/apex/lib.so"), "/state/preload/x");
    EXPECT_EQ(v.mapLexical("/apexes/y"), "/other/y");
}

#include "bpf_emu.h"
TEST(bpf_store_map_semantics) {
    nest::BpfStore st(4);
    nest::BpfObject h;
    h.type = nest::bpfc::HASH; h.keySize = 4; h.valueSize = 4; h.maxEntries = 1;
    int id = st.create(h);
    std::vector<uint8_t> k1{1, 0, 0, 0}, k2{2, 0, 0, 0}, v{5, 0, 0, 0}, out;
    EXPECT_EQ(st.update(id, k1, v, nest::bpfc::EXIST), -ENOENT);
    EXPECT_EQ(st.update(id, k1, v, nest::bpfc::ANY), 0);
    EXPECT_EQ(st.update(id, k2, v, nest::bpfc::ANY), -E2BIG);
    EXPECT_EQ(st.update(id, k1, std::vector<uint8_t>{1, 2}, 0), -EINVAL);
    nest::BpfObject pc;
    pc.type = nest::bpfc::PERCPU_ARRAY; pc.keySize = 4; pc.valueSize = 4; pc.maxEntries = 2;
    int p = st.create(pc);
    EXPECT_EQ(st.userValueSize(p), 32u);  // 8-byte slot per CPU
    EXPECT_EQ(st.lookup(p, k1, out), 0);
    EXPECT_EQ(out.size(), 32u);
    EXPECT_EQ(st.remove(p, k1), -EINVAL);
    EXPECT_EQ(st.lookup(p, k2, out), -ENOENT);  // index 2 >= max_entries
    nest::BpfObject bad;
    bad.type = nest::bpfc::ARRAY; bad.keySize = 8; bad.valueSize = 4; bad.maxEntries = 1;
    EXPECT_EQ(st.create(bad), -EINVAL);
}

#include "netlink_emu.h"
TEST(netlink_emu_acks_and_ends_dumps) {
    struct H { uint32_t len; uint16_t type, flags; uint32_t seq, pid; };
    H msgs[2] = {{16, 32, 1 | 4, 5, 0}, {16, 18, 1 | 0x300, 6, 0}};
    auto out = nest::netlinkReplies(0, reinterpret_cast<const uint8_t*>(msgs), sizeof(msgs));
    EXPECT_EQ(out.size(), 36u + 20u);
    H a, d;
    memcpy(&a, out.data(), 16);
    memcpy(&d, out.data() + 36, 16);
    EXPECT_EQ(a.type, 2);
    EXPECT_EQ(a.seq, 5u);
    EXPECT_EQ(d.type, 3);
    EXPECT_EQ(d.seq, 6u);
    H g = {16, 0x10, 1, 9, 0};  // generic netlink family lookup: not found
    auto ge = nest::netlinkReplies(16, reinterpret_cast<const uint8_t*>(&g), sizeof(g));
    int err;
    memcpy(&err, ge.data() + 16, 4);
    EXPECT_EQ(err, -ENOENT);
}
