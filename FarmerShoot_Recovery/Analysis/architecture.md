# Architecture (CPU / ABI)

| Параметр | Значение | Источник |
|---|---|---|
| CPU | **ARM AArch64 (ARM64, 64-bit)** | e_machine = 0xB7 |
| ELF Class | ELF64 | e_ident[EI_CLASS]=2 |
| Endianness | Little-endian (LSB) | e_ident[EI_DATA]=1 |
| ABI | Android / Bionic (Linux ARM64) | NEEDED libc.so/libdl.so/libm.so (Bionic), libandroid.so, libmediandk.so |
| Android ABI name | **arm64-v8a** | следствие AArch64 + Bionic |
| Тип | DYN (PIC shared object) | e_type=3 |

## Что это означает
- Библиотека собрана **только под `arm64-v8a`**. Это современный стандарт Google Play (с 2019 г. 64-бит обязателен).
- Для запуска нужен ARM64-совместимый Android-процесс (реальное устройство arm64 или эмулятор arm64). На x86-эмуляторе без трансляции не запустится.
- Другие ABI (`armeabi-v7a`, `x86_64`) в этом файле отсутствуют — если проект собирался мультиплатформенно, остальные `.so` лежат в других папках APK (`lib/<abi>/`).

## Выравнивание / загрузка
- Выравнивание LOAD-сегментов 0x4000 (16 КБ) — соответствует требованию Android 15+ к 16 КБ page size. Движок Unity 6.3 для этого и предназначен.
