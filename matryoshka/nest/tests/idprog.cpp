// Test helper: executes uid/gid operations given on the command line and prints the results.
//   idprog uid | gid | setuid N | setgid N | setresuid R E S | fork-uid | exec PATH ARGS...
// SPDX-License-Identifier: Apache-2.0
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mount.h>
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
        if (!strcmp(c, "mount-tmpfs")) {
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
