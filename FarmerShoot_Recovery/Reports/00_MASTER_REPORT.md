# FarmerShoot — RECOVERY PHASE 1 — Мастер-отчёт

**Дата:** 2026-11-19 · **Объект:** `libunity_farmershoot_balders_v3_PROTOBUF_TEST.so` · **Статус анализа:** завершён.

---

## 1. Что это за файл (главное)
Это **нативный движок Unity — `libunity.so`**, а НЕ код игры.

| Параметр | Значение | Достоверность |
|---|---|---|
| SHA256 | `b65177bd30395ce02d5e645f746871290c890b744afa8976e3361660d94d8e14` | — |
| Размер | 207.6 МБ (217 742 416 б) | — |
| ELF | ELF64, little-endian, DYN | 100% |
| CPU/ABI | **ARM64 (arm64-v8a)** | 100% |
| SONAME | `libunity.so` | 100% |
| Движок | **Unity 6000.3.13f1** (Unity 6.3), rev `3b0d9f02b51c` | ~95% |
| Backend | **IL2CPP** | высокая |
| Конфигурация | Development/не-Master | средне-высокая |
| Build-ID | `69815f2befe135c2` | 100% |

## 2. Состояние файла
Повреждены **метаданные ELF** (не содержимое):
1. Таблица секций затёрта текстом/мусором (`"sive"`, пробелы, «0000»).
2. Указатель `PT_DYNAMIC` смещён на обнулённую область; настоящая `.dynamic` цела по `0xc8a05b4`.

Код и данные в LOAD-сегментах **целы**. Оригинал сохранён read-only, не изменялся.

## 3. Обнаруженные компоненты (реальные, извлечены из бинарника)
- **Экспорты движка:** `UnityInitialize`, `UnityShutdown`, `UnitySendMessage`, `UnitySwappy_version/injectTracer`, `JNI_OnLoad/OnUnload`.
- **510 имён .NET-сборок**, **2872 игровых идентификатора** в регионе метаданных-имён (offset 27.4–49.1 МБ).
- **Проект:** кодовое имя **`farmershoot`**, неймспейс студии **`Balders.*`** — крупный **онлайн-FPS** (режимы RankedDefuse/Tournament/Deathmatch/Duel/Training; баллистика, гранаты, бомба, A*-патфайндинг, реплеи, матчмейкинг, dedicated-серверы Agones, магазин оружия).
- **SDK-стек:** Firebase, Facebook, Google Play Games/Integrity, FMOD+SteamAudio, Adjust, PostHog, VContainer, Protobuf, Bolt, Newtonsoft, YamlDotNet.

Детали: `Extracted/MODULE_INVENTORY.txt`, `Extracted/assembly_manifest.txt`, `Analysis/strings_report.md`.

## 4. Недостающие файлы
Полный разбор — `Documentation/missing_files.md`. Критично: **libil2cpp.so**, **global-metadata.dat**, (для Mono) **Assembly-CSharp.dll**, ассеты (`resources.assets`/bundles), `globalgamemanagers`, `boot.config`. Лучший источник — **оригинальный APK/AAB** или **папка Unity-проекта**.

## 5. Оценка восстановления (честно)
- ❌ **Механики/логику шутера и фарма из этого файла восстановить НЕЛЬЗЯ** — это движок, игрового кода в нём нет (подтверждено тестами: нет `il2cpp_*`, нет таблиц регистрации, нет global-metadata magic, нет сигнатур игровых методов).
- ✅ Восстановлено: техпаспорт движка + **архитектурная карта проекта** (модули, неймспейсы, системы, SDK).
- ⚠️ `global-metadata.dat` **в этот файл не вшит** — поиск по магии 0xFAB11BAF пуст во всех вариантах. (Это согласуется с тем, что metadata идёт с `libil2cpp.so`, а не с `libunity.so`.)

## 6. Расхождение, которое стоит проверить
Проект в бинарнике (`farmershoot`/`Balders.*`, соревновательный тактический FPS с матчмейкингом и серверами) по масштабу **не похож** на показанный на скриншотах учебный проект `FarmerStorm` (ферма, барн, `Placeholder_*.cs`). Стоит убедиться, что этот `libunity.so` действительно от вашей игры, а не взят из другого источника. Это влияет на выбор, какие файлы искать.

## 7. План следующего этапа
См. `Reports/01_NEXT_STEPS.md`.
