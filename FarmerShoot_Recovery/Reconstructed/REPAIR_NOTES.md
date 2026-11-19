# ELF Repair — результат

Файл: `libunity.repaired.so` (копия, оригинал не изменялся).
SHA256: см. `SHA256.txt`.

## Что исправлено
1. **PT_DYNAMIC** перенаправлен с обнулённого `0xc89fc28` на реальную `.dynamic` `0xc8a05b4` (vaddr `0xc8a85b4`).
2. **Таблица секций** пересобрана заново (15 корректных секций) из уцелевшей `.dynamic`: .dynsym, .gnu.version[_d/_r], .gnu.hash, .dynstr, .rela.dyn, .rela.plt, .text, .init_array, .dynamic, .data, .shstrtab. Прежний мусорный section table оставлен как «мёртвые» байты (не используется).

## Проверка (без ошибок/предупреждений)
- `file` → чистый ELF ARM aarch64, BuildID xxHash 69815f2befe135c2.
- `readelf -S` → 15 валидных секций.
- `readelf -d` → SONAME libunity.so + 7 NEEDED + релокации читаются штатно.
- `nm -D` → символы читаются.

## Важно
Это валидный, «здоровый» `libunity.so` **движка**. Он НЕ содержит игровой логики и сам по себе игру не запускает — нужен полный APK (libil2cpp.so + global-metadata.dat + ассеты).
