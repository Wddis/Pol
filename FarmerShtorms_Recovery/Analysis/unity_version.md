# Версия Unity

| Признак | Значение | Источник |
|---|---|---|
| Версия | **Unity 6.3 — `6000.3.13f1`** | секция `.note.unity` + 5 вхождений строки в `.rodata` |
| Changeset в бинарнике | `3b0d9f02b51c` | `.note.unity`: `6000.3.13f1_3b0d9f02b51c` |
| Публичный changeset этой версии | `8c4f11e4fb20` | Unity Release API (services.api.unity.com) |
| Скриптовый бэкенд | **IL2CPP** | см. ниже |
| Отдельный файл символов | `libunity.sym.so` | `.gnu_debuglink` |

**Достоверность версии: высокая (~99 %).** Строка находится в служебной ноте, которую пишет сборочная система Unity.

**Важно:** changeset `3b0d9f02b51c` ≠ публичному `8c4f11e4fb20`. Это означает одно из двух:
собственная сборка движка из исходников (Unity Source Code license) или внутренний/патченный билд.
Следствие: **эталонный libunity.so из официального установщика побайтово совпадать не будет**,
но всё равно пригоден для сравнения кода движка.

## IL2CPP или Mono?

Вывод сделан **не** по имени `libunity.so`, а по содержимому:

**За IL2CPP:**
- строки рантайма IL2CPP: `Il2CppFullySharedGenericType`, `Il2CppExceptionWrapper`, `Il2CppComObject`,
  `Il2CppEagerStaticClassConstructionAttribute`, `IL2CPP:WindowsImpersonationContext::*`, `Cannot initialize array containing references`;
- строка `global-metadata.dat` — файл метаданных IL2CPP;
- ~758 C#-обёрток P/Invoke (`Firebase_*_CSharp_*`, `CSharp_UnityfServicesfVivox_*`) и 5 900 имён internal call
  (`UnityEngine.X::Method(System.Single)`) — в формате, который генерирует il2cpp.exe;
- имена игровых сборок и пространств имён (`Balders.farmershoot.*`, `Balders.Bolt.*` и т.п.) в `.rodata`;
- огромный `.text` (111 МБ) — на порядок больше, чем у «чистого» движка (обычно 20–35 МБ).

**Против Mono:** нет экспортов/импортов `mono_*`, нет `libmonosgen-2.0.so` в зависимостях.
`MONO_*` встречается лишь в константах вида `MONO_REFLECTION_SERIALIZER` и именах аудио-кодеков Vivox.

**Особенность:** экспортов `il2cpp_*` нет (экспортировано всего 7 функций). Похоже, рантайм IL2CPP и
сгенерированный C++-код игры **статически слиты в один `libunity.so`**, а не лежат в отдельном
`libil2cpp.so`. Так бывает при сборке движка из исходников / при объединённой линковке.
Достоверность этого вывода — **средняя (~75 %)**: возможна и иная схема, при которой часть кода
всё же находится в отдельном `libil2cpp.so` APK.
