# Недостающие файлы

| Файл | Где обычно лежит | Что содержит | Зачем нужен / что даст |
|---|---|---|---|
| **global-metadata.dat** | APK: `assets/bin/Data/Managed/Metadata/global-metadata.dat`; проект: `Library/Bee/artifacts/.../il2cppOutput/data/Metadata/` | Все имена типов, методов и полей, сигнатуры, строковые литералы C#, атрибуты | **Ключевой файл.** Вместе с целой `.so` даёт через Il2CppDumper/Cpp2IL полную структуру классов (dummy-DLL), имена функций для Ghidra/IDA и строки. Должен быть из **той же сборки** |
| **libil2cpp.so** | APK: `lib/arm64-v8a/` | Скомпилированный C# | Здесь он **влит в Test.so** (TempMerger). Нужна **неповреждённая** копия этой же `.so` из APK |
| **libunity.so** (целая) | APK: `lib/arm64-v8a/libunity.so` | Движок (+ IL2CPP в этой сборке) | Заменяет повреждённую копию: в целой копии есть DYNAMIC и регистрационные таблицы |
| **Assembly-CSharp.dll** и `Axbot.*.dll` | Проект: `Library/ScriptAssemblies/`, `Library/Bee/PlayerScriptAssemblies/`, `Temp/StagingArea/Data/Managed/` | Управляемый IL с телами методов | **Лучший вариант:** ILSpy/dnSpy дают почти исходный C#. В APK их нет (там IL2CPP) |
| **resources.assets** | APK: `assets/bin/Data/` | Ассеты из `Resources/` | Текстуры, префабы, ScriptableObject (AssetRipper) |
| **globalgamemanagers** (+ `.assets`) | APK: `assets/bin/Data/` | Player/Quality/Graphics/Physics/Tags/Layers settings, список сцен, ссылки на скрипты | Восстановление ProjectSettings и связи MonoBehaviour ↔ класс |
| **boot.config** | APK: `assets/bin/Data/boot.config` | Параметры запуска плеера (графика, job-потоки, IL2CPP-флаги) | Подтверждение настроек сборки |
| `level0..N`, `sharedassets*.assets` | APK: `assets/bin/Data/` | Сцены и их ассеты | Карты и уровни |
| `data.unity3d` / `.bundle` / OBB / AAB-ассет-паки | APK/OBB, `StreamingAssets/`, CDN | Сжатые бандлы, DLC (в проекте есть `DLCSupport.Providers.StreamingAssets`) | Основная масса контента |
| Сам **APK/AAB/XAPK** | Google Play, бэкапы, CI-артефакты, телефон (`adb pull`) | Всё перечисленное | Один файл закрывает почти весь список |
| Исходный репозиторий | Git (GitHub/GitLab/Bitbucket), CI-сервер (`/workspace/farmershoot`), ПК разработчиков | Исходники, ассеты | Единственный путь к **настоящему** проекту |

Где искать: старые ПК и диски (`Library/`, `Temp/`, `Builds/`), облака, почта и мессенджеры (отправленные APK), CI-артефакты, телефон с установленной игрой (`adb shell pm path <package>` → `adb pull`), команда Axbot.
