---
name: oda-tun-sim
description: Запустить имитацию телефона ODA с обходом DPI (scripts/test-tun.sh) — весь трафик «телефона» идёт через VPN-интерфейс tun0 → tun2socks (hev-socks5-tunnel) → ByeDPI, проверяются DNS через SOCKS-UDP, обычные сайты, NetProbe, сторож и устойчивость к потоку DNS. Использовать после изменений в Tun2SocksConfig, BypassProfile, BypassController, NetProbe или VpnTunnelService, перед пушем, и когда нужно проверить, что обход DPI несёт трафик телефона, а не только проходит проверку ODA.
---

# Имитация телефона ODA (обход DPI)

Стенд уже есть в репозитории; этот скилл — как его запустить, прочитать результат и расширять.
Отвечать пользователю по-русски.

## Что моделируется

Три сетевых пространства (netns), всё на одной машине Linux:
- **phone** — «телефон»: `tun0` от hev-socks5-tunnel, весь трафик по умолчанию — в tun0.
  ByeDPI и проверки ODA работают от uid 2000 и идут мимо VPN (как `addDisallowedApplication`).
- **isp** — «оператор»: маршрутизатор; простое правило iptables `string` отбрасывает TCP:443-пакет,
  в котором имя заблокированного сайта лежит целиком (если модуля нет — стенд без него).
- **srv** — «интернет»: DNS 1.1.1.1 (UDP и TCP) и HTTPS-сайты с адресами из журналов пользователя.

Конфиг hev и аргументы ByeDPI берутся **из кода приложения** (`Tun2SocksConfig.yaml`,
`BypassProfile.processArgs`, цепочка `BypassProfile.chain(MILD[0], STRONG[1])`) через
`app/src/test/java/com/example/vpnapp/TunLab.java`; проверка — настоящий `NetProbe`.

Чего стенд НЕ заменяет: реальную сеть РФ. Соцсети и Telegram проверяются только на телефоне
пользователя по журналу («ЖУРНАЛ» → «КОПИРОВАТЬ»).

## Запуск

```bash
git submodule update --init --recursive          # byedpi и hev-socks5-tunnel
python3 -m pip install pyroute2                  # в CI: sudo python3 -m pip install --break-system-packages pyroute2
sudo bash scripts/test-tun.sh                    # ~3 минуты; без root сам перезапустится через sudo
```

Нужны: root, gcc, make, JDK (javac/java), python3 + pyroute2, iptables, openssl, curl,
util-linux (nsenter, unshare, setpriv). Длинный прогон запускать в фоне и ждать завершения.

В CI это шаг «Simulate phone traffic through tun2socks and ByeDPI» в `.github/workflows/build.yml`.
Для веток не `main`/`claude/**` workflow запускается вручную (workflow_dispatch, `build.yml`, ref = ветка).

## Как читать результат

1. **Сайты через VPN** — google (204), cloudflare, ya, vk, gosuslugi (200) через tun0; DNS телефона идёт
   через SOCKS-UDP ByeDPI. Должны открыться все.
2. **NetProbe мимо VPN** — «DNS через SOCKS-UDP ByeDPI: отвечает», обычные сайты 6/6, «Сторож: DNS +, сайт +».
3. **Нагрузка как у телефона** — 15 потоков DNS (каждый запрос с нового порта = новая UDP-ассоциация
   в ByeDPI) 40 с, каждые 5 с открывается vk.com. Должно быть «8 из 8» и «pool is full: 0».
4. **Контроль** — та же нагрузка со старым конфигом 0.8.5 (ByeDPI без `-c`, hev без
   `udp-read-write-timeout`). Ожидается, что интернет пропадает (`000`, рост «pool is full»): значит,
   тест ловит ошибку 0.8.5. Если не пропал — предупреждение, не провал.

Итог: «Имитация телефона с обходом DPI: OK» или «ОШИБКА» с хвостами журналов ByeDPI и hev.

## Особенности окружения

- **Нет IPv6 в ядре** (облачные контейнеры с `ipv6.disable=1`): hev всегда открывает сокеты AF_INET6,
  поэтому стенд сам собирает `scripts/tunlab/v4shim.c` (LD_PRELOAD, AF_INET6 → AF_INET) и убирает
  `ipv6:` из конфига hev. ByeDPI без IPv6 справляется сам.
- **У «оператора» нет IPv6**, поэтому, как и приложение с 0.8.6, стенд не направляет IPv6 в VPN
  (нет маршрута `::/0` и IPv6-DNS).
- Файлы стенда: `scripts/test-tun.sh` (сценарий), `scripts/tunlab/lab.py` (netns, «интернет»,
  нагрузка DNS), `scripts/tunlab/v4shim.c`. Временная папка — `mktemp -d`, удаляется при выходе.
- Если прогон прерван и netns остались: `sudo python3 scripts/tunlab/lab.py down`.

## Расширение

- Новый сайт «интернета»: добавить имя и IPv4 в `SITES` в `lab.py` (сертификат и DNS подхватят сами).
- Новая проверка: функция в `test-tun.sh` + `check <0|1> "текст"`; контрольные сценарии,
  которые должны падать на старом коде, оформлять как в п. 4 (предупреждение, не провал).
- Менять поведение приложения — в коде приложения (`Tun2SocksConfig`, `BypassProfile`, `NetProbe`),
  а не в стенде: стенд берёт значения оттуда.
- После изменений: `bash scripts/test-bypass.sh` (логика стратегий и строки ByeDPI) и этот стенд;
  README («Как собрать», «Этапы») держать в актуальном состоянии.
