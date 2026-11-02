// SPDX-License-Identifier: Apache-2.0
#include <cstdlib>
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
