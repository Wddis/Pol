# Источники

Все источники открыты 28.10.2026.

## Официальные (General Satellite)

| # | Ссылка | Дата материала | Что подтверждено |
|---|---|---|---|
| S1 | https://www.gs.ru/catalog/sputnikovye-tv-pristavki/gs-b531n/ | — | eMMC 8 ГБ, HDMI 1.4, USB 2.0, Ethernet 100Base-T, нет Wi-Fi, кодеки MPEG-2/H.264/HEVC, сервисы, 12 В/2 А, габариты |
| S2 | https://www.gs.ru/support/documentation-and-software/gs-b531n/ | ПО от 10.09.2026, UG от 04.10.2023 | Прошивка 8.0.307 / GS1 0.1.19 (USB-обновление), список изменений, без отката |
| S3 | https://www.gs.ru/support/documentation-and-software/gs-b531n/20260903_B531N_usb_8_0_307-0_1_19.zip | 03.09.2026 | Образ прошивки (SHA-256 `8b377b5f…baea2`), разобран в EXP-01…07 |
| S4 | https://www.gs.ru/support/documentation-and-software/gs-b531n/StingrayTV-5.7-UG.pdf | 04.10.2023 | Руководство StingrayTV 5.7: USB Wi-Fi адаптеры, 3G-модем, «Мультимедиа» (JPG/BMP/PNG/MP4/AVI/MKV/MP3), EXT4/NTFS |
| S5 | https://www.gs.ru/support/documentation-and-software/software-list/ | — | Тот же релиз 8.0.307 для B531N/B531M/B533M/B534M |

## YouTube и Cobalt

| # | Ссылка | Что подтверждено |
|---|---|---|
| S6 | https://developers.google.com/youtube/cobalt/docs/gen/starboard/doc/evergreen/cobalt_evergreen_lite | Целевые архитектуры Cobalt Evergreen: x86_64, armv7 32, armv8 64. MIPS отсутствует |
| S7 | https://cobalt.googlesource.com/cobalt/+show/20.lts.stable/src/starboard/contrib/creator/README.md | Был только старый contrib-порт под MIPS-плату Creator CI20 (ветка 20.lts). Это не сертифицированный клиент YouTube |
| S8 | https://developers.google.com/youtube/cobalt/docs/releases/2025-lts-rc | В Cobalt 25 прекращена поддержка Evergreen x86 (тенденция к сокращению архитектур) |

## Родственные проекты (совместимость не доказана)

| # | Ссылка | Оценка |
|---|---|---|
| S9 | https://github.com/pureqml/qmlcore | Открытый компилятор QML→JS для Smart TV и STB. Порты под STB у авторов приватные. Синтаксис пакетов StingrayTV похож. Для YouTube бесполезен: нет медиа-DRM и нет подписи пакетов |

## Контекст доступности сервиса (Россия)

| # | Ссылка | Что сказано |
|---|---|---|
| S10 | https://verstka.media/zamedlenie-youtube-v-rossii-oslablo-do-minimalnyh-znachenij-za-poltora-goda | По данным OONI летом 2026 замедление ослабло, но сохраняется |
| S11 | https://www.fontanka.ru/2024/12/19/74900933/ | Усиление замедления в декабре 2024 |

Это ограничение на уровне сети, а не устройства. Оно влияет на любой вариант (A, B, C) одинаково.

## Поиск без результата

Запросы «GS B531N firmware / Linux / Android / alternative firmware / YouTube», «General Satellite
B531N bootloader», «MStar Kenya SoC» не дали ни альтернативных прошивок, ни SDK, ни
проектов модификации, ни упоминаний YouTube на приёмниках GS. Это фиксируется как
**отсутствие найденных данных**, а не как доказательство невозможности.
