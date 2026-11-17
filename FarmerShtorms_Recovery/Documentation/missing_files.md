# Файлы, которые нужно найти

Пути даны для APK (`unzip app.apk`) и установленного приложения (`/data/app/~~xxx/<package>-yyy/`).
Сначала проверьте: Google Play Console → *App bundle explorer* (можно скачать загруженный AAB/APK),
CI-артефакты, старые ПК, облачные диски, установленную игру на старом телефоне (`adb shell pm path <package>` → `adb pull`).

| Файл | Где лежит | Что содержит | Почему важен | Что даёт |
|---|---|---|---|---|
| **libil2cpp.so** | `lib/arm64-v8a/libil2cpp.so` | весь C#-код, скомпилированный в ARM64 + рантайм IL2CPP | основной носитель логики игры | вместе с metadata — имена и адреса всех методов, псевдокод |
| **global-metadata.dat** | `assets/bin/Data/Managed/Metadata/global-metadata.dat` | имена типов, методов, полей, строки C#, атрибуты | без него код — безымянные инструкции | Il2CppDumper/Cpp2IL: `dump.cs`, DummyDll, скрипты Ghidra/IDA |
| **Assembly-CSharp.dll** | только в Mono-сборках (`assets/bin/Data/Managed/`) или в Library/ScriptAssemblies проекта | IL-код C# | декомпилируется ILSpy/dnSpy почти в исходник | почти полное восстановление скриптов. **В IL2CPP-APK его нет** |
| **globalgamemanagers** | `assets/bin/Data/globalgamemanagers` | ProjectSettings: список сцен, теги, слои, Quality, Physics, Player Settings, версия | «скелет» проекта | восстановление настроек и списка сцен (AssetRipper) |
| **globalgamemanagers.assets** | там же | ресурсы, на которые ссылаются менеджеры | дополнение к предыдущему | — |
| **resources.assets** (+ `.resS`, `.resource`) | `assets/bin/Data/` | всё из папок `Resources/` | ассеты, грузящиеся по имени | модели, текстуры, звуки, ScriptableObject |
| **level0, level1 … / sharedassets*.assets** | `assets/bin/Data/` | сцены и их ассеты | содержимое уровней | сцены через AssetRipper |
| **data.unity3d** | `assets/bin/Data/data.unity3d` | упакованные (LZ4) все вышеперечисленные файлы в новых версиях | в Unity 6 обычно всё лежит в нём | AssetStudio/AssetRipper распаковывают |
| **boot.config** | `assets/bin/Data/boot.config` | параметры запуска плеера (gfx-jobs, скрипт-бэкенд, флаги) | подтверждает IL2CPP и настройки | точная конфигурация билда |
| **RuntimeInitializeOnLoads.json, ScriptingAssemblies.json** | `assets/bin/Data/` | список сборок и точек инициализации | сверка списка сборок | — |
| **Addressables / AssetBundles** (`aa/`, `*.bundle`, `catalog.json`) | `assets/aa/Android/` или CDN/DLC-сервер | контент DLC (`DLCSupport` в коде) | часть контента могла грузиться с сервера | ассеты DLC |
| **libunity.sym.so** | не в APK; у Unity/в папке билда `*-symbols.zip` | символы движка | упомянут в `.gnu_debuglink` | имена функций движка |
| **\*-IL2CPP symbols / `*.symbols.zip`** | рядом с APK/AAB после билда | символы libil2cpp.so | — | имена C++-функций сгенерированного кода |
| **classes.dex, AndroidManifest.xml** | корень APK | Java/Kotlin-часть, package name, разрешения | нужен для запуска | jadx — Java-код плагинов |
| **lib/arm64-v8a/\*.so** остальные | `libmain.so`, `libfmod*.so`, `libFirebaseCppApp*.so`, `libvivox*.so`, `libphonon.so` | нативные плагины | без них игра не стартует | — |
| **Исходный проект Unity** | git/бэкап: `Assets/`, `Packages/`, `ProjectSettings/` | оригинальные исходники | единственный путь к оригинальному C# | полное восстановление |

**Приоритет поиска:** 1) исходный проект/git → 2) AAB/APK целиком → 3) пара `libil2cpp.so` (или этот libunity.so) + `global-metadata.dat` того же билда → 4) `data.unity3d`/`*.assets`.
