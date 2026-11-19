# Dependencies

## Нативные зависимости ELF (DT_NEEDED) — восстановлены из уцелевшей .dynamic
| Библиотека | Назначение |
|---|---|
| `libandroid.so` | Android NDK (ANativeWindow, AssetManager) |
| `libz.so` | zlib (inflate — распаковка ресурсов) |
| `libEGL.so` | EGL (контекст рендеринга) |
| `libmediandk.so` | Android Media NDK (видео/медиа) |
| `libm.so` | математика |
| `libdl.so` | динамическая загрузка |
| `libc.so` | Bionic libc |

SONAME библиотеки: **`libunity.so`**.

> Примечание: `libil2cpp.so` **не** указан в NEEDED — это ожидаемо, т.к. движок (`libunity.so`) не зависит от `libil2cpp.so`; наоборот, `libil2cpp.so` зависит от `libunity.so`. Факт того, что перед нами движок, а не игровой код, подтверждается экспортами (см. `symbols.md`).

## Управляемые зависимости игры (.NET-сборки) — извлечены из метаданных-строк
Всего уникальных `.dll`-имён: **510**. Полный список — `Extracted/assembly_manifest.txt`, организованный — `Extracted/MODULE_INVENTORY.txt`.

### Сторонние SDK / middleware (ключевое для понимания проекта)
- **Сеть/бэкенд:** Bolt (матчмейкинг), `Agones` (оркестрация dedicated-серверов), `Balders.NetCode.Transport.Enet` (ENet UDP-транспорт), `RpcSupport`, `Google.Protobuf` (отсюда `PROTOBUF` в имени файла).
- **Аналитика/маркетинг:** Firebase (Analytics, Crashlytics, Messaging, RemoteConfig), Facebook.Unity, PostHog, Adjust (AdjustSdk, AdjustOaid).
- **Google Play:** Play.Games, Play.Integrity, Play.Core, GoogleSignIn, Google.Apis.Drive.
- **Аудио:** FMODUnity, SteamAudioFMODStudio, SteamAudioUnity (пространственный звук).
- **DI / utils:** VContainer (внедрение зависимостей), PrimeTween, Newtonsoft.Json, YamlDotNet, ZString/Utf8StringInterpolation, K4os LZ4 + xxHash, JetBrains.Lifetimes.
- **UI:** Coffee.UIParticle, softmask, zxing.unity (QR), NativeGallery, NativeShare.
- **Покупки:** Purchasing.Common; интеграции GDPR, Intercom, SignInWithApple, TWA.

### Вывод о масштабе
Это **коммерческого уровня онлайн-FPS** с выделенными серверами, матчмейкингом, покупками, аналитикой и анти-читом (Play Integrity). Архитектура модульная (ECS-признаки: `Components`, `Features`, `Installers`, `Abstractions`), DI через VContainer.
