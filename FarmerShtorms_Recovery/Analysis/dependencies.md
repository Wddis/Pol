# Зависимости

## Динамические (DT_NEEDED, восстановлено из сдвинутого .dynamic)
| Библиотека | Назначение |
|---|---|
| libc.so | Bionic libc |
| libm.so | математика |
| libdl.so | dlopen/dlsym (подгрузка плагинов: FMOD, Firebase, Vivox и др.) |
| libandroid.so | ANativeWindow, ALooper, AChoreographer, ASensor |
| libz.so | zlib (inflate) |
| libEGL.so | контекст OpenGL ES |
| libmediandk.so | MediaCodec/Extractor |

`libGLESv3`, `libvulkan`, `liblog`, `libOpenSLES/AAudio` грузятся через `dlopen` (строки присутствуют).

## Нативные плагины, на которые ссылается код (по строкам P/Invoke/dlopen)
FMOD Studio (`fmodstudio`, `FMOD5_*`), Steam Audio (`ipl*`, `SteamAudioFMODStudio`), Vivox (голосовой чат),
Firebase (App, Analytics, Crashlytics, Messaging, RemoteConfig), ENet (`enet` — сетевой транспорт),
Google Play (Core, Games, Integrity), Facebook SDK, Adjust, GoogleSignIn, PhysX (встроенный в Unity), Box2D, Burst-библиотеки.

## Управляемые сборки (512 имён `.dll` в строках)
Полный список: `Extracted/assemblies_all.txt`; игровые — `Extracted/assemblies_balders.txt` (146 шт.).
Сторонние: VContainer (DI), PrimeTween, Newtonsoft.Json, YamlDotNet, Google.Protobuf, K4os LZ4/xxHash, ZString,
Utf8StringInterpolation, Flurl, PostHog, Agones (игровые серверы), FMODUnity, Coffee.UIParticle, IngameDebugConsole,
NativeGallery, NativeShare, JetBrains.Lifetimes, Unity Purchasing, Unity Services Vivox.
