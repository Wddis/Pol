# ATLANT SPACE — Handoff для продолжения в новой сессии

> Этот файл — ввод в курс дела. Смена модели произошла по системным причинам,
> код не потерян и лежит в ветке `ccr-7aa21a7f-tffwj1`. Ниже — что это, что уже
> сделано, что делать дальше и как собрать/запустить.

## Что это за проект

**ATLANT SPACE** — лёгкая платформа виртуализации Android-приложений без root на
хосте (аналог класса VirtualApp / MultiSpace, но с оригинальной архитектурой и
UI). Задача: запускать сторонние APK внутри изолированных «пространств» внутри
одного обычного приложения.

Ключевые требования из ТЗ:
- **НЕ полноценная VM**, а лёгкая in-process виртуализация (App Container).
- Размер release APK **≤ 100 МБ** (цель — заметно меньше).
- Без root на хосте, без разблокировки загрузчика, без Magisk.
- Несколько независимых пространств со своими данными, идентичностью, разрешениями.
- Своя виртуальная ФС, менеджер приложений (APK/split/XAPK), Root Compatibility Engine,
  модуль Google-сервисов, бэкапы, файловый менеджер, монитор процессов/памяти, журнал.
- Дизайн: тёмный, премиальный, акцент лайм `#B7F34A`. НЕ копировать MultiSpace.
  Полная палитра — в разделе «Дизайн» ТЗ (см. ниже в этом файле).

## Структура Gradle-проекта

Корень проекта: **`atlant-space/`** (внутри репозитория `Wddis/Pol`).
Исходный `bot.py` / Procfile в корне репо — к ATLANT SPACE отношения не имеют,
не трогать.

- `:engine` — Android library, вся логика виртуализации (Kotlin + будущий native).
- `:app` — приложение/UI (пока только каркас каталогов, модуль ещё НЕ включён в
  `settings.gradle.kts` — там временно `include(":engine")`, т.к. app ещё не написан).
- Gradle 8.14.3 (wrapper закоммичен), AGP 8.11.1, Kotlin 2.2.0, compileSdk 36,
  minSdk 26, NDK 27.3.13750724.

## Окружение сборки (в этом контейнере)

- Android SDK установлен в **`/opt/android-sdk`** (`local.properties` указывает туда;
  он в .gitignore, в новой сессии создать заново: `echo "sdk.dir=/opt/android-sdk" > local.properties`).
  Установлены: platform-tools, platforms;android-36, build-tools;36.0.0,
  ndk;27.3.13750724, cmake;3.22.1, emulator, system-images;android-33;aosp_atd;x86_64.
- JDK 21 (`/usr/bin/java`). Gradle также в `/opt/gradle`.
- **Эмулятор**: AVD `atl` (API 33, aosp_atd, x86_64), запускается БЕЗ ускорения
  (нет /dev/kvm): `emulator -avd atl -no-window -no-audio -no-snapshot -accel off
  -gpu swiftshader_indirect`. Загружается медленно (1-2 мин). adb в
  `/opt/android-sdk/platform-tools`.
- **Тестовый APK** для парсера/установщика: референсный MultiSpace лежит в
  `/root/.claude/uploads/9045b29a-9a97-5c61-b205-721d5ae2660b/9b48ff78-MultiSpace_1.1.4.apk`
  (в новой сессии путь к загрузке может отличаться — это просто произвольный APK для тестов).
- Прокси для сети включён (HTTPS_PROXY), maven.google.com / mavenCentral доступны.

## Что уже реализовано (модуль :engine), всё компилируется, парсер протестирован

Пакет `com.atlant.space.engine`:

- `util/Files.kt` — атомарная запись, рекурсивное копирование/удаление без follow
  симлинков, sha256, защита от path traversal (`resolveInside`).
- `util/Reflect.kt` — кэширующий reflection-хелпер (обход иерархии классов) для hook-слоя.
- `log/EventLog.kt` — персистентный журнал событий (JSON-строки, ротация), слушатели.
- `device/VirtualDeviceEnvironment.kt` — виртуальные параметры пространства (Android ID
  16 hex, locale, timezone). Единый источник правды, чтобы компоненты не противоречили.
- `space/Space.kt` + `space/SpaceManager.kt` — модель пространства и менеджер: индекс
  `spaces.json`, создание/переименование/архив/клон/удаление, реконсиляция индекса с
  диском (adopt orphan dirs, восстановление), слушатели изменений. **ResourcePolicy**
  (лимит процессов, kill в фоне).
- `storage/VirtualStorageManager.kt` — раскладка пространства на диске:
  - private root (внутр. память): `filesDir/vspaces/Space_N/{apps,data}/<pkg>/...`
  - shared root (внешн. память, вирт. /sdcard): `.../Spaces/Space_N/system/{Android/data,
    Android/obb,Android/media,Download,Documents,Pictures,Movies,Music,DCIM}`
  - provision, deletePackageData, clearCache, usage, **verify()** (проверка целостности:
    восстановление каталогов, write-probe, sha256 кода), валидация имён пакетов.
- `pm/ManifestParser.kt` — **с нуля написанный парсер бинарного AndroidManifest.xml**
  (string pool UTF8/UTF16, resource map, intent-filters, split, components, process,
  exported, permissions, versionCode major/minor). Не зависит от Android. Устойчив к
  битому/враждебному вводу (кидает IOException, не крэшит). **Есть unit-тесты**
  (`engine/src/test/.../ManifestParserTest.kt`) — запускаются с
  `-Datlant.testApk=<путь к apk>`, прошли на MultiSpace APK.
- `pm/VPackage.kt` — запись об установленном пакете (файлы кода+sha, abi, подписи,
  permissions, AppSettings: keepAlive/wakeOnBroadcast/nativeRedirect).
- `pm/PackageInstaller.kt` — **полноценный установщик**: APK / split-APK набор /
  бандлы (.xapk/.apks/.apkm с OBB), staging + atomic swap (install→.installing→swap→
  .previous), recover() после краха, проверка подписи при обновлении, запрет downgrade,
  проверка ABI (гость должен совпадать с ABI хост-процесса), извлечение native .so,
  извлечение OBB, защита от zip-traversal и бомб (лимиты), читает label через
  PackageManager.getPackageArchiveInfo.
- `perm/VirtualPermissionManager.kt` — виртуальный слой runtime-разрешений, хранится
  per-space (`data/.perms/<pkg>.json`). Важно: грант гостю **ограничен** реальным
  грантом хоста (HOST_BACKED) — нельзя сказать «granted» тому, что физически не работает.

### Рантайм виртуализации (начат, НЕ закончен) — пакет `runtime`

Подход (как VirtualApp, version-stable для API 26-34): хост объявляет пул stub-компонентов;
hook-слой подменяет stub на реальный компонент гостя внутри процесса.

- `runtime/StubConfig.kt` — имена/число stub-процессов (`:vprocN`, PROCESS_SLOTS=6),
  stub-активностей, провайдеров; authority для proxy/settings.
- `runtime/VirtualRuntime.kt` — per-process состояние гостевого процесса (spaceId, slot,
  processName, env, загруженные пакеты LoadedGuest). В main-процессе не инициализируется.
- `runtime/GuestLoadedApk.kt` — построение `ApplicationInfo` гостя, `DexClassLoader`
  (parent = boot, чтобы гость не видел классы движка), вставка LoadedApk в
  `ActivityThread.mPackages` через `getPackageInfoNoCheck`.
- `runtime/env/RedirectContext.kt` — **ContextWrapper с перенаправлением ВСЕГО хранилища**
  гостя в каталог пространства (files/cache/db/prefs/getDir/external/obb). Самая важная
  рабочая часть изоляции данных.
- `runtime/env/RedirectPrefs.kt` — SharedPreferences поверх JSON-файла в пространстве
  (не зависит от приватного XML-формата платформы), атомарная запись, кэш инстансов,
  listeners, полноценный Editor (commit/apply/clear/remove/stringSet).
- `runtime/hook/HookInvocation.kt` + `ProxyInvocationHandler.kt` — generic dynamic-proxy
  обёртка над системными Binder-синглтонами, маршрутизация через MethodHook (before/after),
  неперехваченные методы проксируются как есть.
- `runtime/hook/SystemServiceHooks.kt` — установщики proxy на синглтоны:
  IActivityManager(Singleton) / IActivityTaskManager (API29+), IPackageManager
  (ActivityThread.sPackageManager + ApplicationPackageManager.mPM). (Settings-провайдер —
  TODO.)
- Есть пустые AIDL: `ipc/IAtlantEngine.aidl`, `ipc/IProcessObserver.aidl` (движок↔гость:
  регистрация процесса, проверка permission, androidId, события, virtual root).
- `engine/src/main/cpp/CMakeLists.txt` — пока пустой (native IO-redirector будет позже).

## Что делать дальше (приоритет сверху вниз)

1. **Дописать hook-слой рантайма** и реально запустить гостевую Activity на эмуляторе:
   - `InstrumentationHook` (подмена `ActivityThread.mInstrumentation`): newActivity через
     ClassLoader гостя, гарантировать создание гостевого Application до первой Activity,
     newApplication.
   - `HCallbackHook` (`ActivityThread.mH.mCallback`): перехват LAUNCH_ACTIVITY /
     EXECUTE_TRANSACTION (ClientTransaction, API28+) — восстановить реальный Intent/
     ActivityInfo из stub.
   - В AMS-hook: `startActivity` — сохранить реальный Intent, подставить stub-компонент в
     нужном slot; `getContentProvider` — для settings authority обернуть IContentProvider,
     отдавать per-space ANDROID_ID.
   - PM-hook: getPackageInfo/getApplicationInfo/queryIntentActivities/resolveService/
     resolveContentProvider — отвечать из VPackage/ParsedManifest гостя.
   - `VClient` (bootstrap гостевого процесса) + подмена гостевого Context на RedirectContext.
   - Использовать `org.lsposed.hiddenapibypass:hiddenapibypass` (уже в зависимостях) для
     доступа к скрытым API на Android 9+.
2. **Виртуальные менеджеры**: Activity/Process/Service/Broadcast Manager в main-процессе
   поверх AIDL IAtlantEngine (учёт процессов, применение ResourcePolicy, мониторинг).
3. **Native/JNI layer** (`cpp/`): лёгкий seccomp-free IO-redirector (hook open/stat через
   `__system_property`/`LD_PRELOAD`-подобный приём внутри процесса или через bionic linker
   namespace) для приложений с хардкод-путями `/data/data/<pkg>`. Держать .so маленькими
   (`-Os`, strip), только arm64-v8a + x86_64.
4. **Root Compatibility Engine**: `root/` — Virtual Root (per-app вирт. su: виртуальная
   ФС, журнал команд, вкл/выкл) и описать Guest Root архитектурно. НЕ выдавать за UID 0.
5. **GMS модуль**: `gms/` — совместимость с Google Play Services внутри пространства
   (опционально, обработка отсутствия).
6. **Backup/Diag**: `backup/` (экспорт/импорт/архив пространства — tar.gz в Archives/
   Backups), `diag/` (экран состояния виртуализации, самодиагностика).
7. **Модуль `:app` + UI** (Jetpack Compose, Material3, тема по палитре ТЗ):
   - Включить `:app` в settings.gradle.kts, объявить в манифесте пул stub-компонентов
     (activities `StubActivity$P{0..5}$A{0..7}`, services, providers, `:vprocN` процессы).
   - Экраны: главный (логотип, текущее пространство, список пространств, быстрый запуск
     приложений, состояние среды, RAM, хранилище), список/создание пространств, установка
     APK (прогресс/ошибки), менеджер приложений (запуск/стоп/клон/экспорт/кэш/данные/
     разрешения/инфо), файловый менеджер, монитор процессов/памяти, журнал, настройки
     пространства и приложения, менеджер разрешений, Root-менеджер, бэкапы, диагностика.
   - Оригинальный логотип ATLANT SPACE (векторный, лайм-акцент), иконка приложения.
   - Анимации переходов (ненавязчивые).
8. **R8/ProGuard, оптимизация размера**, собрать **release APK и проверить ≤100 МБ**.

## Палитра дизайна (из ТЗ)

Основной фон `#080A08`, вторичный `#0D100D`, поверхности `#151915`, приподнятые
`#1C211C`, акцент `#B7F34A`, вторичный акцент `#D0FF76`, текст `#FFFFFF`, вторичный
текст `#A1AAA0`, разделители `#293029`. Лайм — дозированно. Минимализм, контурные иконки.

## Как собрать/проверить

```bash
cd atlant-space
echo "sdk.dir=/opt/android-sdk" > local.properties   # если нет
./gradlew :engine:testDebugUnitTest \
  -Datlant.testApk=/путь/к/любому.apk        # тесты парсера манифеста
./gradlew :engine:assembleDebug               # сборка библиотеки движка
# после появления :app:  ./gradlew :app:assembleRelease  и du -h на APK
```

## Git

Ветка разработки: **`ccr-7aa21a7f-tffwj1`** (push только туда,
`git push -u origin ccr-7aa21a7f-tffwj1`). PR не создавать без явной просьбы.
