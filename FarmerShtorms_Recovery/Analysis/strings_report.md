# Анализ строк

`strings -n 6`: **216 918** строк. Тематические выборки — в `Extracted/`.

| Файл | Строк | Что внутри |
|---|---|---|
| assemblies_all.txt | 512 | имена .NET-сборок |
| assemblies_balders.txt | 146 | игровые сборки `Balders.*` |
| balders_identifiers.txt | 1 540 | пространства имён / члены `Balders.*` |
| unity_icalls.txt | 5 902 | internal calls `UnityEngine.*::*` |
| pinvoke_csharp_bindings.txt | 758 | P/Invoke-обёртки (Firebase, Vivox …) |
| jni_classes.txt | 160 | Java-классы/сигнатуры для JNI |
| diagnostic_strings.txt | 3 430 | ошибки и диагностика |
| source_paths.txt | 237 | пути исходников (FMOD, PhysX, Unity) |
| urls.txt | 62 | URL |

## Игровые компоненты (только то, что реально есть в строках)

Пространство `Balders.farmershoot.*` — 110 сборок. По именам видна структура проекта:

- **Режимы игры:** `GameMode.Deathmtach` (опечатка в оригинале), `TeamDeathmatch`, `Defuse`, `RankedDefuse`,
  `Duel`, `RankedDuel`, `Tournament`, `Training`, `BaseCombat`, `ResultRound` (+ `*.Abstraction`, `*.Installers`).
- **Геймплей:** `Gameplay.Bomb`, `Gameplay.Grenades`, `Gameplay.HitBoxes`, `Gameplay.Ragdoll`, `Gameplay.Teams`,
  `Gameplay.TeamColor`, `Gameplay.Drop`, `Gameplay.WeaponMeta`, `Gameplay.InspectInHands`, `Gameplay.RadarRender`,
  `Gameplay.Features.{Damage, Weaponry, Character, Molly, GrenadeTracer, BombIndicator, CameraShake,
  GameStateMachine, NextMapVoting, Voting, StatTrack, SpectatorOutline, GyroscopeSupport, OutOfBoundsMap, GameChallenges}`.
- **Магазин оружия:** `WeaponShop`, `.Availability`, `.Controls`, `.Panels`, `.UI`.
- **Прочее:** `Replay.{Capture,Shared,Watch}`, `GameChat`, `GameCamera`, `Sound`, `DLC`, `DLCSupport.*`,
  `MetaServices`, `Metrics`, `NetCode`, `ServerPlugins.*`, `Settings`, `Startup`, `UI.HUD`, `Graffiti*`, `Lockdown`, `Level`.
- **Общая платформа `Balders.*`:** `Bolt` (бэкенд: Api, Matchmaking, Storage, Stats, RentalMarket, Subscription,
  SystemMessages …), `NetCode.Transport.Enet`, `NetCode.Serialization`, `Ballistics.*`, `AStar.*`, `Decals`, `Logging`,
  `Profiling`, `Integrations.{GDPR, Intercom, QRCode, SignInWithApple, TWA}`.

**Наблюдение:** имена описывают тактический онлайн-шутер (бомба/разминирование, гранаты, молотов, рейтинговые дуэли).
Упоминаний фермерских механик в именах сборок не найдено — проверьте, что файл действительно от вашего проекта.
Префикс `Balders` и бэкенд «Bolt» — это корпоративные пространства имён; убедитесь, что они ваши.

## Подсистемы Unity
URP (`Universal`), Vulkan/GLES, PhysX + Box2D, Terrain, ParticleSystem, Animator/BlendTree, UI Toolkit (`UIElements`),
TextCore/TMP, VideoPlayer, Burst, Input System, AudioSettings (вывод через FMOD), Unity Services Vivox, Unity Purchasing,
Play Asset Delivery (`PlayAssetDeliveryUnityWrapper`), ARCore-хуки (`ArCoreUnity_onUnityPlayerInitialize` — стандартная часть плеера).

## Android API
JNI: `com/unity3d/player/UnityPlayer`, `UnityPlayerForGameActivity`, `android/app/Activity`, `android/view/WindowMetrics`,
`java/nio/*`; NDK: Choreographer, Looper, Sensor, MediaCodec, AImageReader, AHardwareBuffer.
