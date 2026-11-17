# ELF-отчёт: Test.so

| Параметр | Значение |
|---|---|
| Размер | 217 742 049 байт |
| SHA256 | `891b66713904271b155d230b14e9afbdc0b69c20c216abe89df49fa8041d1292` |
| MD5 | `bd9204fb38cdaa34d4ee7b66c46bdf03` |
| ELF Class | ELF64 |
| Endianness | Little-endian |
| Machine | AArch64 (ARM64) |
| ABI (Android) | `arm64-v8a` |
| OS/ABI | SYSV (0) |
| Type | ET_DYN (shared object) |
| Entry | 0x0 (библиотека) |
| SONAME | `libunity.so` |
| GNU Build ID | `69815f2befe135c2` (8 байт — короткий ID, характерно для сборок Unity) |
| `.note.unity` | `Unity Technologies` / `6000.3.13f1_3b0d9f02b51c` |
| `.gnu_debuglink` | `libunity.sym.so` (символы вынесены в отдельный файл) |
| Отладочная информация | **нет** (нет `.debug_*`, `.symtab`), только `.eh_frame` |
| Stripped | **да** |

## ⚠️ Главная находка: файл повреждён

Файл **не загрузится** на устройстве в текущем виде. Причина — внутрь файла вставлено
лишних **0x841 = 2113 байт**, из-за чего всё, что идёт после точки вставки, сдвинуто
относительно программных заголовков.

Доказательства:

| Структура | Где ожидается по заголовкам | Где реально находится | Сдвиг |
|---|---|---|---|
| `.dynamic` (PT_DYNAMIC) | 0x0c89fc28 | 0x0c8a0469 | +0x841 |
| `.eh_frame_hdr` (`01 1b 03 3b`) | 0x03e0fa10 | 0x03e10251 | +0x841 |
| `.text` (первый пролог) | 0x057b5480 | 0x057b5cc1 | +0x841 |
| `.shstrtab` | 0x0cfa6a58 | 0x0cfa7299 | +0x841 |
| Таблица секций (`e_shoff`) | 0x0cfa6be0 | 0x0cfa7421 (= EOF − 27×64) | +0x841 |

По смещению, где загрузчик ищет `.dynamic`, лежат нули → `readelf`, `objdump`, `nm`
выдают ошибки «no .dynamic section», а Android-линкер отказался бы грузить библиотеку.

**Не сдвинуты** (целы на своих местах): ELF-заголовок, PHDR, `.note.gnu.build-id`,
`.dynsym`, `.gnu.version*`, `.gnu.hash`, `.dynstr`, `.rela.dyn`, `.rela.plt`, начало `.rodata`.

Значит, вставка(и) находятся **внутри `.rodata`** (0x01a1ea40–0x03b8154c).
Зонды выравнивания (`Extracted/alignment_shift_map.txt`: позиции `0x3f800000` = 1.0f и
изолированных `0xffffffff` по модулю 4) показывают:

- до ~0x01a24000 — сдвига нет;
- 0x01b38000–0x02894000 — сдвиг ≡ 2 (mod 4);
- 0x030ec000–0x030f0000 — сдвиг ≡ 3 (mod 4);
- с 0x031fc000 и до конца файла — сдвиг ≡ 1 (mod 4) = полные 0x841.

То есть, по всей видимости, вставок **несколько** (суммарно 2113 байт), а не одна.
Без эталонной копии точные границы однозначно не определяются (см. `recovery_possibilities.md`).

## Секции (реальная таблица, найденная в конце файла)

| # | Тип | Адрес | Смещение (по заголовку) | Размер | Вероятное имя |
|---|---|---|---|---|---|
| 1 | NOTE | 0x270 | 0x270 | 0x18 | .note.gnu.build-id |
| 2 | DYNSYM | 0x288 | 0x288 | 0x1c80 | .dynsym (304 записи) |
| 3 | VERSYM | 0x1f08 | 0x1f08 | 0x260 | .gnu.version |
| 4 | VERDEF | 0x2168 | 0x2168 | 0x38 | .gnu.version_d |
| 5 | VERNEED | 0x21a0 | 0x21a0 | 0x60 | .gnu.version_r |
| 6 | GNU_HASH | 0x2200 | 0x2200 | 0x40 | .gnu.hash |
| 7 | STRTAB | 0x2240 | 0x2240 | 0x141b | .dynstr |
| 8 | RELA | 0x3660 | 0x3660 | 0x1a199a8 | .rela.dyn (~1.14 млн релокаций) |
| 9 | RELA | 0x1a1d008 | 0x1a1d008 | 0x1a28 | .rela.plt |
| 10 | PROGBITS | 0x1a1ea40 | 0x1a1ea40 | 0x2162b0c | .rodata (≈35 МБ) |
| 11 | PROGBITS | 0x3b8154c | | 0x28e4c4 | .gcc_except_table |
| 12 | PROGBITS | 0x3e0fa10 | | 0x4ca34c | .eh_frame_hdr |
| 13 | PROGBITS | 0x42d9d60 | | 0x14db708 | .eh_frame |
| 14 | PROGBITS AX | 0x57b9480 | 0x57b5480 | 0x6aa4340 | .text (≈111 МБ кода) |
| 15 | PROGBITS AX | 0xc25d7c0 | | 0x1190 | .plt |
| 16 | PROGBITS WA | 0xc262950 | 0xc25a950 | 0x6442c8 | .data.rel.ro |
| 17 | INIT_ARRAY | 0xc8a6c18 | | 0x1010 | .init_array |
| 18 | DYNAMIC | 0xc8a7c28 | 0xc89fc28 | 0x1f0 | .dynamic |
| 19 | PROGBITS | 0xc8a7e18 | | 0xeb708 | .got |
| 20 | PROGBITS | 0xc993520 | | 0x8d0 | .got.plt |
| 21 | PROGBITS | 0xc993df0 | | 0x210 | (малая data) |
| 22 | PROGBITS | 0xc997e00 | 0xc98be00 | 0x61ac58 | .data |
| 23 | NOBITS | 0xcfb2a80 | | 0x355138 | .bss |
| 24–26 | | | | | .shstrtab, .note.unity, .gnu_debuglink |

## Сегменты (Program Headers — целы)

```
PHDR         0x000040  va 0x40        filesz 0x230
LOAD R       0x000000  va 0x0         filesz 0x57b5468
LOAD R E     0x57b5480 va 0x57b9480   filesz 0x6aa54d0
LOAD RW      0xc25a950 va 0xc262950   filesz 0x7314a0 memsz 0x7316b0  (RELRO)
LOAD RW      0xc98be00 va 0xc997e00   filesz 0x61ac58 memsz 0x96fdb8
DYNAMIC      0xc89fc28 va 0xc8a7c28   0x1f0
GNU_EH_FRAME 0x3e0fa10 va 0x3e0fa10   0x4ca34c
GNU_STACK    RW
NOTE         0x270
```
Выравнивание сегментов 0x4000 (16 КБ) — соответствует требованиям Android 15+ к 16 KB page size.

## Восстановленная таблица .dynamic (найдена по смещению 0x0c8a0469)

```
NEEDED libandroid.so, libz.so, libEGL.so, libmediandk.so, libm.so, libdl.so, libc.so
SONAME libunity.so
FLAGS 0x8 (BIND_NOW)        FLAGS_1 0x1 (NOW)
RELA 0x3660  RELASZ 0x1a199a8  RELAENT 0x18  RELACOUNT 0x116646 (1 140 294)
JMPREL 0x1a1d008  PLTRELSZ 0x1a28  PLTGOT 0xc993520  PLTREL RELA
SYMTAB 0x288  SYMENT 0x18  STRTAB 0x2240  STRSZ 0x141b  GNU_HASH 0x2200
INIT_ARRAY 0xc8a6c18  INIT_ARRAYSZ 0x1010 (514 конструкторов)
VERSYM 0x1f08  VERDEF 0x2168 (2)  VERNEED 0x21a0 (3)
```
