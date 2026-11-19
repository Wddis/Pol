# v8 — нативный оверлей: заметки по сборке и инъекции

## Что это
Нативная библиотека `libfarmerv8.so`, которая, попав **внутрь процесса игры**,
хукает `eglSwapBuffers` и рисует ImGui-окно поверх кадра: таблица watch-полей,
кнопка **«ПРОВЕРИТЬ»** и авто-обновление. Значения читаются из живого процесса
IL2CPP-резолвером **по имени** класса/поля (оффсеты не хардкодятся).

## Недостающий хелпер `GetFirstInstance`
Базовый резолвер умеет находить класс и оффсет поля, но не перечисляет живые
объекты. Добавьте в `Resolver/API/Class.hpp` в `namespace Utils` функцию,
которая берёт первый живой объект класса из кучи. Минимальный вариант через
`il2cpp_gc_handle`/`gc world` недоступен в template напрямую — проще взять его
из `frida-il2cpp-bridge` логики `Il2Cpp.gc.choose` ИЛИ хукнуть конструктор
нужного класса и запомнить `this`:

```cpp
// в Utils (упрощённо): вернуть ранее пойманный instance (ловим в хуке .ctor)
static void* s_lastInstance[256]; // по hash(cls)
void* GetFirstInstance(Unity::il2cppClass* k) { return s_lastInstance[hash(k) % 256]; }
```

Практичнее: для instance-полей хукнуть метод игры, через который объект точно
«живой» (напр. `Weapon.Update`), и в хуке читать `this + offset`. Это надёжнее
слепого перебора кучи. Для статических полей (`WeaponShop.AutoBuy` и т.п.)
хелпер не нужен — работает `GetStaticField` из коробки.

> Если это тормозит — используйте **Frida-путь** (`../frida/farmer_v8.js`):
> там `Il2Cpp.gc.choose(class)` уже реализован и находит живые объекты сам.

## Touch input (чтобы кнопка «Проверить» нажималась)
`imgui_impl_android` сам ввод не получает — его надо прокинуть. Варианты цели хука:
- `ANativeActivity` → `onInputEvent` (игры на native activity);
- `InputConsumer::consume` / `initializeMotionEvent` (большинство Unity-сборок);
- `RegisterNatives` → `nativeInjectEvent` (Java-путь ввода).

В `main.cpp` готов обработчик `App::h_handleInput` — остаётся поставить на него
`DobbyHook` по адресу выбранной цели (адрес ищется по символу/сигнатуре в
`libunity.so`/`libinput`). См. референс:
`DeFexNN/NightcoreLiosImgui` (хуки `InputConsumer::initializeMotionEvent`,
`nativeInjectEvent`) и `Sneaky51/Android-Il2Cpp-Resolver-Template` (render-хук).

## Инъекция в процесс
1. **NO-ROOT (рекомендуется для своего проекта):** вшить `.so` в СВОЙ apk через
   `objection patchapk -s your_game.apk` (frida-gadget), либо положить `.so` в
   `lib/arm64-v8a/` и вызвать `System.loadLibrary` из gadget-скрипта. Репак ломает
   подпись → только офлайн/dev, онлайн анти-чит может не пустить.
2. **ROOT:** любой инжектор (Zygisk-модуль, `inject` по `/proc/<pid>/maps`,
   Riru). Тогда репак не нужен.

## Зависимости (в git не хранятся)
`build.sh` тянет: ocornut/imgui, Sneaky51 resolver, и ждёт собранную `libdobby.a`
(jmpews/Dobby). Все три — апстрим, пины версий ставьте сами под свою сборку.
