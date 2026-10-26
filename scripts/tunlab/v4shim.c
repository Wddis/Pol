// LD_PRELOAD: сокеты AF_INET6 (dual-stack) → AF_INET в ядре без IPv6. Адреса v4-mapped ::ffff:a.b.c.d
// переводятся туда и обратно, поэтому программа «думает», что работает с IPv6-сокетом, как на Android.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static unsigned char v6fd[65536];
#define REAL(name) static __typeof__(name) *real_##name; if (!real_##name) real_##name = dlsym(RTLD_NEXT, #name)

static int to4(const struct sockaddr *a, socklen_t l, struct sockaddr_in *o) {
    if (!a || a->sa_family != AF_INET6 || l < sizeof(struct sockaddr_in6)) return 0;
    const struct sockaddr_in6 *s = (const void *)a;
    memset(o, 0, sizeof(*o));
    o->sin_family = AF_INET;
    o->sin_port = s->sin6_port;
    if (IN6_IS_ADDR_V4MAPPED(&s->sin6_addr)) memcpy(&o->sin_addr, s->sin6_addr.s6_addr + 12, 4);
    else if (IN6_IS_ADDR_UNSPECIFIED(&s->sin6_addr)) o->sin_addr.s_addr = 0;
    else { errno = ENETUNREACH; return -1; }   // настоящий IPv6: сети нет (как мобильный без IPv6)
    return 1;
}
static void to6(struct sockaddr *a, socklen_t *l, socklen_t cap) {
    if (!a || !l || a->sa_family != AF_INET || cap < sizeof(struct sockaddr_in6)) return;
    struct sockaddr_in s = *(struct sockaddr_in *)a;
    struct sockaddr_in6 *o = (void *)a;
    memset(o, 0, sizeof(*o));
    o->sin6_family = AF_INET6;
    o->sin6_port = s.sin_port;
    o->sin6_addr.s6_addr[10] = o->sin6_addr.s6_addr[11] = 0xff;
    memcpy(o->sin6_addr.s6_addr + 12, &s.sin_addr, 4);
    *l = sizeof(*o);
}
static int is6(int fd) { return fd >= 0 && fd < 65536 && v6fd[fd]; }

int socket(int d, int t, int p) {
    REAL(socket);
    if (d != AF_INET6) return real_socket(d, t, p);
    int fd = real_socket(AF_INET, t, p);
    if (fd >= 0 && fd < 65536) v6fd[fd] = 1;
    return fd;
}
int close(int fd) { REAL(close); if (fd >= 0 && fd < 65536) v6fd[fd] = 0; return real_close(fd); }
int setsockopt(int fd, int lv, int on, const void *v, socklen_t l) {
    REAL(setsockopt);
    if (is6(fd) && lv == IPPROTO_IPV6) {
        if (on == IPV6_UNICAST_HOPS) return real_setsockopt(fd, IPPROTO_IP, IP_TTL, v, l);
        if (on == IPV6_TCLASS) return real_setsockopt(fd, IPPROTO_IP, IP_TOS, v, l);
        return 0;
    }
    return real_setsockopt(fd, lv, on, v, l);
}
int getsockopt(int fd, int lv, int on, void *v, socklen_t *l) {
    REAL(getsockopt);
    if (is6(fd) && lv == IPPROTO_IPV6) {
        if (on == IPV6_UNICAST_HOPS) return real_getsockopt(fd, IPPROTO_IP, IP_TTL, v, l);
        if (l && *l >= sizeof(int)) { *(int *)v = 0; *l = sizeof(int); }
        return 0;
    }
    return real_getsockopt(fd, lv, on, v, l);
}
#define IN_ADDR(fn, ...) \
    struct sockaddr_in a4; int c = is6(fd) ? to4(a, l, &a4) : 0; \
    if (c < 0) return -1; \
    if (c) { a = (const struct sockaddr *)&a4; l = sizeof(a4); }
int connect(int fd, const struct sockaddr *a, socklen_t l) { REAL(connect); IN_ADDR(); return real_connect(fd, a, l); }
int bind(int fd, const struct sockaddr *a, socklen_t l) { REAL(bind); IN_ADDR(); return real_bind(fd, a, l); }
ssize_t sendto(int fd, const void *b, size_t n, int f, const struct sockaddr *a, socklen_t l) {
    REAL(sendto); IN_ADDR(); return real_sendto(fd, b, n, f, a, l);
}
ssize_t sendmsg(int fd, const struct msghdr *m, int f) {
    REAL(sendmsg);
    if (is6(fd) && m->msg_name) {
        struct msghdr c = *m; struct sockaddr_in a4;
        int r = to4(m->msg_name, m->msg_namelen, &a4);
        if (r < 0) return -1;
        if (r) { c.msg_name = &a4; c.msg_namelen = sizeof(a4); }
        return real_sendmsg(fd, &c, f);
    }
    return real_sendmsg(fd, m, f);
}
int sendmmsg(int fd, struct mmsghdr *v, unsigned int n, int f) {
    unsigned int i; for (i = 0; i < n; i++) { ssize_t r = sendmsg(fd, &v[i].msg_hdr, f); if (r < 0) return i ? (int)i : -1; v[i].msg_len = r; }
    return n;
}
#define OUT_ADDR(call) socklen_t cap = l ? *l : 0; __typeof__(call) r = call; if (r >= 0 && is6(fd)) to6(a, l, cap); return r;
ssize_t recvfrom(int fd, void *b, size_t n, int f, struct sockaddr *a, socklen_t *l) { REAL(recvfrom); OUT_ADDR(real_recvfrom(fd, b, n, f, a, l)); }
int getsockname(int fd, struct sockaddr *a, socklen_t *l) { REAL(getsockname); OUT_ADDR(real_getsockname(fd, a, l)); }
int getpeername(int fd, struct sockaddr *a, socklen_t *l) { REAL(getpeername); OUT_ADDR(real_getpeername(fd, a, l)); }
ssize_t recvmsg(int fd, struct msghdr *m, int f) {
    REAL(recvmsg); socklen_t cap = m->msg_namelen; ssize_t r = real_recvmsg(fd, m, f);
    if (r >= 0 && is6(fd) && m->msg_name) to6(m->msg_name, &m->msg_namelen, cap);
    return r;
}
int recvmmsg(int fd, struct mmsghdr *v, unsigned int n, int f, struct timespec *t) {
    (void)t; unsigned int i;
    for (i = 0; i < n; i++) { ssize_t r = recvmsg(fd, &v[i].msg_hdr, i ? (f | MSG_DONTWAIT) : f); if (r < 0) return i ? (int)i : -1; v[i].msg_len = r; }
    return n;
}
int accept4(int fd, struct sockaddr *a, socklen_t *l, int fl) {
    REAL(accept4); socklen_t cap = l ? *l : 0; int r = real_accept4(fd, a, l, fl);
    if (r >= 0 && is6(fd)) { if (r < 65536) v6fd[r] = 1; to6(a, l, cap); }
    return r;
}
int accept(int fd, struct sockaddr *a, socklen_t *l) { return accept4(fd, a, l, 0); }
