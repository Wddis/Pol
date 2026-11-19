# Unity Version & Engine Analysis

## Версия Unity — ОПРЕДЕЛЕНА, достоверность ВЫСОКАЯ (~95%)

| Параметр | Значение |
|---|---|
| **Версия** | **Unity 6000.3.13f1** (маркетингово — Unity 6.3) |
| Ревизия движка | `3b0d9f02b51c` |
| Вариант | встречается также `6000.3.13f1_patched` |

Строка `6000.3.13f1` найдена 5 раз, в т.ч. в каноничном виде:
```
27911932: ...TilemapProxy 6000.3.13f1 (3b0d9f02b51c) CPU...
217740538: Unity Technologies  6000.3.13f1_3b0d9f02b51c
```
Это **совпадает со скриншотом редактора** (`Unity 6.3 (6000.3.13f1)`). Строка `2018.3.0a1` в бинарнике — это легаси-маркер движка («gone since Unity 2018.3»), а не версия сборки.

## Scripting backend — IL2CPP (не Mono)
- В движке присутствует весь слой интеграции с IL2CPP: `Extracting il2cpp resources`, `[libil2cpp]`, `N6il2cpp2vm6Thread...`, пути сборки `External/il2cpp/builds/...`, `il2cpp/libil2cpp/vm/Reflection.cpp`.
- Признаков Mono нет: `MonoBleedingEdge` = 0, `Mono path` = 0 (два совпадения `mono_` — случайные подстроки).
- **Важная оговорка (как и просили):** вывод об IL2CPP сделан НЕ по факту наличия `libunity.so`, а по множеству IL2CPP-специфичных строк движка + полному отсутствию Mono-рантайма. Сам игровой IL2CPP-код (`libil2cpp.so`) в этом файле отсутствует (см. `recovery_possibilities.md`).

## Тип сборки — Development/Debug (не Master), достоверность СРЕДНЕ-ВЫСОКАЯ
- Строка `development` — 13 вхождений; ссылки на `Assembly-CSharp-testable`, `Assembly-CSharp-Editor`, `*.Tests.Runtime`, тест-фреймворки.
- Размер 208 МБ (для release-`libunity.so` типично 40–70 МБ) согласуется с development-конфигурацией и сохранёнными именами управляемых типов.
- Точная строка `Development Build` не найдена, поэтому формулировка — «не-Master конфигурация с отладочными метаданными».

## Графика / подсистемы движка (по строкам)
- **GfxDevice**: Vulkan (56 упоминаний) + OpenGLES — рендер-бэкенды.
- Swappy (Android Frame Pacing) — экспортируются `UnitySwappy_version`, `UnitySwappy_injectTracer`.
- Аудио: по зависимостям игры — FMOD + SteamAudio (пространственный звук).

## Итог
Движок: **Unity 6000.3.13f1 (6.3), ревизия 3b0d9f02b51c, IL2CPP backend, ARM64, Development-конфигурация.**
