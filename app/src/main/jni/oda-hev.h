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
#undef MSG_MORE
#define MSG_MORE 0
/* Метка в библиотеке: CI проверяет, что заголовок подключился (.github/workflows/build.yml).
   weak — одна на все файлы; экспортируемый символ компоновщик не выбрасывает */
__attribute__((weak, used)) const char oda_hev_mark[] = "oda-hev: MSG_MORE=0";
#endif
#endif
