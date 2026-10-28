# Журнал экспериментов

Среда: облачный Linux-контейнер (x86_64), интернет через прокси. **Физического доступа к
приставке и телевизору нет.** Все эксперименты — статический анализ официальной прошивки и
эмуляция отдельных бинарников в qemu-user. Ничего не записывалось на реальное устройство.

---

### EXP-01 — Получение официальной прошивки и документации
- **Дата:** 2026-10-28
- **Цель:** получить первоисточник и проверить целостность.
- **Исходные условия:** страница S2 на gs.ru.
- **Метод:** `curl -L` архива `20260903_B531N_usb_8_0_307-0_1_19.zip` и PDF руководства. Сверка MD5 с `readme.txt`.
- **Результат:** HTTP 200, 77 914 381 и 4 853 222 байт. MD5 `b533m.upd` = `4549ea69…323c`, `b533m_gs1.upd` = `4597bd13…5354` — **совпадают с readme**.
- **Ошибки:** нет.
- **Вывод:** образ подлинный (официальный источник, контрольные суммы совпали).
- **Лог:** [logs/exp01_download_hashes.log](logs/exp01_download_hashes.log), [logs/firmware_readme_8.0.307.txt](logs/firmware_readme_8.0.307.txt)
- **Следующий шаг:** EXP-02.

### EXP-02 — Формат контейнера обновления
- **Цель:** определить формат `.upd`.
- **Метод:** `file`, `unzip -l`, чтение `update.json`, `od`.
- **Результат:** `b533m.upd` — zip (store): `update.json`, `kernel.bin` (u-boot legacy uImage, Linux-3.10.23-mstar, MIPS, load 0x80000000), `rootfs.squashfs` (squashfs 4.0, zlib, 1043 inode). `update.json`: `targetPlatforms: ["b533m"]`, сценарий `WriteFlashPartition` kernel/rootfs, рынки `tricolor`, `tricolor-ott`, `tis-dialog`, `vashnet`. `b533m_gs1.upd` — непрозрачный контейнер с магией `UPNH`, похож на шифрованный.
- **Вывод:** основной образ не зашифрован, его можно анализировать. Подпись проверяется вне zip (в zip нет отдельного файла подписи) — механизм неизвестен.
- **Ограничения:** механизм подписи не реверсился сознательно.
- **Лог:** [logs/exp02_update_container.log](logs/exp02_update_container.log)

### EXP-03 — Идентификация ядра и SoC
- **Метод:** `strings kernel.bin | grep`.
- **Результат:** `Linux version 3.10.23 (stingray@gs-labs.tv) (gcc 4.6.4, crosstool-NG 1.19.0) #1 PREEMPT Fri Oct 17 2025`, `MStar Kenya`, `LINUX ON MSTAR`, драйверы `Mstar-gmac`, `Mstar-ehci`, таблица ядер MIPS 24K/34K/74K/1004K/1074K.
- **Вывод:** SoC MStar семейства «Kenya», CPU MIPS. Ядро 3.10 (2013) без поддержки mainline.
- **Лог:** [logs/exp03_kernel.log](logs/exp03_kernel.log)

### EXP-04 — Обзор корневой ФС
- **Метод:** `unsquashfs`, `ls`, `readelf`, `strings`.
- **Результат:** BusyBox, glibc 2.23, OpenSSL 1.1.1c, dbus, glib, ntfs-3g, wpa_supplicant, hostapd, usb_modeswitch. MStar HAL (`libapiVDEC`, `libapiGOP`, `libapiHDMITX`…). 37 модулей USB Wi-Fi, BT и USB-сети. Нет WebKit, Chromium, Qt, ffmpeg, GStreamer. inittab: shell на ttyS0.
- **Вывод:** закрытая embedded Linux-система вендора, без браузерного движка и Android-слоя.
- **Лог:** [logs/exp04_rootfs_survey.log](logs/exp04_rootfs_survey.log)

### EXP-05 — Формат приложений StingrayTV
- **Метод:** `unzip -l` / `unzip -p` системных `.pkg`.
- **Результат:** `.pkg` = zip с `Manifest` (JSON: `id`, `markets`, `version`, `sdk.minVersion: 12`) и QML-подобным кодом или сгенерированным JS. Системные пакеты не используют `WebView`.
- **Вывод:** приложения — декларативный UI на JS. Компилируемый нативный код в пакетах не встречен.
- **Лог:** [logs/exp05_app_packages.log](logs/exp05_app_packages.log)

### EXP-06 — Возможности основного бинарника `argile-zapper`
- **Метод:** `strings -n 6` (194 112 строк) + grep по ключевым словам.
- **Результат:** `youtube` 0, `webkit` 0, `chromium` 0, `widevine` 0, `playready` 0. V8 9.7.106.21. Проверка подписи пакетов: `No signature found in package`, `signature verification failed`, `package signature correct, verifying permissions...`. `WebView->setUrl/setHtml` есть, движка нет. HLS m3u8, DLNA/UPnP ContentDirectory, WebDav, контейнеры MP4/MKV/AVI. Wi-Fi через wpa_supplicant. Сборка `mstar-k5cn-8.0-nihilus`.
- **Вывод:** платформа может играть HLS и локальные файлы, но не содержит ни YouTube, ни браузера. Сторонние пакеты отсекаются проверкой подписи.
- **Лог:** [logs/exp06_zapper_strings.log](logs/exp06_zapper_strings.log)

### EXP-07 — Эмуляция userland в qemu-user *(только эмуляция, не GS B531N)*
- **Цель:** подтвердить ABI и набор инструкций.
- **Метод:** `qemu-mipsel [-cpu X] -L rootfs rootfs/bin/busybox uname -m` (QEMU 8.2.2).
- **Результат:** default и 24KEc — SIGILL (exit 132). 34Kf и 74Kf — `mips`, exit 0. BusyBox v1.31.0 и glibc 2.23 запускаются.
- **Вывод:** бинарники MIPS32r2 LE с FPU (вероятно, и DSP ASE). Совместимы с 34K/74K-классом.
- **Ограничения:** запуск `argile-zapper` невозможен без железа MStar (HAL `libdrv*`/`libapi*`). Эмуляция полной приставки **не строилась**: архитектура периферии неизвестна, и это создало бы ложное впечатление совместимости.
- **Лог:** [logs/exp07_qemu.log](logs/exp07_qemu.log)

### EXP-08 — Возможности USB и сети по руководству
- **Метод:** `pdftotext` + grep.
- **Результат:** внешний USB Wi-Fi-адаптер (совместимость не гарантируется), 3G-модем, «Мультимедиа» (JPG/BMP/PNG/MP4/AVI/MKV, MP3), рекомендовано EXT4, для обновления нужен NTFS.
- **Лог:** [logs/exp08_user_guide.log](logs/exp08_user_guide.log)

### EXP-09 — Поиск YouTube-клиента под архитектуру
- **Метод:** документация Cobalt (S6, S7), поиск по GS/Триколор.
- **Результат:** Evergreen — только x86_64/armv7/armv8. MIPS — лишь устаревший contrib-порт (20.lts) для CI20. Сертифицированного YouTube для MStar MIPS STB нет. Упоминаний YouTube на приёмниках GS не найдено.
- **Вывод:** направление B закрыто: нет клиента под архитектуру, и нет пути установки.

---

## Запланировано (нужен физический доступ)

| ID | Цель | Метод | Что нужно | Риск |
|---|---|---|---|---|
| EXP-10 | Определить модель телевизора | Наклейка на задней панели (например, `LE40C530F1W`, `UE32D4000`). Меню → «Поддержка» → «Обращение в Samsung» | Пользователь | нет |
| EXP-11 | Входы ТВ | Пересчитать HDMI/USB/VGA на задней и боковой панели | Пользователь | нет |
| EXP-12 | Проверить HDMI-источник | Подключить ноутбук или смартфон (USB-C→HDMI) к свободному HDMI ТВ, открыть youtube.com | Ноутбук + HDMI-кабель | нет |
| EXP-13 | Офлайн-воспроизведение на B531N | Скопировать MP4 (H.264+AAC) и MKV (HEVC) на флешку EXT4/NTFS → «Мультимедиа» | Флешка | нет |
| EXP-14 | DLNA с ПК на B531N | Медиасервер на ПК (например, Windows Media Player или Jellyfin) → Media Explorer приставки | ПК в той же сети, Ethernet | нет |
| EXP-15 | Полноценный YouTube через внешний плеер | Google TV / Android TV плеер → HDMI → вход, авторизация, воспроизведение со звуком | Устройство ~2–6 тыс. ₽ | нет |

Критерий успеха для EXP-12 и EXP-15: открылся интерфейс, загрузился список роликов, ролик
воспроизводится **со звуком** на экране Samsung, пульт или клавиатура управляют воспроизведением.
