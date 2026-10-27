/*
 * Подключается ко всем файлам tun2socks (-include, см. Application.mk и scripts/test-tun.sh).
 *
 * hev-socks5-tunnel без pipeline шлёт приветствие SOCKS5 («05 01 00») с MSG_MORE и ждёт ответа
 * сервера. MSG_MORE просит ядро придержать данные до следующей записи, а её нет — ядро отправляет
 * их только по таймеру, через 200 мс. Так каждое соединение телефона и каждый DNS-запрос ждали
 * лишние 0,2 с (и через ByeDPI, и через Psiphon/Tor). pipeline включать нельзя: ByeDPI принимает
 * приветствие только отдельным пакетом. Поэтому в файлах hev (у них есть hev-socks5.h) MSG_MORE — 0.
 */
#if defined(__has_include)
#if __has_include(<hev-socks5.h>)
/* как в hev-socks5-udp.c: иначе sys/socket.h, подключённый здесь первым, не объявит sendmmsg */
#define _GNU_SOURCE
#include <sys/socket.h>
#include <netinet/in.h>
#undef MSG_MORE
#define MSG_MORE 0

/*
 * TCP_NODELAY на соединениях hev к SOCKS (ByeDPI, Psiphon, Tor). Без него hev держит каждую следующую
 * маленькую запись приложения, пока SOCKS-сервер не подтвердит предыдущую (алгоритм Нейгла), а тот
 * подтверждает с задержкой до 40 мс — HTTP/2, TLS и мессенджеры, которые шлют несколько мелких записей
 * подряд, ждали лишние ~40 мс на каждый ответ (стенд: замер smallwrites, 44 мс → ~6 мс).
 * Каждый сокет hev проходит через hev_socks5_socket(), где для него вызывается setsockopt(IPV6_V6ONLY):
 * там и включаем TCP_NODELAY для потоковых. Прототип setsockopt уже объявлен выше, поэтому макрос
 * меняет только вызовы.
 */
static inline int
oda_setsockopt (int fd, int level, int name, const void *val, socklen_t len)
{
    int res = setsockopt (fd, level, name, val, len);
    if (level == IPPROTO_IPV6 && name == IPV6_V6ONLY) {
        int type = 0, one = 1;
        socklen_t tlen = sizeof (type);
        if (getsockopt (fd, SOL_SOCKET, SO_TYPE, &type, &tlen) == 0 && type == SOCK_STREAM)
            setsockopt (fd, IPPROTO_TCP, 1 /* TCP_NODELAY: netinet/tcp.h здесь нельзя — его TCP_MSS спорит с lwIP */,
                        &one, sizeof (one));
    }
    return res;
}
#define setsockopt oda_setsockopt
/* Метка в библиотеке: CI проверяет, что заголовок подключился (.github/workflows/build.yml).
   weak — одна на все файлы; экспортируемый символ компоновщик не выбрасывает */
__attribute__((weak, used)) const char oda_hev_mark[] = "oda-hev: MSG_MORE=0 NODELAY=1";
#endif
#endif
