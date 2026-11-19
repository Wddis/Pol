# FarmerShoot v8 — модуль для **LSPatch** (rootless Xposed)

Готовый Xposed-модуль, который LSPatch встраивает в ваш APK игры **без root**.
Внутри процесса игры он: показывает **тост «v8 загружен»** и **панель-окно**
(перетаскиваемую) прямо в игре — это доказательство инъекции, — и, если вшит
нативный движок, даёт живые патроны и патч прыжка.

## Как распознаётся LSPatch
По `meta-data android:name="xposedminversion"` в манифесте + точка входа в
`assets/xposed_init` → `com.recovery.farmerv8.FarmerV8Module`
(`IXposedHookLoadPackage`). Формат проверен по исходникам LSPatch
(`share/android/.../ModuleLoader.java`, `patch-loader/.../LSPLoader.java`).

## Применение (на телефоне)
1. Откройте **LSPatch** → **New Patch** (Rootless / Local).
2. Выберите **вашу игру** (целевой APK).
3. **Add module** → выберите `FarmerShoot-v8-LSPatch-module.apk` (этот файл).
4. **Start patch** → установите пропатченную игру (старую удалите, подпись иная).
5. Запустите игру — сверху появится **тост** и **панель v8**. Инъекция прошла.

> Scope (область) LSPatch задаёт сам при встраивании — выбранная игра.

## Честная граница (важно)
Xposed/LSPosed хукает **Java/ART**, а логика Unity — **нативный IL2CPP**
(`libil2cpp.so`). Поэтому:
- **Панель и тост** (Java) появятся всегда — это доказательство, что код
  исполняется внутри процесса игры.
- **Патроны и «прыжок ×3»** читает/пишет **нативная часть** `libfarmerv8.so`
  (из `../native/`). Без неё кнопки честно скажут «нужен нативный движок».

## Добавить нативный движок (ammo/jump)
1. Соберите `../native/` → `libfarmerv8.so` (arm64-v8a) — см. `../native/overlay.md`.
2. Положите его в `libs/arm64-v8a/libfarmerv8.so` и пересоберите модуль
   (`./build.sh`) — скрипт вшьёт `.so` в apk под `lib/arm64-v8a/` и `assets/`.
3. Модуль на старте извлечёт и `System.load()` его; заработают CHECK / Прыжок ×3.
   (Экспортируйте из `.so` JNI-функции `nativeCheck`/`nativePatchJump`/`nativeRestore`
   — сигнатуры см. в `FarmerV8Module.java`. Либо движок рисует свой ImGui-оверлей
   через eglSwapBuffers независимо от Java-панели.)

## Сборка модуля из исходника
Нужен Android SDK (build-tools + platform-34). Запуск: `./build.sh`
Пайплайн: `javac` (стабы Xposed + модуль) → `d8` → `aapt2 link` → `zipalign`
→ `apksigner` (debug-ключ). Стабы `src/de/robv/...` только для компиляции — в dex
не попадают (в рантайме их даёт LSPatch).

Готовый apk: `build/FarmerShoot-v8-LSPatch-module.apk`.

## Примечание про онлайн/анти-чит
Патч LSPatch меняет подпись игры → Play Integrity/серверный анти-чит могут не
пустить в онлайн. Это ваш проект — тестируйте офлайн/dev.
