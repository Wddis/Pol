# Farmer Shoot — Recovery Phase 1: технический отчёт

## 1. Итог в двух словах
`Test.so` — это **`libunity.so` из Android-сборки Unity 6000.3.13f1, ARM64 (arm64-v8a), IL2CPP**,
в который, судя по содержимому, статически влит сгенерированный IL2CPP-код игры.
Файл **повреждён**: внутрь вставлено 2113 лишних байт, всё после них сдвинуто, загрузчик видит пустую
`.dynamic` → библиотека не загрузится. Данные после точки вставки сохранены, просто сдвинуты.
**Исходный C#-код, сцены и ассеты из этого файла восстановить нельзя** — их в нём нет или они необратимо скомпилированы.

## 2. Сохранность
- Копия: `Original/Test.so` (read-only) + `/tmp/dl/Test.so.bak` в контейнере.
- SHA256 `891b66713904271b155d230b14e9afbdc0b69c20c216abe89df49fa8041d1292`.
- Оригинал не изменялся; анализ — только чтением.

## 3. Результаты анализа libunity.so
Подробно: `Analysis/elf_report.md`, `symbols.md`, `dependencies.md`, `strings_report.md`.
- ELF64, LE, AArch64, ET_DYN, SONAME `libunity.so`, Build ID `69815f2befe135c2`, stripped, без DWARF.
- 7 экспортов (`JNI_OnLoad`, `UnityInitialize`, `UnitySendMessage`, …), 296 импортов.
- `.text` 111 МБ, `.rodata` 35 МБ, 1,14 млн релокаций, 514 статических конструкторов.
- Повреждение: +0x841 байт внутри `.rodata` (одна или несколько вставок), найдены настоящие `.dynamic` и таблица секций.

## 4. Архитектура
ARMv8-A AArch64, `arm64-v8a`, страницы 16 КБ, BIND_NOW + full RELRO, Vulkan + GLES, GameActivity.

## 5. Версия Unity
**6000.3.13f1** (Unity 6.3) — достоверность **высокая (~99 %)**, источник — `.note.unity`.
Changeset `3b0d9f02b51c` не совпадает с публичным `8c4f11e4fb20` → собственная/патченная сборка движка.
Бэкенд **IL2CPP** — достоверность высокая (~95 %). Слияние IL2CPP-кода в libunity.so — средняя (~75 %).

## 6. Обнаруженные компоненты
- 512 управляемых сборок, из них 146 `Balders.*` и 110 `Balders.farmershoot.*` (`Extracted/assemblies_*.txt`).
- Режимы: Deathmatch, TeamDeathmatch, Defuse, RankedDefuse, Duel, RankedDuel, Tournament, Training.
- Геймплей: бомба, гранаты, молотов, хитбоксы, регдолл, команды, дроп, магазин оружия, реплеи, чат, голос (Vivox), граффити.
- Сеть: ENet-транспорт, собственный NetCode, бэкенд Bolt (матчмейкинг, хранилище, статистика), Agones (серверы).
- Плагины: FMOD + Steam Audio, Firebase, Google Play Games/Integrity, Facebook, Adjust, PostHog, Unity IAP, VContainer, PrimeTween.
- ⚠️ Фермерских механик в именах не найдено; пространства имён `Balders` — проверьте, что это ваш код.

## 7. Недостающие файлы
См. `Documentation/missing_files.md`. Ключевые: `global-metadata.dat`, `libil2cpp.so` (если есть), `data.unity3d`
/ `globalgamemanagers` / `resources.assets` / `sharedassets*`, `boot.config`, остальные `.so`, `classes.dex`, а лучше — git/бэкап проекта.

## 8. Реалистичная оценка
| Цель | Реально? |
|---|---|
| Починить libunity.so побайтово без эталона | ⚠️ без гарантии (~30 %) |
| Структурно согласованная копия для Ghidra | ✅ |
| Имена классов/методов | только с `global-metadata.dat` |
| Оригинальный C# | ❌ только из бэкапа |
| Ассеты/сцены | ❌ нужен APK/AAB |
| Запустить игру из одного .so | ❌ |

## 9. План этапа 2
1. Найти исходный проект / AAB / APK (Play Console → App bundle explorer, CI, старые устройства через `adb pull`).
2. Если найден эталон того же билда (Build ID `69815f2befe135c2`) — побайтовое сравнение и точный ремонт Test.so.
3. Если эталона нет — сделать *аналитическую* копию (`Reconstructed/libunity_analysis.so`) с удалением 0x841 байт
   в эвристической точке; пометить как непригодную для запуска.
4. Получив `global-metadata.dat`: Cpp2IL / свежий Il2CppDumper (metadata v31+ для Unity 6) → `dump.cs`, DummyDll,
   скрипт меток для Ghidra.
5. Ghidra headless (Java уже есть, сам Ghidra нужно установить) → импорт меток, псевдокод ключевых систем
   (GameMode, Weaponry, Damage, NetCode) в `Decompiled/` — **с пометкой «псевдокод, не оригинал»**.
6. Ассеты: AssetRipper по `data.unity3d`/`*.assets` → Unity-проект-каркас (сцены, префабы, материалы, ScriptableObject).
7. Ручная переписка C#-логики по псевдокоду и DummyDll — уже как новая реализация, а не «восстановленный оригинал».
