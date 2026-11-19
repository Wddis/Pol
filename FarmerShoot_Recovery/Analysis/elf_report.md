# ELF Report — libunity_farmershoot_balders_v3_PROTOBUF_TEST.so

## Файл
| Поле | Значение |
|---|---|
| Имя | `libunity_farmershoot_balders_v3_PROTOBUF_TEST.so` |
| Размер | 217 742 416 байт (207.6 МБ) |
| SHA256 | `b65177bd30395ce02d5e645f746871290c890b744afa8976e3361660d94d8e14` |
| MD5 | `63d3eb83bddf978af67158b595f6015b` |
| GNU Build-ID | `69815f2befe135c2` |

## ELF Header
| Поле | Значение |
|---|---|
| Class | **ELF64** |
| Data | 2's complement, **little-endian (LSB)** |
| OS/ABI | UNIX — System V |
| Type | **DYN** (shared object) |
| Machine | **AArch64 (ARM64, EM_AARCH64 = 0xB7)** |
| Entry point | 0x0 (нормально для .so) |
| Program headers | 10, по 56 байт, off=64 |
| Section headers | 27 заявлено, off=217 738 208 — **ПОВРЕЖДЕНЫ** |

## Сегменты (Program Headers) — ЦЕЛЫ
| Тип | Offset | VirtAddr | FileSize | Flags |
|---|---|---|---|---|
| PHDR | 0x40 | 0x40 | 0x230 | R |
| LOAD | 0x0 | 0x0 | 0x57b5468 | R |
| LOAD | 0x57b5480 | 0x57b9480 | 0x6aa54d0 | R-E (код) |
| LOAD | 0xc25a950 | 0xc262950 | 0x7314a0 | RW |
| LOAD | 0xc98be00 | 0xc997e00 | 0x61ac58 | RW |
| DYNAMIC | 0xc89fc28 | 0xc8a7c28 | 0x1f0 | RW — **указатель смещён** |
| GNU_RELRO | 0xc25a950 | 0xc262950 | 0x7314a0 | R |
| GNU_EH_FRAME | 0x3e0fa10 | 0x3e0fa10 | 0x4ca34c | R |
| GNU_STACK | 0x0 | 0x0 | 0x0 | RW |
| NOTE | 0x270 | 0x270 | 0x18 | R |

Четыре LOAD-сегмента покрывают весь файл; крупного «приклеенного» блока за концом сегментов нет — 208 МБ это реальное содержимое библиотеки.

## Обнаруженные повреждения (это и есть следы «восстановлений»)
1. **Таблица заголовков секций (section header table) затёрта мусором.**
   `readelf` выдаёт `sh_link = 0x20202020` (ASCII-пробелы) и `0x65766973` (ASCII `"sive"`); поля секций заполнены паттернами `20 20 20 20`, `30 30 30 30` («0000»). Из-за этого `nm`, `objdump -d`, `readelf -S` не работают. **Для загрузки на Android это не критично** — загрузчик использует сегменты, а не секции.
2. **PT_DYNAMIC указывает на обнулённую область.** p_offset=0xc89fc28 / p_vaddr=0xc8a7c28 → там нули (DT_NULL). **Настоящая `.dynamic` цела** и найдена по адресу file offset `0xc8a05b4` (смещение указателя ≈ +0x98c). Данные целы — «уехал» только указатель.

## Вывод
ELF-контейнер структурно повреждён в метаданных (section headers + указатель PT_DYNAMIC), но **полезная нагрузка — код и данные в LOAD-сегментах — цела**, и динамическая таблица восстановима. Оригинал в `Original/` сохранён в режиме read-only и не изменялся.
