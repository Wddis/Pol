# v8 — закреплённый опыт (что реально сработало)

Журнал того, что подтвердилось на живом устройстве при патче игры через **LSPatch**
(rootless Xposed). Это рабочая база для следующих сессий.

## Итог: инъекция и окно РАБОТАЮТ (подтверждено скриншотом)
- LSPatch встроил модуль, `handleLoadPackage` исполнился, окно «FarmerShoot v8 —
  INJECTED ✓» появилось поверх игры. Игра — **OpenGL ES** (overlay через
  `eglSwapBuffers` показал `ON`).

## Что НЕ сработало и чем заменили (ключевые уроки)

### 1. Java-View через `addContentView` — НЕ видно
Unity рисует GL-surface поверх обычных View (часто `setZOrderOnTop`). Обычная
View под ним.
**Решение:** отдельное окно через **`WindowManager`** (тип `TYPE_APPLICATION`
по токену активити, либо `TYPE_APPLICATION_OVERLAY` при разрешении). Видно и на
Vulkan, и на GL. Нативный ImGui-оверлей (`eglSwapBuffers`) — только для GL.

### 2. Хук `Activity.onResume` под LSPatch НЕ срабатывал
**Решение:** `Application.registerActivityLifecycleCallbacks` (официально,
надёжно) + запасная рефлексия `ActivityThread.mActivities`. Никаких хуков
фреймворка для получения активити.

### 3. `dlopen("libil2cpp.so", RTLD_NOLOAD)` возвращает null → il2cpp не цепляется
Самое важное. Наша `.so`, загруженная `System.load` из кэша, из-за **изоляции
линкер-неймспейсов Android** не видит `libil2cpp.so` через `dlopen`. Резолвер
(Sneaky51) на этом висел → «il2cpp: waiting…», классов 0, DUMP «не готов».
**Решение:** резолвить символы `libil2cpp.so` напрямую из памяти через
**xDL** (`xdl_open`/`xdl_sym`, github.com/hexhacking/xDL) и вызывать il2cpp API
своими биндингами. Неймспейсы больше не мешают.

### 4. Нет логов LSPatch → нужна самодиагностика
**Решение:** heartbeat-**тост** каждые 2.5с со стадией + маркер-файл
`files/v8_alive.txt`. Если тостов нет вообще — модуль не исполняется (проблема
встраивания), а не код.

### 5. UX: «спрятать» закрывало окно насовсем
**Решение:** тап по шапке **сворачивает/разворачивает** тело; шапка остаётся,
окно не исчезает.

## Рабочая архитектура (итоговая)
```
LSPatch встраивает модуль-apk в игру
  └─ FarmerV8Module (Java, assets/xposed_init)
       ├─ Bootstrap-поток: ждёт Application-контекст
       ├─ грузит libfarmerv8.so (System.load из кэша игры)
       ├─ ActivityLifecycleCallbacks → окно WindowManager (видно всегда)
       └─ heartbeat-тосты + v8_alive.txt (диагностика)
  libfarmerv8.so (native)
       ├─ xDL: резолв il2cpp из libil2cpp.so (обход неймспейсов)
       ├─ il2cpp API: dump/поиск классов/полей, чтение/запись по оффсету
       ├─ ImGui-оверлей на eglSwapBuffers (для GL)
       ├─ захват instance через хук .ctor (And64InlineHook) → патч jump/ammo
       └─ JNI: nativeInfo / nativeDumpTo / nativePatchJump / nativeRestore
```

## Сборка (воспроведение в контейнере)
- SDK: cmdline-tools + `platforms;android-34` + `build-tools;34.0.0`.
- NDK: `ndk;26.3.11579264` (clang 17, arm64).
- Нативные зависимости (тянутся, в git не хранятся):
  ocornut/imgui (берём папку `App/ImGUI` из Sneaky51-резолвера — совместимый
  бэкенд), `Rprop/And64InlineHook` (inline-хук, без Dobby), `hexhacking/xDL`.
- `.so`: `aarch64-linux-android24-clang++ -std=c++17 -fPIC -O2
  -DIMGUI_IMPL_OPENGL_ES3 -Iresolver/App -Iresolver/App/ImGUI -Ia64 -Ixdl/include`
  + xDL (.c, clang -std=c11), линк `-lEGL -lGLESv3 -landroid -llog -ldl
  -static-libstdc++`, затем `llvm-strip`.
- Модуль-apk: `javac` (стабы Xposed + модуль, без lambda/anon — d8 8.x NPE) →
  `d8` → `aapt2 link` (+assets, +lib/arm64-v8a) → `zipalign` → `apksigner`.

## Дальше (чтобы ammo/jump работали «по-настоящему»)
1. На устройстве дождаться `il2cpp: READY`, нажать **DUMP имена** →
   `files/v8_dump.txt` с реальными именами классов/полей.
2. Вписать их в `native/final/farmer_native.cpp`: `kWatchClass/kWatchField`
   (ammo) и `kPatchClass/kPatchField` (+`kPatchMul`=3 для прыжка).
3. Пересобрать `.so` и модуль → патрон/прыжок работают на живой игре.

## Находка на реальной игре (com.farmar.shoot2)
Список загруженных `.so` НЕ содержит `libil2cpp.so`. Есть `libunity.so` (тот самый
208 МБ из recovery), `libmain.so`, + плагины (fmod, phonon, vivox, sodium, enet,
crashlytics). Вывод: **il2cpp статически вшит в `libunity.so`**, а экспорты
`il2cpp_*` из `.dynsym` вырезаны (recovery это подтверждал).
- Фикс FINAL6: пробуем резолв не только по `.dynsym` (`xdl_sym`), но и по
  `.symtab`/`.gnu_debugdata` (`xdl_dsym`) по `libunity.so`/`libmain.so`.
- Если и `.symtab` пуст → символов нет вовсе. Тогда путь один:
  **оффлайн Il2CppDumper** по `libunity.so` + `global-metadata.dat` → получить
  оффсеты классов/полей → читать/писать по `base(libunity.so)+offset` в рантайме
  (экспорты для этого не нужны). Блокер: `global-metadata.dat` у этой игры в
  читаемом виде не найден (вероятно шифрован/подгружается в память) — её надо
  снять дампом памяти, тогда Il2CppDumper даст полную карту.

## Честная граница
Это инструмент для СВОЕЙ игры офлайн/dev. Репак LSPatch меняет подпись →
онлайн-анти-чит/Play Integrity могут не пустить.
