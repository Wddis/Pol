# GS B531N: аппаратная и программная платформа

Уровни достоверности:

- **[Ф-офиц]** — факт из официального источника General Satellite.
- **[Ф-прошивка]** — факт, установленный анализом официальной прошивки 8.0.307 (см. logs/).
- **[Г]** — гипотеза.
- **[?]** — неизвестно.

## Аппаратные характеристики

| Параметр | Значение | Уровень | Доказательство |
|---|---|---|---|
| Тип | Двухтюнерный спутниковый приёмник DVB-S/S2, серверный (для клиентов «Мультиэкран») | Ф-офиц | gs.ru, карточка модели |
| SoC | MStar, кодовое имя платформы «Kenya». Сборочный путь `mstar-k5cn-8.0` | Ф-прошивка | logs/exp03_kernel.log, logs/exp06_zapper_strings.log |
| Точный номер микросхемы (MSDxxxx) | неизвестен; в открытых источниках «MStar Kenya» не найден | ? | поиск, sources.md |
| CPU | MIPS32 release 2, little-endian, ABI o32, mips16, нужны FPU и DSP | Ф-прошивка | readelf: `MIPS R3000 … o32, mips16, mips32r2`. qemu: запуск с `-cpu 34Kf`/`74Kf` успешен, с `24KEc` и по умолчанию — SIGILL (logs/exp07_qemu.log) |
| Ядро процессора | 34K или 74K (оба есть в таблице CPU ядра) | Г | logs/exp03_kernel.log |
| RAM | неизвестно | ? | на gs.ru не указано |
| Постоянная память | eMMC 8 ГБ (встроенный накопитель); в ядре также есть драйвер NAND MStar | Ф-офиц / Ф-прошивка | gs.ru; строка `Allocate Mstar nand info fail` |
| Видео (декодирование эфира) | MPEG-2, MPEG-4 AVC (H.264), HEVC (H.265) | Ф-офиц | gs.ru |
| Видео (заявлено во фреймворке) | Mpeg1/2/4, H263, H264, H265, AV1, VP8, VP9 и др. | Ф-прошивка (список фреймворка) | Это общий список StingrayTV. **Аппаратное декодирование VP9/AV1 на этом SoC не подтверждено** |
| Выходы | HDMI 1.4, 3×RCA, S/PDIF оптический | Ф-офиц | gs.ru |
| USB | 1× USB 2.0 спереди | Ф-офиц | gs.ru, фото |
| Ethernet | 100Base-T (драйвер `Mstar-gmac`) | Ф-офиц / Ф-прошивка | gs.ru, kernel |
| Wi-Fi встроенный | нет | Ф-офиц | gs.ru |
| Wi-Fi через USB | поддерживается штатно, совместимость со всеми адаптерами не гарантируется. В прошивке есть модули Realtek 8188eu/8192cu/8192eu/8723bu/8812au/8821au/8821cu/88x2bu/88x2cu/8814au, MediaTek mt7601u, Atheros ath9k_htc/carl9170, Ralink rt2800usb/rt73usb | Ф-офиц / Ф-прошивка | UG §2.3, logs/exp04_rootfs_survey.log |
| 3G-модем через USB | поддерживается (usb_modeswitch) | Ф-офиц | UG §2.3.4 |
| Bluetooth и Zigbee через USB | модули и демоны есть (умный дом) | Ф-прошивка | logs/exp04 |
| Последовательная консоль | `ttyS0::respawn:-/bin/sh` в inittab | Ф-прошивка | logs/exp04. Требует вскрытия корпуса; физическое наличие разъёма UART не проверено |
| Питание и габариты | 12 В 2 А, ≤24 Вт; 214×128×34 мм | Ф-офиц | gs.ru |

## Программная платформа

| Параметр | Значение | Уровень | Доказательство |
|---|---|---|---|
| ОС | Embedded Linux на BusyBox 1.31.0 (сборка 17.10.2025). **Не Android** | Ф-прошивка | logs/exp04, logs/exp07 |
| Ядро | Linux 3.10.23-mstar, PREEMPT, gcc 4.6.4, собрано stingray@gs-labs.tv | Ф-прошивка | logs/exp03 |
| libc | glibc 2.23 (crosstool-NG 1.23.0) | Ф-прошивка | logs/exp07 |
| TLS | OpenSSL 1.1.1c (2019), TLS 1.3 технически возможен | Ф-прошивка | logs/exp04 |
| UI и рантайм | `/system/argile-zapper` (113 МБ, MIPS ELF): движок V8 9.7.106.21, собственный UI-фреймворк `argile`, XMLHttpRequest | Ф-прошивка | logs/exp06 |
| Формат приложений | `.pkg` = zip с `Manifest` (JSON: id, markets, sdk.minVersion) и QML/JS. Синтаксис QML похож на pureqml/qmlcore, но это не Qt QML (Qt-библиотек нет) | Ф-прошивка / Г (родство с qmlcore) | logs/exp05 |
| Установка приложений | Пакеты проверяются по подписи: `No signature found in package`, `signature verification failed`, `package signature correct, verifying permissions...` | Ф-прошивка | logs/exp06 |
| Браузер | Есть элемент `WebView` (`setUrl`/`setHtml`), но HTML-движка (WebKit, Chromium и т.п.) нет ни в бинарнике, ни в библиотеках. В системных пакетах `WebView` не используется | Ф-прошивка (отсутствие движка) / Г (WebView — заглушка) | logs/exp04, logs/exp06 |
| Медиаплеер | «Мультимедиа»: JPG, BMP, PNG, MP4, AVI, MKV, MP3 с USB. «Media Explorer»: DLNA и WebDav. HLS-клиент (m3u8) | Ф-офиц / Ф-прошивка | UG §4.17, logs/exp06, logs/exp08 |
| DRM | Собственная CAS/DRM (CAS 4.0.6, DRM 3.6.0 HP). Widevine/PlayReady не найдены | Ф-прошивка | logs/exp06 |
| Формат обновления | `b533m.upd` = zip: `update.json` (targetPlatforms `b533m`, рынки tricolor и др.), `kernel.bin` (u-boot legacy uImage), `rootfs.squashfs` (zlib). `b533m_gs1.upd` — шифрованный или подписанный контейнер с магией `UPNH`. Откат на предыдущую версию запрещён | Ф-прошивка | logs/exp02, readme |
| Единая платформа | Обновление B531N использует имена файлов и `targetPlatforms` **b533m**. Платформа общая с GS B533M; на gs.ru тот же релиз 8.0.307 указан и для B534M, B531M | Ф-прошивка / Ф-офиц | logs/exp02 |
| SDK для сторонних разработчиков | публичного нет | ? | не найден |
| Загрузчик | U-Boot (по формату uImage). Блокировка и проверка подписи ядра — неизвестно | Г | logs/exp02 |

## Неизвестно, и как это проверить

1. Номер микросхемы SoC и объём RAM: маркировка на чипах после вскрытия, `/proc/cpuinfo` и `/proc/meminfo` через UART.
2. Проверяет ли U-Boot подпись `kernel.bin` и `rootfs.squashfs`: анализ U-Boot (нужен дамп eMMC или лог UART при загрузке).
3. Механизм подписи `.upd`: нужен реверс `argile-zapper`. **Не проводилось**, это обход защиты.
4. Работает ли `WebView`: в штатной прошивке проверить нельзя.
5. Аппаратная поддержка VP9 и AV1 на этом SoC: datasheet или тест-файл VP9.webm/MKV через «Мультимедиа».
