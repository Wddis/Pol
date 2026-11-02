# Matryoshka: состояние проекта и передача контекста

Документ для продолжения работы в новой сессии. Здесь собраны цель, решения владельца, архитектура,
текущее состояние, порядок сборки и проверки, найденные подводные камни и следующие шаги.
Подробное исследование и план: [RESEARCH.md](RESEARCH.md). Движок: [../nest/README.md](../nest/README.md).

Обновлено: 2026-11-02, версия приложения **0.4.0-nest** (versionCode 5), ветка `ccr-d540c321-ahwwm2`.

---

## 1. Цель и требования владельца

Android-приложение **Matryoshka** — полноценная изолированная VM с **Android 11** на телефоне
(«телефон в телефоне»):

- Magisk-root внутри VM (по умолчанию root нет, у приложений гостя свои UID);
- GMS / YouTube / Google Play (в рамках лицензий);
- импорт/экспорт APK, обмен файлами;
- независимые ФС и настройки, игры, сохранение состояния, современный UI;
- размер 0,9–1,5 ГБ, ОЗУ в простое 300–400 МБ (где недостижимо — честно объяснить);
- только FOSS с соблюдением лицензий, без проприетарного/пиратского кода и чужих товарных знаков.

**Устройство владельца (он же тестировщик):** Xiaomi Redmi Note 8 (ginkgo), Snapdragon 665,
4 ГБ ОЗУ, 64 ГБ, MIUI 12.5 / Android 11, ядро 4.14, **без root** на хосте.

**Утверждённые решения (пункты владельца):**
1. Писать с тестами с нуля, оптимизируя процесс.
2. Облегчать без урезания функциональности.
3. Быть умеренно оптимальнее конкурентов (VMOS, VPhoneGaGa), без урезаний.
4. Структура «как у настоящего устройства», чтобы не было придирок приложений.
5. Владелец — тестировщик; есть временная кнопка отправки лога (`TESTER_TOOLS`).
6. Оптимизация умеренная; фоновую работу владелец разрешит в настройках.
8. Безопасность умеренная, без урезаний.
9. Вопрос Google (GMS) решается позже.
11. Рабочая оптимизированная VM с рабочими сервисами Google; камеры — «декорации», но приложения должны их видеть.
13. Процессы в меру 32-битные (поддержка arm32-приложений нужна).
14. Резервное копирование не нужно; приоритет — оптимизация.
- **Root:** Magisk ставится в VM так же, как в VPhoneGaGa (установка + мягкий перезапуск) и
  после установки работает.
- **Виртуальные ресурсы:** VM показывает приложениям свой объём ОЗУ и памяти, а реально
  использует ресурсы телефона.

---

## 2. Архитектура: движок Nest (userspace, без root и KVM)

Гостевой Android работает как набор обычных процессов приложения на ядре телефона.
Приложение `targetSdk = 28`: разрешён запуск файлов из данных приложения (W^X начинается с 29).
Супервизор упакован как `lib/arm64-v8a/libnest_run.so` (`useLegacyPackaging`, `extractNativeLibs=true`).

Главные механизмы (код в `nest/src/`):

| Механизм | Где | Суть |
|---|---|---|
| Перехват | `tracer.cpp` | seccomp `RET_TRACE` + ptrace. Фильтр ветвится по `seccomp_data.arch` (native / 32-бит), переход на native-блок — 32-битный `BPF_JA`. Записи с фильтром по аргументу (`arg0Only` для prctl, `nonZeroArg` для prlimit64) |
| Быстрый путь | `preload.cpp`, `fastpath.h` | `libnest_preload.so` (LD_PRELOAD в каждый процесс) переводит пути сам и помечает вызовы `kFastPathTag` в 6-м регистре; фильтр их пропускает (кроме execve). 32-бит: только младшее слово метки. Для 32-битных процессов — `libnest_preload32.so` (armeabi-v7a, собирается задачей `buildNestPreload32` в `app/build.gradle.kts`) |
| ФС | `vfs.*`, `vfs_share.*` | Таблица монтирования (длиннейший префикс), симлинки разрешаются внутри гостя, магические ссылки `/proc` не разыменовываются. Живая таблица в общем seqlock-файле для preload. Флаги монтирования: `sticky` (инфраструктура движка не скрывается), `cgroup` (файлы групп создаются при первой записи), `append` (`/dev/kmsg`) |
| exec | `tracer.cpp` (`rewriteExec`) | Запуск через **гостевой** загрузчик (`linker64 prog args`), argv[0] сохраняется (toybox), shebang, ELF32 `PT_INTERP`; `/proc/self/exe` отдаёт гостевую программу; копия preload в каждый каталог, разрешённый linker-namespace (`STATE/preload/…`) |
| Личность | `tracer.cpp` (`handleIds`, `handleCaps`) | Виртуальные uid/gid/groups и **capabilities** (eff/prm/inh/bnd/amb, securebits, keepcaps) с правилами ядра для setuid и exec. Привилегии проверяются по CAP_SETUID/SETGID/CHOWN |
| PID 1 | `handlePid` | Гостевой init видит себя PID 1 (`--pid1` или профиль android): getpid/gettid/getppid, kill/tkill/tgkill, `/proc/1`; `kill(-1)` только по гостевым процессам |
| Привилегированные вызовы | `emulate`, `doMount`, `handleSigsys` | mount (bind/move/tmpfs/proc/sysfs/devpts/cgroup/cpuset…; новое монтирование скрывает вложенные), umount2, pivot_root, chroot, unshare/setns (пока одно общее пространство), reboot (выключение убивает всё). Работает и при SIGSYS от seccomp-политики приложений Android |
| rlimit | `clampRlimit` | setrlimit/prlimit64 выше жёсткого лимита хоста для гостя с CAP_SYS_RESOURCE обрезается до лимита хоста и проходит (иначе init абортит службы с `rlimit`) |
| Сокеты | `rewriteSockaddr`, `fixSockName` | Пути AF_UNIX туда и обратно (`getsockname`/`getpeername`/`accept`). `connectRedirect`: connect к logdw идёт в «тройник» логов |
| binder | `vbinder*.{h,cpp}` | Свой драйвер binder в userspace: брокер — поток в nest_run (`-B сокет`), клиент — в preload. Три контекста, узлы/handles, вложенные вызовы, oneway, refcount, смерть, FD/FDA/PTR (SG), BR_SPAWN_LOOPER, **poll/epoll** (wake-байт на сокете процесса + флаг `kRespDrainWake`). Отправителю передаётся виртуальный euid |
| eBPF | `bpf_emu.*` + `handleBpf` | Карты (hash/array/per-CPU/LRU, флаги, итерация) и программы в userspace. fd объекта = fd файла `STATE/bpf/obj-N` (вызов подменяется на `openat`, после SIGSYS — перезапуск с откатом PC, `restartAs`). pin/get в `/sys/fs/bpf`, info, attach/query. Резервный канал `mount(NULL,"/","nest-bpf",cmd\|size<<32,attr)` для сред без bpf() (qemu) |
| Профиль Android | `android_profile.cpp` | Свой `/dev` (null/zero/random/urandom/tty/ptmx/ashmem/tun + узлы binder для access()), `/dev/kmsg` → `STATE/kmsg`, сгенерированные `/proc/version`, `/proc/filesystems` (без selinuxfs → SELinux у гостя выключен), `/proc/cmdline`, `/proc/*/attr/*` → `STATE/attr`, APEX в `/apex/<name>` |
| Логи | `guest_log.cpp` | Тройник logdw: копия всего logcat гостя в `STATE/logcat.txt` + пересылка в гостевой logd |
| Метки указателей | `arch.h` `untagAddr` | Android 11 на arm64 метит указатели кучи в старшем байте; для `process_vm_*` метка снимается (трассировщик и брокер) |

**Запуск гостя (как делает приложение, `core/nest/NestGuest.kt`):**
```
libnest_run.so -b /=<rootfs> -b /data=<data> --android <state> -P libnest_preload.so \
   -P32 libnest_preload32.so -B <state>/b.sock -v -s -- /system/bin/init second_stage
env NEST_STATE_DIR=<state>   [NEST_BINDER_LOG=<file> — трасса binder]
```
Пути короткие (`<dataDir>/n/{r,d,s}`), потому что имена unix-сокетов ограничены 108 байтами.

**Гостевой образ:** Waydroid LineageOS 18.1 VANILLA arm64 (system) + HALIUM_11 vendor,
собирается `guest/tools/make-nest-rootfs.sh` (debugfs, без root, обрезка лишнего) workflow
`.github/workflows/nest-guest-image.yml` в релиз **`nest-guest-a11`**: `manifest.json` +
`nest-a11-lineage18.1.tar.gz` (692 МБ, распаковано ≈1,56 ГБ, sha256 в manifest). Репозиторий
`Wddis/Pol` публичный. `/vendor` внутри rootfs.

---

## 3. Текущее состояние

### Проверено на телефоне владельца (логи тестировщика 0.2.0 → 0.3.0)
- seccomp, ptrace, exec из данных приложения, memfd/передача fd — работают;
- быстрый путь быстрее медленного в 5,4–6,8 раза (медленный ≈264 мкс на остановку);
- vbinder работает (сценарий из трёх процессов).

### Локальный стенд (x86_64 + qemu-user вместо arm64-процессора)
Настоящий init Android 11 проходит early-init → init → late-init → fs → post-fs-data →
**zygote-start → boot**. Работают logd, lmkd, servicemanager, hwservicemanager, vndservicemanager,
vold (регистрирует службу), apexd, bpfloader (через эмуляцию BPF), statsd, zygote и
zygote_secondary, surfaceflinger, audioserver, HIDL HAL. Перезагрузок нет.

Остановки на стенде (**артефакты qemu, на телефоне их быть не должно**):
- netd: qemu-user не поддерживает netlink `NETLINK_SOCK_DIAG` (EPROTONOSUPPORT), после чего netd падает с null deref в `TrafficController::start`. netd рестартует → вместе с ним рестартуют zygote и другие;
- `prctl(PR_SET_SECCOMP)` гостя (minijail в media*, configstore) → EINVAL в qemu → SIGABRT;
- qemu не реализует `bpf()` (ENOSYS) — обходится резервным mount-каналом.

Ожидаемые, ещё не сделанные части: `vendor.hwcomposer` (Waydroid ищет Wayland), камера,
графика вообще (см. план).

### Ждём от владельца
Тест **0.4.0-nest** на телефоне: «Система» → «Проверка движка Nest» → «Пробная загрузка
Android 11»: скачать образ → «Загрузить Android 11» → 3–5 минут → «Отправить лог».
В лог попадают вывод движка, kmsg гостя, logcat гостя и (по галочке) трасса binder.
**Следующий шаг — разобрать этот лог** и чинить то, что всплывёт на настоящем ядре 4.14
и под seccomp-политикой приложений Android.

---

## 4. Карта репозитория (`matryoshka/`)

- `nest/` — движок (C++17, CMake). `src/` — модули из таблицы выше, `tests/` — 56 тестов
  (`test_vfs.cpp`, `test_tracer.cpp`, `test_vbinder.cpp`; вспомогательные `idprog.cpp` — набор
  команд для проверки поведения, `binderprog.cpp` — сырой протокол binder: manager/poll-manager/server/client).
  `tools/gen_compat_syscalls.py` генерирует `src/syscalls_compat.h` (номера 32-битных вызовов из
  UAPI NDK и хоста; список имён — в скрипте, при добавлении вызова перегенерировать).
  `tools/bench/` — скрипты локального стенда.
- `app/` — приложение (Kotlin, Compose). Важное: `core/nest/NestProbe.kt` (проверка телефона),
  `core/nest/NestGuest.kt` (скачивание/распаковка/запуск гостя), `core/debug/TesterLog.kt`
  (кнопка лога), `ui/system/NestCheckScreen.kt`. Старые движки (`core/engine/Engines.kt`:
  HYPERVISOR/crosvm, CONTAINER — требуют root, EMULATOR — QEMU-пакет) остаются; NEST пока
  не стал типом VM в `EngineType` — это отдельная задача после успешной загрузки на телефоне.
- `guest/` — сборка гостевых образов. `docs/` — RESEARCH.md (исследование, план, сценарий Magisk),
  ARCHITECTURE.md, LIMITATIONS.md, этот файл.
- `.github/workflows/matryoshka.yml` — CI: shellcheck, тесты Nest (с `g++-multilib`), probe,
  юнит-тесты/lint/сборка APK. `nest-guest-image.yml` — образ гостя в релиз.

---

## 5. Сборка и проверка

```bash
cd matryoshka
# движок (хост x86_64) + все тесты
cmake -S nest -B /tmp/bn -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/bn -j8 && /tmp/bn/nest_tests
# приложение (как в CI)
./gradlew testDebugUnitTest lintDebug assembleRelease -Probolectric.repo=https://maven-central.storage-download.googleapis.com/maven2
```
- NDK **27.2.12479018**, CMake из SDK 3.22.1. APK ≈2 МБ, в нём `libnest_run.so`,
  `libnest_preload.so`, `libnest_preload32.so` (ELF32 ARM), `libnest_bindertest.so`.
- В песочнице Maven Central отвечает 429: локально помогал `~/.gradle/init.d/mirror.gradle`,
  перенаправляющий на `https://maven-central.storage-download.googleapis.com/maven2/`
  (в репозиторий не входит; в новой сессии создать заново при необходимости).
- **Подпись:** release подписывается debug-ключом (`signingConfigs.debug`). Отпечаток SHA-256
  сертификата всех присланных APK (0.2.0–0.4.0): `fc9081168b873e5679fb5f551a9d8d9040faad4107b40050e6889fe047d723c9`.
  Ключ лежит только в контейнере сессии (`~/.android/debug.keystore`). Новый контейнер создаст
  другой ключ — тогда новая сборка **не встанет поверх**, придётся удалить приложение (и
  скачанный образ гостя). Решение о сохранении ключа — за владельцем.

### Стенд (без телефона)
Нужны qemu-user-static с binfmt для aarch64 и arm. Подготовка: распаковать релизный rootfs в
`$BENCH/rootfs`, собрать хостовый `nest_run` в `$BENCH/nestbuild`, preload под Android —
`nest/tools/bench/build-preloads.sh`. Запуск: `BENCH=… T=300 nest/tools/bench/boot-guest.sh`.
Результат: `/tmp/nst/kmsg`, `/tmp/nst/logcat.txt`, `/tmp/nst-out.txt`.
Полезно: `V_FLAGS="-v -v"` (все переписанные пути по pid), `NEST_BINDER_LOG=/tmp/b.txt`
(каждая транзакция: отправитель, контекст, handle, код, интерфейс; ответы, ошибки
буфера, изменения refcount узлов).

Особенности qemu-стенда: нужны identity-привязки (`-b X=X`) для путей, которые qemu открывает
сам; метка быстрого пути через qemu не проходит (всё идёт медленным путём); seccomp гостя,
bpf, часть netlink недоступны.

---

## 6. Уроки и подводные камни (не наступать снова)

- **Порядок вычисления аргументов** C++ не определён: в тестовых программах не вызывать
  несколько операций внутри одного `printf`.
- **binder refcount:** BR_INCREFS/BR_ACQUIRE на объекты отправителя — в очередь **потока-отправителя**
  до BR_TRANSACTION_COMPLETE/BR_REPLY (как в ядре), иначе HAL падают в `RefBase::incStrong`.
  При повторном использовании адреса объекта (другой cookie, узел без ссылок) — новый узел.
- **servicemanager A11** ждёт работу через epoll на fd binder → fd должен становиться читаемым (сделано).
- **Метки указателей arm64** ломают `process_vm_readv` (EFAULT) → `untagAddr`.
- `android_get_control_socket()` проверяет имя через `getsockname` → нужна обратная трансляция путей.
- libprocessgroup требует `getpid()==1` для SetupCgroups → виртуальный PID 1.
- Монтирование поверх каталога должно скрывать вложенные привязки (иначе `/apex` пуст для linkerconfig).
- `/dev/kmsg` — файл: без O_APPEND процессы затирали строки друг друга.
- init с `rlimit`/`capabilities` у службы абортит дочерний процесс, если setrlimit/capset не прошли.
- Для flattened APEX `update_linker_config` — no-op, первый linkerconfig должен видеть все APEX.
- ProcessState проверяет `access(/dev/vndbinder)`; без файла падает обратно на /dev/binder.
- Robolectric: `Os.symlink/chmod` — заглушки; в коде использовать `java.nio.file.Files` для ссылок.

---

## 7. План (по порядку)

1. **Разбор лога 0.4.0 с телефона**, исправления до стабильного `boot` и запуска `system_server`.
   Ожидаемые темы: seccomp-политика приложений (SIGSYS) для новых вызовов, minijail-фильтры
   гостя поверх нашего, netd/сеть без iptables (виртуальная сеть), производительность на SD665.
2. Виртуальные `/proc/mounts` и `/proc/self/mountinfo` из таблицы монтирования (сейчас гость
   видит монтирования хоста: vold пытался размонтировать `/mnt/...` хоста).
3. Корректные cgroup.procs (членство процессов, наследование при fork) — сейчас «1 processes
   remain» и задержки ~250 мс при остановке служб.
4. Графика: свой HWC/gralloc поверх GPU хоста (gfxstream/ANGLE-подобный путь) и вывод в
   Surface приложения; затем ввод, звук, сеть, хранилище, обмен файлами.
5. NEST как полноценный тип VM в приложении (`EngineType.NEST`, VmManager, экран устройства).
6. Виртуальные ОЗУ/память для приложений гостя (`/proc/meminfo`, statfs), камера-HAL
   («декорация»), microG/GApps.
7. Magisk: установка как у VPhoneGaGa (init-hook + мягкий перезапуск), SO_PEERCRED-виртуализация,
   виртуальные владельцы файлов, отдельные mount namespaces, Zygisk.
8. Оптимизация (кэш путей, больше вызовов в быстрый путь, память), UI.

---

## 8. Правила работы

- Общение с владельцем — **на русском**, коротко и понятно; он тестирует на телефоне и
  присылает файлы логов `matryoshka-log-*.txt`.
- Разработка в назначенной ветке (сейчас `ccr-d540c321-ahwwm2`), PR — только по просьбе.
- Каждое изменение движка — с тестом; перед отправкой APK: тесты Nest, `testDebugUnitTest`,
  `lintDebug`, `assembleRelease`; проверить подпись (`apksigner verify --print-certs`).
- **Безопасность:** в `bot.py` (корень репозитория) захардкожены API-ключ и токен Telegram.
  Их нельзя выводить или копировать; владельцу рекомендовано их отозвать. `bot.py` не изменять.
- Лицензии: только FOSS; уведомления — `THIRD_PARTY_NOTICES.md`; без товарных знаков.
