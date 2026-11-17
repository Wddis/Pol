# ELF-отчёт — Original/Test.so

| Поле | Значение |
|---|---|
| Размер | 217 737 937 байт (207,7 МиБ) |
| SHA256 | `a667df2aa75007abb7549afeebe19c3e4dbcf84bfb4b636e5dec08e7e5a65a5b` |
| ELF Class | ELF64 |
| Endianness | Little endian |
| Машина | AArch64 (ARM64) |
| ABI Android | **arm64-v8a** |
| OS/ABI | System V, ABI version 0 |
| Тип | ET_DYN (разделяемая библиотека `.so`) |
| Entry point | 0x0 (обычно для `.so`) |
| GNU Build-ID (NOTE) | `69815f2befe135c2` (8 байт, укороченный ID) |

## Целостность — ФАЙЛ ПОВРЕЖДЁН
1. **Обрезан конец.** Таблица секций должна начинаться со смещения 217 738 208 (27 × 64 = 1728 байт), но файл заканчивается раньше, на 217 737 937. Не хватает ≥ 1999 байт. Секций нет, поэтому `nm`, `objdump` и отображение секций в Ghidra/IDA не работают. Остались только строки имён секций (`.dynsym`, `.shstrtab`).
2. **Обнулены области данных.** Найдено 8 нулевых блоков ≥ 64 КБ, всего около 7,7 МБ. Все лежат в RW-сегментах (RELRO / .data):
   `0xc5e5c72` (2,8 МБ), `0xcb0b25b` (2,0 МБ), `0xc89f632` (0,96 МБ), `0xc39de02` (0,83 МБ), `0xc4e6af2` (0,57 МБ), `0xce2b125`, `0xcf73bc5`, `0xcee90d5`.
3. **Сегмент DYNAMIC (0xc89fc28) полностью обнулён**, потому что попал в блок `0xc89f632`. Из-за этого нет DT_NEEDED, DT_SYMTAB, DT_STRTAB и релокаций: загрузчик Android такой файл не откроет.

## Сегменты (program headers целы)
| Тип | Offset | VAddr | FileSz | MemSz | Флаги |
|---|---|---|---|---|---|
| PHDR | 0x40 | 0x40 | 0x230 | 0x230 | R |
| LOAD | 0x0 | 0x0 | 0x57b5468 | 0x57b5468 | R (rodata, ~88 МБ) |
| LOAD | 0x57b5480 | 0x57b9480 | 0x6aa54d0 | 0x6aa54d0 | R E (код, ~107 МБ) |
| LOAD | 0xc25a950 | 0xc262950 | 0x7314a0 | 0x7316b0 | RW (RELRO) |
| LOAD | 0xc98be00 | 0xc997e00 | 0x61ac58 | 0x96fdb8 | RW (.data/.bss) |
| DYNAMIC | 0xc89fc28 | — | 0x1f0 | — | **обнулён** |
| GNU_RELRO | 0xc25a950 | — | 0x7314a0 | — | |
| GNU_EH_FRAME | 0x3e0fa10 | — | 0x4ca34c | — | цел, по нему можно найти границы функций |
| GNU_STACK | — | — | — | — | RW, без исполнения |
| NOTE | 0x270 | — | 0x18 | — | GNU Build-ID |

Выравнивание 0x4000 (16 КБ): сборка под требование Android 15+ о страницах 16 КБ.

## Секции
Заголовки секций потеряны (см. выше). Секции, восстанавливаемые по `.shstrtab` в хвосте файла: `.dynsym` и `.shstrtab` найдены по строкам. Остальное можно вывести только по сегментам.

## Символы, отладка, сборка
- Экспорт и импорт **напрямую не читаются**: DYNAMIC обнулён. Таблица `.dynstr` в начале файла (около 0x2000) уцелела частично, в ней есть `JNI_OnLoad`, `UnitySendMessage`, `libc.so`, `libm.so`, `libdl.so`, `libandroid.so`, `liblog.so`, `libunity.so`. Подробности в `symbols.md`.
- DWARF: секций `.debug_*` нет (строк `.debug_info` тоже нет). Файл **stripped**, есть только `.eh_frame`.
- Путь сборки: `/workspace/farmershoot/TempMerger/il2cpp/libil2cpp/...` (CI-сборка, «TempMerger»).
- Сырые выводы: `readelf_header.txt`, `readelf_segments.txt`, `nm_dynamic.txt` (nm падает: «file too short»).
