# String Analysis

Полный дамп: `Analysis/strings_full.txt` (216 919 строк, `strings -t d -n 6`).

## Категории найденного

### 1. Версия/движок
- `6000.3.13f1`, `6000.3.13f1_3b0d9f02b51c`, `6000.3.13f1_patched`, `Unity Technologies`.

### 2. IL2CPP-инфраструктура движка (не игровой код)
- `Extracting il2cpp resources`, `re-extract il2cpp resources on next run`, `[libil2cpp]`, `IL2CPP Alloc/Calloc called with %d bytes`, `N6il2cpp2vm6Thread26NativeThreadAbortE`.
- Пути сборки: `External/il2cpp/builds/external/baselib/...`, `/TempMerger/il2cpp/libil2cpp/vm/Reflection.cpp`, `.../icalls/mscorlib/System...`.

### 3. Управляемые метаданные-ИМЕНА игры (регион offset 27 399 243 – 49 133 357, ~21 МБ)
Это самое ценное для реконструкции структуры. Содержит ТОЛЬКО имена (без тел методов, без таблиц регистрации):
- **510 имён .NET-сборок** (`*.dll`) — манифест модулей.
- **2872 уникальных идентификатора `Balders.*` / `farmershoot.*`** — неймспейсы и типы.
- Сигнатуры методов — **только для `UnityEngine.*`** (биндинги движка, напр. `UnityEngine.TextCore.Text.FontAsset::UpdateBoldWeight(System.IntPtr,System.Single)`). Сигнатур игровых методов (`Balders...::Method`) — **0**.

### 4. Игровая лексика (реальные идентификаторы)
- Режимы: `RankedDefuse`, `Defuse`, `Deathmtach`(sic)/`TeamDeathmatch`, `Duel`/`RankedDuel`, `Tournament`, `Training`, `AimTraining`.
- Боевка: `Ballistics`, `GrenadeLogic`, `GrenadePractice`, `MolotovExtraVisuals`, `BombDetonation`, `HitBoxes`, `Damage`, `Ragdoll`, `Recoil`/`AimDispersionCurve`, `ShooterId`, `HitParameters`.
- Фичи: `GameStateMachine`, `AStar`(патфайндинг), `Radar`/`RadarRender`, `SpectatorOutline`, `KillCamera`, `ActionCamera`, `Replay`(Capture/Watch), `NextMapVoting`, `StatTrack`.
- Управление: `ShootNShiftButton`, `IsShootAndShiftButtonEnabled`, `AimUpDown`, `GyroscopeSupport`.
- Экономика/магазин: `WeaponShop` (AutoBuy, Chargeback, Categories, Availability), `WeaponMeta.Registry`, `Graffiti`, `Skin`/`Loadout`-термины.

### 5. Диагностика/ошибки движка
- `Field doesn't have an RVA`, `Expected version: %s. Actual version: %s`, `IL2CPP does not support marshaling...`, `OutOfMemoryException`, `IL2CPP Threadpool worker`.

## Что строки ПОЗВОЛЯЮТ и НЕ позволяют
- ✅ Позволяют восстановить **структуру проекта**: модули, неймспейсы, имена фич/систем, перечень режимов, стек SDK.
- ❌ Не позволяют восстановить **логику**: нет тел методов, нет сигнатур игровых методов, нет полей со смещениями, нет связи имя→код (нет таблиц регистрации IL2CPP).
