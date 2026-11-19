# FarmerShoot Recovery — полный журнал сессии и база знаний

Единый документ со всем, что установлено и сделано. Подходит как точка входа для продолжения
в новой сессии. Дата: 2026-11-19. Ветка: `ccr-ec144e92-pu58np`.

---

## 0. Краткое резюме (TL;DR)

- Полученный файл `test.so` (208 МБ) — это **движок Unity `libunity.so`**, НЕ код игры.
- Движок: **Unity 6000.3.13f1 (6.3)**, rev `3b0d9f02b51c`, backend **IL2CPP**, ARM64, dev-сборка.
- Файл **повреждён** правками пользователя: затёрта таблица секций, смещён указатель `PT_DYNAMIC`,
  и данные **прогрессивно сдвинуты** вставками байт. Содержимое (код, релокации) цело.
- **Почин ELF сделан** (копия загрузочна). Чинить оффсеты в test.so смысла нет — см. ниже.
- На телефоне установлена **подлинная оригинальная игра** (Build-ID совпал). Оригинал — чистый.
- **`global-metadata.dat` в test.so НЕ вшита** (проверено 4 способами, включая энтропию и XOR).
- **`libil2cpp.so` и `global-metadata.dat` в читаемых APK игры НЕ найдены** — вероятно, приватное
  хранилище/анти-чит. Без них восстановить игровую ЛОГИКУ нельзя.
- Собрано Android-приложение **Farmer SO Verifier (v1→v4)**, которое всё это проверяет на телефоне.

---

## 1. Идентификация файла

| Поле | Значение |
|---|---|
| Имя | `libunity_farmershoot_balders_v3_PROTOBUF_TEST.so` |
| Размер | 217 742 416 байт (207.6 МБ) |
| SHA256 (оригинал) | `b65177bd30395ce02d5e645f746871290c890b744afa8976e3361660d94d8e14` |
| MD5 | `63d3eb83bddf978af67158b595f6015b` |
| SHA256 (почин. копия) | `11c86ee2cd6cab27bba824fba41dbfb87e78f3d3c5fac732b4f186a08923782d` |
| GNU Build-ID | `69815f2befe135c2` |
| ELF | ELF64, little-endian, DYN, AArch64 (arm64-v8a) |
| SONAME | `libunity.so` |
| NEEDED | libandroid.so, libz.so, libEGL.so, libmediandk.so, libm.so, libdl.so, libc.so |

**Экспорты (определяют природу = движок):** `JNI_OnLoad`, `JNI_OnUnload`, `UnitySendMessage`,
`UnitySwappy_version`, `UnitySwappy_injectTracer`, `UnityInitialize` (vaddr 0x5acb728), `UnityShutdown`.
Ни одного `il2cpp_*` / `mono_*` экспорта → это движок, а не `libil2cpp.so`.

**Версия Unity:** строка `6000.3.13f1` / `6000.3.13f1_3b0d9f02b51c` / `6000.3.13f1_patched`.
Backend IL2CPP (строки `[libil2cpp]`, `External/il2cpp/builds/...`), Mono нет. Dev-сборка (`development`×13,
ссылки на `Assembly-CSharp-testable`, тест-фреймворки; размер 208 МБ).

---

## 2. Повреждения test.so и ремонт

**Три зоны повреждения (следы правок пользователя):**
1. **Таблица секций** в хвосте затёрта текстом/мусором (`20 20 20 20`=пробелы, `30 30`=«0»,`"sive"`).
   Для загрузки некритично (Android читает сегменты, не секции), но ломает `nm`,`objdump -d`,`readelf -S`.
2. **Указатель `PT_DYNAMIC`** указывал на обнулённую зону `0xc89fc28`. Реальная `.dynamic` цела по
   **file offset `0xc8a05b4`** (vaddr `0xc8a85b4`).
3. **Прогрессивный сдвиг данных**: в несколько мест вставлены лишние байты (подтверждено сверкой —
   см. §5). Поэтому file-оффсеты «уехали».

**Что цело:** код (RX), данные (RW), таблица релокаций (**1 140 327** записей `R_AARCH64_RELATIVE`,
проверены начало/середина/конец), `.dynamic`, адреса символов.

**Ключевые значения `.dynamic` (из уцелевшей таблицы):**
`SYMTAB 0x288 · STRTAB 0x2240 · STRSZ 0x141b · GNU_HASH 0x2200 · RELA 0x3660 · RELASZ 0x1a199a8 ·
RELAENT 24 · RELACOUNT 1140294 · JMPREL 0x1a1d008 · PLTRELSZ 0x1a28 · PLTGOT 0xc993520 ·
INIT_ARRAY 0xc8a6c18 · INIT_ARRAYSZ 0x1010 · VERSYM 0x1f08 · VERDEF 0x2168 · VERNEED 0x21a0`.

**Ремонт (на копии, `Reconstructed/libunity.repaired.so`):** переписан `PT_DYNAMIC`→`0xc8a05b4`,
пересобрана корректная таблица из 15 секций. Проверка: `file`,`readelf -S/-d`,`nm -D` — без ошибок.
Экспорт `UnityInitialize` по 0x5acb728 указывает на реальный ARM64-код
(`cmp w12,#0x1a; sub w12,w10,#0x41; … b.ne; add; cmp x8,x9`).

> ВАЖНО: чинить оффсеты именно в test.so бессмысленно — оригинал (чистый) уже есть на телефоне.

---

## 3. Поиск global-metadata.dat в test.so — ОТСУТСТВУЕТ

Пользователь считал, что вшивал metadata (в т.ч. шифровал) в этот lib. Проверено 4 способами — нет:
1. Магия `0xFAB11BAF` (LE/BE) — 0.
2. Магия под однобайтовым **XOR** — только случайные совпадения с разными ключами (шум).
3. Сигнатуры сжатия (LZ4/zstd/gzip) — нет (хотя LZ4 есть в зависимостях игры).
4. **Карта энтропии всех 208 МБ** — ни одного окна >7.3 (строки 4.4–5.4, код 6.8–6.9).
   Зашифрованный/сжатый блок дал бы ~7.9–8.0 → его НЕТ.

Также: структур регистрации IL2CPP (`g_CodeRegistration`,`Il2CppClass`,`Il2CppCodeGenModule`) — 0;
сигнатур методов игровых типов — 0. Регион 27.4–49.1 МБ — это **только строки-ИМЕНА** (плотность ~90% ASCII):
имена движковых типов (`m_Camera`,`::Scripting::Unity::…`) + имена игровых сборок/типов.

---

## 4. Архитектура проекта (из имён-строк; это НЕ исходный код)

Кодовое имя **`farmershoot`**, студийный неймспейс **`Balders.*`**. Крупный онлайн-FPS.
Извлечено: **510 .NET-сборок**, **2872 идентификатора**. Полные списки:
`Extracted/MODULE_INVENTORY.txt`, `Extracted/assembly_manifest.txt`, `Extracted/balders_identifiers.txt`.

**Режимы:** RankedDefuse, Defuse, (Team)Deathmatch, Duel/RankedDuel, Tournament, Training, AimTraining.
**Боевка:** Ballistics (Simulation/GrenadeLogic/GrenadePractice), Molotov, Bomb(Detonation), HitBoxes,
Damage, Ragdoll, Recoil/AimDispersionCurve, ShooterId, HitParameters.
**Системы:** GameStateMachine, AStar (патфайндинг), Radar/RadarRender, SpectatorOutline, KillCamera,
Replay (Capture/Watch), NextMapVoting, StatTrack, WeaponShop (AutoBuy/Chargeback/Categories), Graffiti.
**Сеть:** NetCode (Enet-транспорт, Encryption, Quantisation), Matchmaker (Client/Contracts),
ServerPlugins, Agones (dedicated-серверы), Bolt (матчмейкинг), RpcSupport, Google.Protobuf.
**SDK:** Firebase (Analytics/Crashlytics/Messaging/RemoteConfig), Facebook, Google Play Games/Integrity,
FMOD+SteamAudio (простр. звук), Adjust, PostHog, VContainer (DI), PrimeTween, Newtonsoft, YamlDotNet,
ZString, K4os LZ4+xxHash, Purchasing. Архитектура: ECS-признаки (Components/Features/Installers/Abstractions).

---

## 5. Проверка на телефоне (приложение-верификатор)

**Build-ID установленной игры совпал** (`69815f2befe135c2`) → оригинал подлинный.

**Сдвиг test.so относительно оригинала (растёт → вставки байт):**
| Якорь | оффсет в test.so | реальный в оригинале | Δ |
|---|---|---|---|
| UnityInitialize | 8855 | 8855 | 0 (до вставок) |
| RadarView | 46402888 | 46402548 | −340 |
| RadarMapView | 46404335 | 46403987 | −348 |
| RadarConfiguration | 47093719 | 47092970 | −749 |
| AimDispersionCurve | 47274569 | 47273752 | −817 |

- `Balders.farmershoot.Game` в ОРИГИНАЛЕ **не найден**, хотя в test.so есть → список имён игровых
  сборок, вероятно, **сам вшивал пользователь** (эксперименты `PROTOBUF`/`v3`).
- **`libil2cpp.so` и `global-metadata.dat` в читаемых APK НЕ найдены** (кнопки проверки/извлечения).
  Гипотеза: приватное хранилище `/data/data/<pkg>/` или загрузка/дешифровка при старте (анти-чит
  Play Integrity) → без root недоступны. Нужна «Инвентаризация» (v3) для точной картины.

**Эталонные якоря (имя → оффсет в test.so), вшиты в приложение:**
`UnityInitialize=8855, WeaponShop=27422552, RankedDefuse=27568339, GameStateMachine=27568478,
RadarView=46402888, RadarMapView=46404335, RadarConfiguration=47093719, HitParameters=47266316,
ShooterId=47266330, AimDispersionCurve=47274569`.

### 5a. ПОДТВЕРЖДЁННАЯ сверка на устройстве (v4, финал) — 10/10 ✓

Build-ID ✓. Все 10 значений найдены в оригинале. Δ всегда отрицательная → в test.so ВСТАВЛЕНЫ байты
(подтверждает «вшивал»). Карта накопления сдвига:

| Якорь | test.so оффсет | реальный | Δ | прирост вставок |
|---|---|---|---|---|
| UnityInitialize | 8855 | 8855 | 0 | — |
| WeaponShop | 27422552 | 27422544 | −8 | +8 |
| RankedDefuse | 27568339 | 27568314 | −25 | +17 |
| GameStateMachine | 27568478 | 27568447 | −31 | +6 |
| RadarView | 46402888 | 46402548 | −340 | +309 |
| RadarMapView | 46404335 | 46403987 | −348 | +8 |
| RadarConfiguration | 47093719 | 47092970 | −749 | +401 |
| HitParameters | 47266316 | 47265505 | −811 | +62 |
| ShooterId | 47266330 | 47265519 | −811 | +0 |
| AimDispersionCurve | 47274569 | 47273752 | −817 | +6 |

ИТОГ: подтверждено 10/10, без сдвига 1 (UnityInitialize), Build-ID ✓.
ВЫВОД ЗАКРЫТ: значения ПОДЛИННЫ, test.so = оригинал + вставленные байты (+ битый header).
В оригинале по этим местам видны настоящие C#-строки (`<...>k__BackingField`, `_BackingField`,
`WeaponShop.Control`, `Standoff.Gameplay`) → оригинальный libunity.so несёт богатые managed-метаданные.

> Вопрос «достоверны ли оффсеты/значения» — ЗАКРЫТ: да. Дальнейший прогресс — только через
> libil2cpp.so + global-metadata.dat (см. §8), т.к. в движке игровой логики нет.

---

## 6. Приложение Farmer SO Verifier (исходники: `Verifier_App/`)

Лёгкий (≈21 КБ) APK, без root, читает публичный APK установленной игры. Эволюция:
- **v1** — проверка фиксированных оффсетов (дала ✗ из-за сдвига test.so).
- **v2** — поиск якорей ПО СОДЕРЖИМОМУ в оригинале + показ настоящих оффсетов; проверка наличия
  libil2cpp.so/global-metadata.dat; кнопка извлечения.
- **v3** — «Инвентаризация»: список всех `.so` и `metadata/il2cpp/.dat/assets` во всех сплитах APK.
- **v4** — «СВЕРИТЬ ВСЁ»: таблица по 10 якорям (test.so-значение | что в оригинале по тому же
  оффсету | реальный оффсет + Δ). Результат сохраняется в `compare.txt`/`inventory.txt`.

Пакет приложения: `com.recovery.sofarmerverify`. Разрешение: `QUERY_ALL_PACKAGES`.
Извлечение/отчёты пишутся в `Android/data/com.recovery.sofarmerverify/files/`.

### Опыт сборки (важно для повторения)
- Среда: JDK 21 + gradle + keytool есть; Android SDK ставился руками
  (`commandlinetools` → `sdkmanager` → `build-tools;34.0.0` + `platforms;android-34`), SDK в `/opt/android-sdk`.
- Сборка вручную: `aapt2 link` (manifest) → `javac -source 8 -target 8` → `d8` → добавить `classes.dex`
  в apk (python zipfile) → `zipalign -f 4` → `apksigner` (debug-ключ `debug.ks`, pass `android`).
- Перед `d8`/`javac`: `unset _JAVA_OPTIONS JAVA_TOOL_OPTIONS` (в среде стоят опции, мешающие).
- **БАГ d8 8.2.2 (build-tools 34) + javac 21:** падает `NullPointerException: String.length() … <parameter1>`
  на классах с «новыми» атрибутами. Триггерят: **анонимные классы**, **нестатические внутренние классы**
  (synthetic this$0), **реализация дженерик-интерфейса** (`Comparator<Integer>` → bridge-метод).
  **Решение:** все вспомогательные классы делать `static`, без анонимных, без implements generic-интерфейса
  (сортировать индексы вручную). Альтернатива (не проверялась) — поставить `build-tools;35.0.0` (новее d8).

---

## 7. Честные выводы

- Из `test.so` (движок) **игровую логику/механики восстановить НЕЛЬЗЯ** — её там нет.
- Оффсеты/значения **движка** подлинны; оффсеты **игры** — это только имена (без привязки к коду).
- Для реального восстановления нужны **`libil2cpp.so` + `global-metadata.dat`** (код + карта типов) →
  тогда Il2CppDumper/Cpp2IL дадут классы/методы/поля и псевдокод. Либо **папка Unity-проекта** (настоящий C#).
- Эти файлы НЕ в test.so и пока НЕ найдены в читаемых APK установленной игры.

---

## 8. Следующие шаги

1. На телефоне (v3) нажать **«Инвентаризация файлов игры»** → получить `inventory.txt`
   (все `.so` и metadata-файлы). Определить, где `libil2cpp.so`/`global-metadata.dat` и достаём ли без root.
2. (v4) **«СВЕРИТЬ ВСЁ»** → зафиксировать достоверность (ожидается ✓, значения подлинны со сдвигом).
3. Если файлы в приватном хранилище/зашифрованы → без root не извлечь из чужого процесса. Легальные пути:
   - **собственный APK/AAB** игры (бэкап телефона, почта/мессенджер, Google Play Console разработчика);
   - **папка Unity-проекта** со старого ПК/диска/облака/Git (там `Assembly-CSharp.dll` и настоящий C#).
4. Как только будут `libil2cpp.so + global-metadata.dat` ЛИБО APK — прислать: прогнать Il2CppDumper/Cpp2IL,
   сопоставить с уже восстановленным манифестом модулей (§4), получить карту кода.

---

## 8a. Исследование no-root пути (GitHub + searchcode) — ПОДТВЕРЖДЕНО

Идея «патч/чтение значения по оффсету в рантайме» — это стандартный механизм всего il2cpp-тулинга.
Почему `libil2cpp.so`/`global-metadata.dat` не видны в APK: их **шифруют и расшифровывают в памяти**
при старте (анти-чит). Достаются **дампом из памяти процесса**.

**Инструменты восстановления (офлайн, запускаются в этом контейнере):**
- `Perfare/Il2CppDumper` (9.7k★) — вход: `libil2cpp.so` + `global-metadata.dat` → `dump.cs`
  (все классы/методы/поля/оффсеты) + `script.json` для IDA/Ghidra.
- `djkaty/Il2CppInspector` (3.1k★), `SamboyCoding/Cpp2IL` (3.1k★) — авто-реконструкция, .NET-заглушки.
- `OsOmE1/Beebyte-Deobfuscator` — деобфускация имён (строки вида `c skdv vk` в оригинале → вероятно Beebyte).

**Добыча скрытых файлов из памяти:**
- `IIIImmmyyy/frida-il2cppDumper`, `MlgmXyysd/Il2CppMemoryDumper`, `Perfare/Zygisk-Il2CppDumper` —
  снимают расшифрованные `libil2cpp.so`+`global-metadata.dat` из памяти (даже «decrypt-on-run»). Требуют **root** (Riru/Zygisk).
- **NO-ROOT:** `frida-gadget`, вшитый в СВОЙ же APK (`sensepost/objection` → `objection patchapk`): frida
  работает внутри приложения без root. Легально — игра пользователя.

**Среда готова:** clang 18 + lld умеют собирать aarch64-`.so` без NDK; Android SDK/build-tools стоят.

### Каветы no-root frida-gadget пути (честно)
- Репак+переподпись ломают подпись → онлайн/анти-чит (Play Integrity) не пустят в игру, и агрессивный
  анти-чит может убить процесс на старте (тогда дамп не выйдет). Но дамп метаданных идёт на init, часто
  ДО серверной авторизации — есть шанс успеть.
- Намного проще, если у пользователя есть **свой исходный проект / AAB / development-билд** — тогда
  frida не нужна вовсе. Это приоритетный вопрос к пользователю.

## 8b. Пайплайн восстановления (план)
1. (v5) Извлечь APK игры с телефона → прислать.
2. Проверить APK напрямую: есть ли `libil2cpp.so`/`global-metadata.dat` (может, просто под другим
   именем/в сплите — наш поиск по имени мог промахнуться; инвентаризация покажет точно).
3. Если скрыты/зашифрованы: собрать здесь APK с `frida-gadget` (objection, no-root) → пользователь
   ставит и запускает на телефоне → дамп расшифрованных `libil2cpp.so`+`global-metadata.dat` из памяти.
4. Прогнать `Il2CppDumper`/`Il2CppInspector`/`Cpp2IL` → `dump.cs` + Script/ (классы для Unity).
5. При обфускации — `Beebyte-Deobfuscator`.
6. Сопоставить с манифестом модулей (§4) → карта кода проекта.

## 9. Карта файлов репозитория (`FarmerShoot_Recovery/`)

```
Original/            оригинал test.so (read-only, gitignored) + SHA256/MD5
Reconstructed/       libunity.repaired.so (gitignored) + REPAIR_NOTES.md + SHA256
Analysis/            elf_report, unity_version, architecture, dependencies, symbols,
                     strings_report, recovery_possibilities (.md) + сырые 0x-дампы
Extracted/           MODULE_INVENTORY, assembly_manifest, balders_identifiers,
                     thirdparty_sdks, gameplay_vocab, example_radar_and_values (.txt)
Documentation/       missing_files.md, project_architecture_reconstructed.txt
Reports/             00_MASTER_REPORT.md, 01_NEXT_STEPS.md
Verifier_App/        исходники приложения (src/, AndroidManifest.xml, README.md)
SESSION_LOG.md       этот файл
```
Большие бинарники (`*.so`, `strings_full.txt`, `*.apk`, `debug.ks`, `build/`) — в `.gitignore`.
