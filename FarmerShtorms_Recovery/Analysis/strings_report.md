# Анализ строк
Всего ASCII-строк ≥ 4 символов: 736 548 (`Extracted/all_strings_offsets.txt`, со смещениями).

## Unity Engine
Внутренние вызовы движка (`UnityEngine.TerrainData::...`, `AudioSettings::GetSampleRate()`), работа с бандлами (`UnityFS`, «Copying data failed when creating archive file»), PlayerLoop (`farmershootPlayerLoop`, `farmershootPreLateUpdate`, `farmershootPreDirector` — **собственные этапы PlayerLoop проекта**).

## Android
230 строк `android/...` (JNI-классы), `com/unity3d/...`, GameActivity, ANativeWindow.

## Ошибки и диагностика
Сообщения IL2CPP о неподдерживаемых icall, ошибки маршалинга, «Forcing a crash -- Intentionally Dereferencing NULL pointer», «Temporary cache directory '%s' exists».

## Игровые компоненты (реальные имена из бинарника)
**Меню создания ассетов** (`Extracted/asset_menu_paths.txt`, 25 шт.): `farmershoot SDK/Gun Definition`, `Gun Parameters`, `ShotGun Parameters`, `Knife Parameters`, `Grenade Definition/Parameters`, `Bomb Parameters`, `Armor Definition`, `Create SkinDefinition/SkinTemplate/GlovesDefinition`, `Create WeaponMeshDefinition/WeaponSoundParameters/WeaponAnimationParameters/WeaponLodParameters`, `Weapon Effects/Create Muzzle Flash|Trace|GunShells|Grenade Trail|Grenade Detonation Effect Definition`, `Character/Default|Custom Character Definition`, `Character Effects/Create Blood Definition`.

**Режимы игры** (сборки): Deathmatch (`Deathmtach` — опечатка в оригинале), TeamDeathmatch, Defuse, RankedDefuse, Duel, RankedDuel, Tournament, Training, BaseCombat, ResultRound.

**Группы сборок `Axbot.farmershoot.*`:** Gameplay (37), GameMode (24), WeaponShop (5), UI (4), ServerPlugins (3), Replay (3), Metrics, MetaServices, DLCSupport, Content, GameCamera, GameChat, Inputs, Level, Sound, Startup, Lockdown, GraffitiPicker/Preview и др.

**Общие модули Axbot:** AStar (поиск пути), Ballistics (баллистика, гранаты), Decals, Gameplay.Effects (BombDetonation, MolotovExtraVisuals, Graffiti, CharacterEffects), NetCode.Serialization, Obstruction, FpsMetrics.

**Сервисы:** `farmershoot.Matchmaker.Client` (HTTP + Server-Sent Events) и `Contracts`, клиент для Agones. Встречаются идентификаторы `Get*Request/Response` (`GetMatchRequest`, `GetClanMatchesRequest`, `GetPlayerBanResponse`, `ConfirmAppStorePurchaseAsync`), мапперы `_bolt*Mapper`, кланы, рефералы, starter pass / daily tasks, баны и репорты.

Примеры классов: `WeaponBuyControlView`, `CombatReportDamageDetailsView`, `SpectatePlayerInfoView`, `ClownGrenadeParameters`, `TeamMVPEvent`, `ClientCharacter`, `CollimatorTacticalWeaponNode\`3`.
Полный отфильтрованный список: `Extracted/gameplay_keyword_identifiers.txt` (9273).

**Признаков «фермы»** (Farm*, Crop, Harvest, Animal) в коде **нет**: 16 вхождений «Farm» — это части слова `farmershoot`.
