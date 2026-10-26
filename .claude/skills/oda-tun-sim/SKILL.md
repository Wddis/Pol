---
name: oda-tun-sim
description: Запустить имитацию телефона ODA с обходом DPI (scripts/test-tun.sh) — весь трафик «телефона» идёт через VPN-интерфейс tun0 → tun2socks (hev-socks5-tunnel) → ByeDPI, проверяются DNS через SOCKS-UDP, обычные сайты, NetProbe, сторож, устойчивость к потоку DNS, соцсети как у приложения (список доменов), блокировка по адресу (CDN, Telegram) и запасной путь через «Psiphon» (HelperRelay). Использовать после изменений в Tun2SocksConfig, BypassProfile, BypassController, NetProbe или VpnTunnelService, перед пушем, и когда нужно проверить, что обход DPI несёт трафик телефона, а не только проходит проверку ODA.
---

# Имитация телефона ODA (обход DPI)

Стенд уже есть в репозитории; этот скилл — как его запустить, прочитать результат и расширять.
Отвечать пользователю по-русски.

## Что моделируется

Три сетевых пространства (netns), всё на одной машине Linux:
- **phone** — «телефон»: `tun0` от hev-socks5-tunnel, весь трафик по умолчанию — в tun0.
  ByeDPI и проверки ODA работают от uid 2000 и идут мимо VPN (как `addDisallowedApplication`).
  «Psiphon» стенда (SOCKS5, `lab.py socks`) работает от uid 2001 и ходит в «интернет» мимо оператора
  по отдельному каналу p1/s1 (как зашифрованный туннель за границу).
- **isp** — «оператор»: правило iptables `string` отбрасывает TCP:443-пакет, в котором имя заблокированного
  сайта лежит целиком (если модуля нет — стенд без него); у «CDN соцсети» (157.240.200.36) и «сервера
  Telegram» (149.154.167.51) режутся все пакеты с данными — блокировка по адресу.
- **srv** — «интернет»: DNS 1.1.1.1 (UDP и TCP), HTTPS-сайты с адресами из журналов пользователя
  (и i.instagram.com, scontent-hel3-1.cdninstagram.com), «сервер Telegram» без TLS (эхо).

Конфиг hev, маршрут ByeDPI и запасной путь берутся **из кода приложения** (`Tun2SocksConfig.yaml`,
`BypassProfile.processArgs` + `BypassProfile.route` для цепочки «split SNI → сильная», `HelperRelay`,
`ByeDpiStats`) через `app/src/test/java/com/example/vpnapp/TunLab.java`; проверка — настоящий `NetProbe`.
Сильная стратегия стенда — `multidisorder` (его DPI ищет имя целиком в одном пакете; переменная
`LAB_STRONG`): стенд проверяет устройство маршрута, а не то, какая стратегия бьёт настоящий ТСПУ.

Чего стенд НЕ заменяет: реальную сеть РФ. Соцсети и Telegram проверяются только на телефоне
пользователя по журналу («ЖУРНАЛ» → «КОПИРОВАТЬ»).

## Запуск

```bash
git submodule update --init --recursive          # byedpi и hev-socks5-tunnel
python3 -m pip install pyroute2                  # в CI: sudo python3 -m pip install --break-system-packages pyroute2
sudo bash scripts/test-tun.sh                    # ~5 минут; без root сам перезапустится через sudo
```

Нужны: root, gcc, make, JDK (javac/java), python3 + pyroute2, iptables, openssl, curl,
util-linux (nsenter, unshare, setpriv). Длинный прогон запускать в фоне и ждать завершения.

В CI это шаг «Simulate phone traffic through tun2socks and ByeDPI» в `.github/workflows/build.yml`.
Для веток не `main`/`claude/**` workflow запускается вручную (workflow_dispatch, `build.yml`, ref = ветка).

## Как читать результат

Режим «Обход DPI» (без запасного пути):
1. **Сайты через VPN** — google (204), cloudflare, ya, vk, gosuslugi (200) через tun0; DNS телефона идёт
   через SOCKS-UDP ByeDPI. Должны открыться все.
2. **NetProbe мимо VPN** — «DNS через SOCKS-UDP ByeDPI: отвечает», обычные сайты 6/6, «Сторож: DNS +, сайт +».
3. **Нагрузка как у телефона** — 15 потоков DNS (каждый запрос с нового порта = новая UDP-ассоциация
   в ByeDPI) 40 с, каждые 5 с открывается vk.com. Должно быть «8 из 8» и «pool is full: 0».
4. **Соцсети как у приложения** — www.instagram.com, i.instagram.com, www.facebook.com: 200 быстрее 3 с
   (список доменов → сразу сильная, без тайм-аута 4 с мягкой).
5. **Закрытое по адресу** — CDN и Telegram не открываются (так и должно быть без запасного пути);
   «почему»: для CDN «…по адресу», для 57.144.62.34 «…по имени»; сводка ByeDPI: «список → multidisorder»,
   «не открылся: scontent-hel3-1.cdninstagram.com 157.240.200.36» и «149.154.167.51 (Telegram)».

Режим «Авто» (HelperRelay → «Psiphon»):
6. **Путь закрыт** — ya, google, i.instagram.com открываются; Telegram — нет.
7. **Путь открыт** — Telegram «ok», CDN 200 (первый раз ~4–5 с: сильная не прошла → Psiphon; второй —
   сразу, адрес запомнен), обычные сайты и соцсети напрямую; NetProbe проходит; сводка: «Telegram → Psiphon»,
   «список → Psiphon».

Контроль — старый запуск ByeDPI 0.8.5–0.8.6 (без `-c`, без списка доменов; hev без `udp-read-write-timeout`):
8. i.instagram.com сразу не открывается (тайм-аут мягкой; на стенде повтор multidisorder бывает и неудачным), а под потоком DNS интернет пропадает
   (`000`, рост «pool is full»). Если не воспроизвелось — предупреждение, не провал.

Итог: «Имитация телефона с обходом DPI: OK» или «ОШИБКА» с хвостами журналов ByeDPI и hev.

## Особенности окружения

- **Нет IPv6 в ядре** (облачные контейнеры с `ipv6.disable=1`): hev всегда открывает сокеты AF_INET6,
  поэтому стенд сам собирает `scripts/tunlab/v4shim.c` (LD_PRELOAD, AF_INET6 → AF_INET) и убирает
  `ipv6:` из конфига hev. ByeDPI без IPv6 справляется сам.
- **У «оператора» нет IPv6**, поэтому, как и приложение с 0.8.6, стенд не направляет IPv6 в VPN
  (нет маршрута `::/0` и IPv6-DNS).
- Файлы стенда: `scripts/test-tun.sh` (сценарий), `scripts/tunlab/lab.py` (netns, «интернет»,
  нагрузка DNS, «Psiphon» `socks`, клиент Telegram `tgcheck`), `scripts/tunlab/v4shim.c`.
- Не правьте `test-tun.sh` во время прогона: bash читает скрипт по ходу и споткнётся о сдвинутые строки. Временная папка — `mktemp -d`, удаляется при выходе.
- Если прогон прерван и netns остались: `sudo python3 scripts/tunlab/lab.py down`.

## Расширение

- Новый сайт «интернета»: добавить имя и IPv4 в `SITES` в `lab.py` (сертификат и DNS подхватят сами).
- Новая проверка: функция в `test-tun.sh` + `check <0|1> "текст"`; контрольные сценарии,
  которые должны падать на старом коде, оформлять как в п. 4 (предупреждение, не провал).
- Менять поведение приложения — в коде приложения (`Tun2SocksConfig`, `BypassProfile`, `NetProbe`),
  а не в стенде: стенд берёт значения оттуда.
- После изменений: `bash scripts/test-bypass.sh` (логика стратегий и строки ByeDPI) и этот стенд;
  README («Как собрать», «Этапы») держать в актуальном состоянии.
