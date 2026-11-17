# Версия Unity

| Вывод | Достоверность |
|---|---|
| **Unity 6000.3.13f1 (Unity 6.3)**, changeset `3b0d9f02b51c` | **Высокая (~95 %)** |

Доказательства (`Extracted/version_strings.txt`):
- `6000.3.13f1 (3b0d9f02b51c)` — стандартная строка версии плеера;
- `6000.3.13f1_3b0d9f02b51c`;
- `6000.3.13f1_patched` — суффикс говорит о **модифицированном (пропатченном) движке** или скрипте сборки.
Одна строка `2018.3.0a1` — это порог совместимости сериализации, а не версия сборки.

## Scripting backend: IL2CPP (подтверждено независимо от имени файла)
- 63 строки IL2CPP-рантайма: `IL2CPP Root Domain`, `il2cpp.usym`, `Extracting il2cpp resources`, сообщения о неподдерживаемых icall с путями `.../il2cpp/libil2cpp/icalls/mscorlib/...`.
- Строки `global-metadata.dat`.
- Нет признаков Mono (`mono_jit_*`, `mono_domain_*` отсутствуют).
- **Важно:** в файле одновременно есть код движка (UnityPlayer, `UnityFS`, PhysX и т. д.) и сгенерированный IL2CPP-код игры. Это **объединённая библиотека** (libunity + libil2cpp, путь `TempMerger`, сборка `Axbot.Merger.Runtime.dll`). Отдельный `libil2cpp.so`, скорее всего, не существовал: искать нужно именно `global-metadata.dat`.
- Экспортов `il2cpp_init` / `il2cpp_domain_get` в строках нет: при слиянии их, видимо, скрыли или убрали.
