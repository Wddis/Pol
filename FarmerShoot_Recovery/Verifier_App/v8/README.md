# FarmerShoot Verifier **v8** — LIVE Field Inspector (оверлей поверх игры)

v1–v7 читали **файлы** установленной игры (статика). **v8 — другое:** он читает
**живой процесс** запущенной игры и показывает **реальные текущие значения**
(патроны, HP, координаты…), подсвечивая **что изменилось** (`old → new`, Δ).

Это закрывает прежний блокер: `global-metadata.dat` **не было** в `test.so`, но у
**запущенной** игры она уже в памяти — v8 берёт классы/поля **по имени прямо из
живого процесса**. Оффсеты не хардкодятся → работает после обновлений игры.

---

## ⚠️ Честная техническая граница (прочитайте первым)
Прочитать значения чужой игры можно **только изнутри её процесса**. Отдельное
приложение-оверлей из соседней песочницы память игры **не видит без root**.
Поэтому у v8 два пути доставки кода внутрь процесса:

| Путь | root? | Что делаете | Онлайн/анти-чит |
|---|---|---|---|
| **Frida-gadget в своём APK** (`objection patchapk`) | нет | репак+переподпись своего apk | подпись меняется → Play Integrity/анти-чит могут не пустить; тестируйте **офлайн/dev** |
| **frida-server / инжектор** | да | ничего не репачите | обходится модулями, но нужен root |

Это **ваш** проект — любой путь легален. Для «просто посмотреть патроны вживую»
проще всего **Frida** (ничего компилировать не надо).

---

## Вариант A — Frida (рекомендуется, запускается сегодня) · `frida/farmer_v8.js`
Делает: **DUMP** (свежее дерево классов/полей в файл), **autoFind** (где лежат
`ammo/jump/health/…`), **AUTO-WATCH** (сам находит меняющиеся числа и печатает
только изменения), **WATCH** (ручной список), **PATCH** (запись значений:
абсолют или множитель ×3), **ENFORCE/RESTORE**.

```bash
npm i frida-il2cpp-bridge frida-compile
npx frida-compile frida/farmer_v8.js -o farmer_v8.compiled.js

# ROOT:
frida -U -f <package.name> -l farmer_v8.compiled.js
# NO-ROOT (gadget в своём apk):
objection patchapk -s your_game.apk        # вшить gadget
#   установить пропатченный apk, запустить игру, затем:
frida -U Gadget -l farmer_v8.compiled.js
```

### Увидеть патроны БЕЗ знания имён полей (turn-key)
`CONFIG.autoWatch` уже включён. Скрипт сам берёт классы оружия/движения/жизни
(`Weaponry/Weapon/Ammo/Health/Movement/Jump…`) и следит за их числовыми полями.
Зайдите в матч, **стреляйте** — в консоли всплывёт строка вида
`~ <...Magazine>.ammo  30 -> 29  (Δ -1)`. Это и есть ваш счётчик патронов —
теперь вы знаете его точное имя класса/поля.

### Поменять значение и увидеть глазами (прыжок ×3)
1. Из строк autoFind/auto-watch возьмите реальные `cls`+`field` для прыжка
   (кандидаты по `jump/force/gravity/height`).
2. Впишите в `CONFIG.patch`, напр.:
   ```js
   { cls: "<...PlayerMovement>", field: "jumpForce", kind: "instance", mul: 3, enforce: true, label: "Прыжок ×3" }
   ```
   `mul:3` — втрое выше; `mul:0.33` — втрое ниже; `value:999` — абсолют (ammo).
3. В консоли Frida нажмите «кнопку»: `rpc.exports.patch()` → прыгните в игре,
   увидите, что прыжок втрое выше. `rpc.exports.restore()` — вернуть как было.
   `enforce:true` держит значение каждый кадр (если игра его пересчитывает).

RPC-«кнопки»: `rpc.exports.dump() / .autofind() / .check() / .patch() / .restore() / .enforce(1|0)`.

## Вариант B — нативный ImGui-оверлей (настоящее «окно поверх») · `native/`
`libfarmerv8.so` хукает `eglSwapBuffers` и рисует поверх игры окно с таблицей
watch-полей, кнопкой **«ПРОВЕРИТЬ»**, авто-обновлением (меняющиеся значения —
оранжевым, Δ — красным) и **кнопками-патчами** («Прыжок ×3», «Патроны=999», «ОТКАТ»).
Наш код: `main.cpp`, `main.h`, `WatchConfig.hpp` (списки `kWatch` и `kPatch`).

```bash
cd native && NDK=/путь/к/android-ndk ./build.sh    # тянет ImGui/Resolver, ждёт libdobby.a
# → build/libfarmerv8.so ; вшить в свой apk (gadget) или грузить инжектором (root)
```
Нюансы (хелпер `GetFirstInstance`, хук тач-ввода под кнопку) — в `native/overlay.md`.

---

## Какие имена подставлять
Из живого **DUMP** (точно) или из уже восстановленного манифеста:
`../../Extracted/balders_identifiers.txt`, `../../Extracted/gameplay_vocab.txt`
(`WeaponShop`, `AimDispersionCurve`, `HitParameters`, `ShooterId`, `Ballistics`…).
DUMP на живой игре надёжнее — покажет реальные namespace и имена полей.

## Референсы (свежие, GitHub, 2025–2026)
- `Sneaky51/Android-Il2Cpp-Resolver-Template` — resolve-by-name + render-хук.
- `DeFexNN/NightcoreLiosImgui` — ImGui-оверлей Unity, хуки тач-ввода, 3 варианта инъекции.
- `vfsfitvnm/frida-il2cpp-bridge` — живой доступ к il2cpp из Frida (основа Варианта A).
- `muhammadrizwan87/il2cppdumper`, `CameroonD/Il2CppMetadataExtractor` — дамп метадаты из памяти.

## Граница честности
v8 показывает **подлинные live-значения** вашего процесса и их изменения. Это
инструмент **инспекции/отладки своей игры**, а не «восстановление проекта».
Исходный C# по-прежнему даёт только папка Unity-проекта или дамп `libil2cpp.so`
+ `global-metadata.dat` (Il2CppDumper/Cpp2IL) — см. `../../Reports/01_NEXT_STEPS.md`.
