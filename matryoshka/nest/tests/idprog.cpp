// Test helper: executes uid/gid operations given on the command line and prints the results.
//   idprog uid | gid | setuid N | setgid N | setresuid R E S | fork-uid | exec PATH ARGS...
// SPDX-License-Identifier: Apache-2.0
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/capability.h>
#include <signal.h>
#include <cstddef>
#include <sys/mount.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static void printRes(int rc) {
    if (rc == 0) printf("0\n");
    else printf("-1 %s\n", errno == EPERM ? "EPERM" : strerror(errno));
}

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    for (int i = 1; i < argc; i++) {
        const char* c = argv[i];
        if (!strcmp(c, "rlimit-raise")) {
            // ask for far more open files than any kernel allows (init's `rlimit` / `setrlimit`)
            rlimit want{1ull << 40, 1ull << 40};
            printRes(setrlimit(RLIMIT_NOFILE, &want));
        } else if (!strcmp(c, "sockname")) {
            // bind an AF_UNIX socket, then report the names the kernel gives back
            const char* path = argv[++i];
            unlink(path);
            int fd = socket(AF_UNIX, SOCK_STREAM, 0);
            sockaddr_un a{};
            a.sun_family = AF_UNIX;
            strncpy(a.sun_path, path, sizeof(a.sun_path) - 1);
            if (bind(fd, reinterpret_cast<sockaddr*>(&a), sizeof(a)) || listen(fd, 1)) {
                printf("bind-failed %s\n", strerror(errno));
                continue;
            }
            sockaddr_un got{};
            socklen_t len = sizeof(got);
            getsockname(fd, reinterpret_cast<sockaddr*>(&got), &len);
            int c2 = socket(AF_UNIX, SOCK_STREAM, 0);
            connect(c2, reinterpret_cast<sockaddr*>(&a), sizeof(a));
            sockaddr_un peer{};
            socklen_t plen = sizeof(peer);
            getpeername(c2, reinterpret_cast<sockaddr*>(&peer), &plen);
            printf("%s %d %s\n", got.sun_path, static_cast<int>(len) == static_cast<int>(offsetof(sockaddr_un, sun_path) + strlen(path) + 1),
                   peer.sun_path);
            close(c2);
            close(fd);
            unlink(path);
        } else if (!strcmp(c, "mount-fs")) {
            const char* type = argv[++i];
            const char* target = argv[++i];
            printRes(mount(type, target, type, 0, argv[++i]));
        } else if (!strcmp(c, "mkdir")) {
            printRes(mkdir(argv[++i], 0755));
        } else if (!strcmp(c, "write-existing")) {
            // like libprocessgroup: open(O_WRONLY) without O_CREAT
            const char* p = argv[++i];
            int fd = open(p, O_WRONLY | O_CLOEXEC);
            const char* t = argv[++i];
            if (fd < 0) { printf("-1 %s\n", strerror(errno)); continue; }
            printRes(write(fd, t, strlen(t)) == static_cast<ssize_t>(strlen(t)) ? 0 : -1);
            close(fd);
        } else if (!strcmp(c, "pids")) {
            // getpid gettid, and whether this process is reachable as pid 1
            printf("%ld %ld kill1=%d\n", syscall(SYS_getpid), syscall(SYS_gettid), kill(1, 0));
        } else if (!strcmp(c, "child-ppid")) {
            pid_t p = fork();
            if (p == 0) {
                printf("ppid %ld\n", syscall(SYS_getppid));
                fflush(stdout);
                _exit(0);
            }
            waitpid(p, nullptr, 0);
        } else if (!strcmp(c, "proc1-exe")) {
            char buf[4096] = {};
            ssize_t n = readlink("/proc/1/exe", buf, sizeof(buf) - 1);
            printf("%s\n", n > 0 ? (strstr(buf, "idprog") ? "idprog" : buf) : strerror(errno));
        } else if (!strcmp(c, "caps")) {
            // eff prm inh (low 32 bits, hex), then ambient bits 6 and 7
            __user_cap_header_struct h{_LINUX_CAPABILITY_VERSION_3, 0};
            __user_cap_data_struct d[2] = {};
            long rc = syscall(SYS_capget, &h, d);
            if (rc) { printf("capget-failed %s\n", strerror(errno)); continue; }
            printf("%x %x %x amb%d%d\n", d[0].effective, d[0].permitted, d[0].inheritable,
                   static_cast<int>(prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, 6, 0, 0)),
                   static_cast<int>(prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, 7, 0, 0)));
        } else if (!strcmp(c, "capset")) {
            // capset EFF PRM INH (hex, low 32 bits; upper capabilities cleared)
            __user_cap_header_struct h{_LINUX_CAPABILITY_VERSION_3, 0};
            __user_cap_data_struct d[2] = {};
            d[0].effective = static_cast<uint32_t>(strtoul(argv[++i], nullptr, 16));
            d[0].permitted = static_cast<uint32_t>(strtoul(argv[++i], nullptr, 16));
            d[0].inheritable = static_cast<uint32_t>(strtoul(argv[++i], nullptr, 16));
            printRes(static_cast<int>(syscall(SYS_capset, &h, d)));
        } else if (!strcmp(c, "keepcaps")) {
            printRes(prctl(PR_SET_KEEPCAPS, atoi(argv[++i]), 0, 0, 0));
        } else if (!strcmp(c, "ambient-raise")) {
            printRes(prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, atoi(argv[++i]), 0, 0));
        } else if (!strcmp(c, "bset-drop")) {
            printRes(prctl(PR_CAPBSET_DROP, atoi(argv[++i]), 0, 0, 0));
        } else if (!strcmp(c, "bset-read")) {
            printf("%d\n", prctl(PR_CAPBSET_READ, atoi(argv[++i]), 0, 0, 0));
        } else if (!strcmp(c, "statloop")) {
            int n = atoi(argv[++i]);
            const char* p = argv[++i];
            int ok = 0;
            struct stat st;
            for (int k = 0; k < n; k++) ok += stat(p, &st) == 0;
            printf("%d\n", ok);
        } else if (!strcmp(c, "bits")) {
            printf("%d\n", static_cast<int>(sizeof(void*) * 8));
        } else if (!strcmp(c, "mount-tmpfs")) {
            printRes(mount("tmpfs", argv[++i], "tmpfs", 0, nullptr));
        } else if (!strcmp(c, "bind")) {
            const char* src = argv[++i];
            printRes(mount(src, argv[++i], nullptr, MS_BIND, nullptr));
        } else if (!strcmp(c, "umount")) {
            printRes(umount2(argv[++i], 0));
        } else if (!strcmp(c, "write")) {
            const char* p = argv[++i];
            FILE* f = fopen(p, "w");
            if (!f) { printf("write-failed %s\n", strerror(errno)); ++i; continue; }
            fputs(argv[++i], f);
            fclose(f);
        } else if (!strcmp(c, "cat")) {
            FILE* f = fopen(argv[++i], "r");
            if (!f) { printf("cat-failed %s\n", strerror(errno)); continue; }
            char buf[256] = {};
            size_t n = fread(buf, 1, sizeof(buf) - 1, f);
            fclose(f);
            printf("%.*s\n", static_cast<int>(n), buf);
        } else if (!strcmp(c, "errno-rel")) {
            // relative path -> passthrough (traced) branch of the preload wrappers
            struct stat st;
            int r = stat("definitely-missing-file", &st);
            printf("%d %s\n", r, r == 0 ? "ok" : strerror(errno));
        } else if (!strcmp(c, "errno-abs")) {
            struct stat st;
            int r = stat("/definitely/missing/file", &st);
            printf("%d %s\n", r, r == 0 ? "ok" : strerror(errno));
        } else if (!strcmp(c, "argv0")) {
            const char* b = strrchr(argv[0], '/');
            printf("%s\n", b ? b + 1 : argv[0]);
        } else if (!strcmp(c, "uid")) {
            uid_t r, e, s;
            getresuid(&r, &e, &s);
            printf("%u %u %u\n", r, e, s);
        } else if (!strcmp(c, "gid")) {
            gid_t r, e, s;
            getresgid(&r, &e, &s);
            printf("%u %u %u\n", r, e, s);
        } else if (!strcmp(c, "setuid")) {
            printRes(setuid(static_cast<uid_t>(atoi(argv[++i]))));
        } else if (!strcmp(c, "setgid")) {
            printRes(setgid(static_cast<gid_t>(atoi(argv[++i]))));
        } else if (!strcmp(c, "setresuid")) {
            uid_t r = atoi(argv[i + 1]), e = atoi(argv[i + 2]), s = atoi(argv[i + 3]);
            i += 3;
            printRes(setresuid(r, e, s));
        } else if (!strcmp(c, "fork-uid")) {
            pid_t p = fork();
            if (p == 0) {
                printf("child %u %u\n", getuid(), geteuid());
                _exit(0);
            }
            waitpid(p, nullptr, 0);
        } else if (!strcmp(c, "exec")) {
            execv(argv[i + 1], argv + i + 1);
            perror("execv");
            return 1;
        }
    }
    return 0;
}
