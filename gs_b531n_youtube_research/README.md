# YouTube на телевизоре Samsung с приставкой General Satellite GS B531N

Исследование 28.10.2026. Цель: найти обоснованный технический путь к работающему YouTube
на экране имеющегося телевизора Samsung.

## Исходное оборудование

| Устройство | Известно | Источник |
|---|---|---|
| Телевизор | **Samsung PS43E450**, плазма 43" 2012 года, ПО T-MST9DEUC-1019.0, не Smart TV, 2×HDMI, 1×USB, без LAN и Wi-Fi. См. [tv_samsung_ps43e450.md](tv_samsung_ps43e450.md) | фото экрана «Обращение в Samsung» |
| Приставка | General Satellite GS B531N, StingrayTV, ПО 8.0.307, модуль GS1 0.1.19, CAS 4.0.6, App 6.0.3, библиотека сервисов 3.3.4 06.82, DRM 3.6.0 HP, аппаратная ревизия rev01, USB на передней панели | фото экрана «Информация» |

MAC-адрес и серийный номер в репозиторий намеренно не попали.

## Три цели (рассматриваются раздельно)

- **A.** YouTube непосредственно на телевизоре.
- **B.** YouTube на GS B531N (штатно или через подтверждённую модификацию).
- **C.** YouTube через дополнительное устройство, подключённое к телевизору.

## Краткий результат

- **B — подтверждённого пути нет.** Официальная прошивка 8.0.307 скачана с gs.ru и разобрана.
  Внутри: SoC MStar (кодовое имя в ядре — «Kenya»), процессор **MIPS32r2 little-endian**,
  **Linux 3.10.23**, glibc 2.23, монолитный интерфейс `argile-zapper` на JS-движке V8 9.7.
  Приложения — QML/JS-пакеты, приёмник проверяет их **подпись**. HTML-браузерного движка нет.
  Клиентов YouTube под MIPS не существует (Cobalt Evergreen: только x86_64/armv7/armv8),
  Android на MIPS не поддерживается.
- **A — невозможно.** PS43E450 не Smart TV и не имеет сети. Прошивка зашифрована и подписана.
- **C — рабочий путь с минимальными затратами.** Внешний HDMI-плеер с Google TV или Android TV
  (официальный YouTube), либо кабель HDMI от ноутбука или ПК.

Подробно: [conclusion.md](conclusion.md).

## Структура

| Файл | Содержимое |
|---|---|
| [hardware.md](hardware.md) | Подтверждённые характеристики GS B531N и неизвестные параметры |
| [sources.md](sources.md) | Источники |
| [hypotheses.md](hypotheses.md) | Гипотезы, доказательства, статусы |
| [experiments.md](experiments.md) | Эксперименты EXP-01…EXP-08 и план следующих |
| [compatibility.md](compatibility.md) | Матрица совместимости и сравнение вариантов |
| [logs/](logs/) | Реальные логи анализа прошивки и эмуляции |
| [tv_samsung_ps43e450.md](tv_samsung_ps43e450.md) | Телевизор: возможности и прошивка |
| [conclusion.md](conclusion.md) | Ответы на 12 вопросов и следующий шаг |

## Воспроизведение

```sh
curl -LO https://www.gs.ru/support/documentation-and-software/gs-b531n/20260903_B531N_usb_8_0_307-0_1_19.zip
unzip 20260903_B531N_usb_8_0_307-0_1_19.zip
cd 20260903_B531N_usb_8_0_307-0_1_19 && mkdir upd && cd upd && unzip ../b533m.upd
file kernel.bin rootfs.squashfs
unsquashfs -no-xattrs -d rootfs rootfs.squashfs
qemu-mipsel -cpu 74Kf -L rootfs rootfs/bin/busybox uname -m
```

Анализ только статический, плюс запуск отдельных утилит через qemu-user. На реальную приставку
ничего не записывалось.
