# Matryoshka: состояние проекта и передача контекста

Документ для продолжения работы в новой сессии. Здесь собраны цель, решения владельца, архитектура,
текущее состояние, порядок сборки и проверки, найденные подводные камни и следующие шаги.
Подробное исследование и план: [RESEARCH.md](RESEARCH.md). Движок: [../nest/README.md](../nest/README.md).

Обновлено: 2026-11-03, версия приложения **0.5.1-nest** (versionCode 16), ветка `ccr-d540c321-ahwwm2`.

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

### Первый запуск на телефоне (лог 0.4.0, 2026-11-03)
Проверка движка — всё зелёное (быстрый путь в 3,8 раза быстрее, vbinder работает). Образ
скачался и распаковался. init второй стадии стартовал и через 84 мс упал на «Failed to
initialize property area» (`fchown failed: EPERM`). Причина — **утечка метки быстрого пути**:
регистр x5 с меткой сохранялся после вызова, и следующий «сырой» вызов libc (`fchown` после
`open`) проходил мимо трассировщика к настоящему ядру. На qemu метка не доходит до хоста,
поэтому стенд этого не видел. Исправлено в 0.4.1: метка привязана к номеру вызова
(`fastPathTag(nr)`, фильтр сравнивает `args[5] ^ nr`), регистр обнуляется сразу после `svc`.
Там же: set*id/setgroups/capset/fchown, отклонённые seccomp-политикой приложений (SIGSYS),
теперь отвечаются из виртуальных учётных данных; `/proc/<pid>/oom_score_adj` виртуальный.
`keyctl` (219) пока отвечает EPERM — init только пишет ошибку.

### Лог 0.4.1 (2026-11-03)
`fchown` больше не падает; init остановился на «Failed to initialize property area»: bionic
ставит SELinux-метку (`fsetxattr security.selinux`) на каждый файл свойств, приложению это
запрещено, init абортит. Плюс ошибка 0.4.1: виртуальный `oom_score_adj` лежал в каталоге
состояния, невидимом из гостя (ENOENT). Исправлено в 0.4.2: `security.*` xattr для гостя с
CAP_SYS_ADMIN виртуальны (хост не трогается); каталог состояния привязан в vfs сам на себя
(sticky); отказы хоста в `setpriority`/`ioprio_set` (и RT-планировании при CAP_SYS_NICE)
скрываются от гостя (zygote `priority -20`). Тест `android_profile_on_separate_root_like_the_phone`
повторяет раскладку телефона (отдельный корень гостя) — стенд с `-b /=/` такие ошибки не видит.

### Лог 0.4.2 (2026-11-03)
oom и метки прошли, но init снова остановился на «Failed to initialize property area»: bionic
проверяет, что `/dev/__properties__/property_info` принадлежит root (`st_uid == 0`), а на
телефоне все файлы гостя принадлежат uid приложения. Исправлено в 0.4.3 — **виртуальные
владельцы файлов**: `chown`/`fchown`/`fchownat` гостя записывают владельца в xattr
`user.nest.owner` ("uid:gid") на файле хоста (`Tracer::recordOwner`); обёртки `stat`/`fstat`/
`fstatat` в preload показывают записанного владельца, а файлы приложения без записи — как root
(uid/gid хоста передаются в `NEST_HOST_UID`/`NEST_HOST_GID`). Внутренние вызовы libc идут через
PLT (`fstat@@LIBC` JUMP_SLOT), поэтому перехватываются. Не покрыто: «сырые» syscalls stat мимо
libc и statx.

### Лог 0.4.3 (2026-11-03) — большой скачок
Загрузка дошла до 22 с: активированы все APEX, init разобрал все .rc, SetupCgroups успешно,
boringssl-тесты прошли. Две новые проблемы:
1. **ueventd** не может открыть netlink-сокет событий ядра (`NETLINK_KOBJECT_UEVENT`): приложению
   SELinux это запрещает. ueventd — critical, 4 аварии → перезагрузка. **Это и был блокер.**
   Исправлено в 0.4.4: движок перехватывает `socket(AF_NETLINK, …, NETLINK_KOBJECT_UEVENT)`,
   подменяет на безвредный AF_UNIX-датаграм (чёрная дыра: bind успешен, событий нет — в VM их и
   нет); `bind`/`connect` с netlink-адресом на таком fd тоже эмулируются (`isFakeNetlinkBind`,
   множество fd на tgid, чистится при exec/выходе). Это был запланированный пункт «сеть».
2. **linkerconfig** падает на `VENDOR_VNDK_VERSION is not defined` (`ro.vndk.version` пусто), но
   init после этого продолжает — не фатально для этой перезагрузки. На стенде не воспроизводится:
   там `/vendor` — отдельное дерово с `default.prop` (ro.vndk.version=30), а на телефоне `/vendor`
   из тарболла. **Открытый вопрос:** грузит ли init `/vendor/default.prop` на телефоне. Следующий
   лог покажет; если да — надо воспроизвести стенд с vendor внутри rootfs (как в релизе) и, вероятно,
   пересобрать образ гостя.

### Лог 0.4.4 (2026-11-03) — телефон дошёл до zygote-start
79 с работы: ueventd поднялся (coldboot 33 мс), post-fs-data, vold, apexd, load_persist_props,
**zygote-start**. Перезагрузка из-за bpfloader: `setrlimit(MEMLOCK, 1G)` → EPERM → init абортит.
Обрезка лимита не сработала: трассировщик не смог прочитать лимиты другого процесса (SELinux
на телефоне). Исправлено в 0.4.5: запасной вариант — собственный лимит движка; плюс на выходе
EPERM для гостя с CAP_SYS_RESOURCE скрывается.
**linkerconfig / VENDOR_VNDK_VERSION разгадан:** bootstrap-linkerconfig — статическая программа
(без preload), её `fstat` видел uid приложения у файлов `/dev/__properties__`, bionic отбрасывал
их → свойство «не определено» → нет linker-конфига → statsd не находит libstatssocket. Исправлено:
fstat в preload теперь с меткой; непомеченные `fstat`/`newfstatat`/`statx`/`stat64` трассируются,
и `fixStatOwner` подставляет виртуального владельца в результат на выходе. Тест — статически
собранный `nest_idprog_static` от непривилегированного пользователя.
Один раз замечен нестабильный тест (1 сбой из ~10 прогонов, вывод не сохранился) — следить.

### Лог 0.4.5 (2026-11-03) — без перезагрузок
bpfloader и linkerconfig прошли, перезагрузки нет; движок остановлен на 27-й секунде с кодом 137
(SIGKILL: пользователь нажал «Стоп» или MIUI убил процесс — 236 процессов гостя, 1 ГБ свободной
памяти; уточнить). Падают службы:
- **netd** (exit 1): нет сетевых прав приложения — netlink ROUTE/NFLOG/XFRM, `/proc/sys/net/*`,
  правила маршрутизации. Нужна **виртуальная сеть** (отдельный большой этап): эмуляция rtnetlink
  и сетевых sysctl, либо userspace-сетевой стек. netd рестартует → вместе с ним zygote.
- **vold** (SIGABRT): не читает `/sys/fs/fuse/connections` (mount fusectl игнорировался). 0.4.6:
  fusectl → пустой виртуальный каталог.
- **logd** (exit 1): «failed to set CAP_SYSLOG or CAP_AUDIT_CONTROL» (capset в `drop_privs`). На стенде
  проходило; причина на телефоне не ясна. 0.4.6 пишет в журнал движка, почему отклонён capset/
  ambient raise (старые и новые наборы) — разобрать по следующему логу.
- configstore (minijail seccomp), hwcomposer (Wayland), camera — ожидаемые/следующие этапы.

### Лог 0.4.6 (2026-11-03)
vold больше не падает (fusectl). logd: capset вернул **EFAULT (14)** — движок не прочитал аргументы.
Причина: служба делает `prctl(PR_SET_DUMPABLE, 0)`, после этого `process_vm_readv` к ней разрешён
только с CAP_SYS_PTRACE (на стенде root — проходит). Исправлено в 0.4.7: dumpable виртуальный
(`Creds::dumpable`, PR_GET/SET_DUMPABLE в трассировщике, сброс при exec) — заодно это чинит
брокер binder для таких процессов; `readMem` при отказе читает пословно через PTRACE_PEEKDATA.
Тест воспроизводит ошибку телефона под непривилегированным пользователем.
298 заблокированных `process_vm_readv` (270) — вызовы гостевого crash_dump при сбоях, не критично.
Движок снова остановлен на ~25 с кодом 137 — уточнить у владельца, нажимал ли он «Стоп».

### Лог 0.4.7 (2026-11-03) — «сам останавливается» объяснено
Владелец «Стоп» не нажимает. Остановки (код 137) — это **перезагрузка гостя**: «critical process
'lmkd' exited 4 times» → InitFatalReboot → движок завершает всех. logd больше не падает.
lmkd: `memory.pressure_level` (errno 13) — init делает `chmod 0040` этому файлу; на телефоне
владелец настоящего файла — приложение, и режим 0040 закрывает ему чтение (на стенде root).
Исправлено в 0.4.8 — **виртуальные права файлов**: запись `user.nest.owner` = "uid:gid:mode";
chmod/fchmod/fchmodat (preload и трассировщик, в т.ч. для статических программ) оставляют
владельцу на хосте rw (каталогам rwx), а режим гостя хранят в записи; stat/fstat/statx показывают
его. Тест под непривилегированным пользователем, обычная и статическая программа.

### Лог 0.4.8 (2026-11-03) — стабильно 7 минут без перезагрузок
Владелец остановил сам через 438 с. Перезагрузок нет; идёт цикл: netd падает → по `onrestart`
перезапускаются zygote, surfaceflinger, audioserver и др. Причины в цикле:
- **netd**: нет сетевых прав (netlink route/xfrm, `/sys/class/net`, `/proc/sys/net`) — нужна
  виртуальная сеть (следующий этап). wificond: «Failed to start netlink manager».
- media-службы (mediaextractor, omx, swcodec, configstore): гостевой seccomp-фильтр (minijail)
  отрезал соединение vbinder с брокером → «Binder driver could not be opened». 0.4.9: гостевые
  seccomp-фильтры принимаются, но не устанавливаются (Kind::GuestSeccomp, PR_SET_SECCOMP);
  песочница самого приложения на телефоне остаётся. Тест.
- hwcomposer (Wayland Waydroid) — этап графики.

### Лог 0.4.9 (2026-11-03)
Медиаслужбы (mediaextractor, omx, swcodec, configstore) больше не падают — гостевой seccomp
решён. Цикл перезапусков остаётся только из-за **netd** (17 раз за 129 с). surfaceflinger
абортит: «failed to get hwcomposer service» и не открывает binder в своём потоке — этап графики
(свой HWC вместо Waydroid-Wayland). Следующий шаг — **виртуальная сеть** (без логов владельца,
на стенде), затем графика.

### Виртуальная сеть, шаг 1 (0.5.0)
Все AF_NETLINK-сокеты (route/sock_diag/xfrm/netfilter/uevent) подменяются чёрной дырой AF_UNIX
(Socket-обработчик, уже не только uevent). На стенде netd перестал выходить штатно, **zygote
реально инициализируется** (грузит классы, GC, стартует CameraService). Но netd на стенде всё
ещё падает в `TrafficController::start`: `SkDestroyListener` = socket(AF_NETLINK, SOCK_RAW,
NETLINK_SOCK_DIAG=4) — **qemu-user отклоняет его сам, до системного вызова, движок его не видит**
(подтверждено логом: type=0x80003 proto=4 не доходит до обработчика). На телефоне (без qemu) этот
socket() настоящий → движок подменит → netd не должен падать здесь. **Стенд эту ветку проверить
не может — проверяем 0.5.0 на телефоне.** Остаток цикла перезапусков на стенде — qemu-артефакты.

### Виртуальная сеть, шаг 2 (0.5.1) — netd больше не падает
Лог 0.5.0 с телефона: сокеты открываются, но netd завершался (нет ответов на запросы, закрыты
`/proc/sys/net`, `/sys/class/net`). Шаг 2:
- `netlink_emu.{h,cpp}` + обёртки preload (socket/bind/connect/getsockname/setsockopt/send/sendto/
  sendmsg/write/writev/recvfrom/recvmsg/close): netlink-сокет гостя = один конец socketpair
  (AF_UNIX DGRAM), «ядро» (preload) отвечает на каждый запрос: dump → NLMSG_DONE, прочее → ACK
  (error 0), genl GETFAMILY → -ENOENT; адрес отправителя ответов — ядро (pid 0). qemu на стенде
  AF_NETLINK больше не видит. Статические программы — по-прежнему чёрная дыра трассировщика.
- профиль Android: виртуальные `/proc/sys/net` (create-on-write, conf/neigh для all/default/lo)
  и `/sys/class/net/lo`.
Стенд: netd больше не падает сам (только SIGKILL из цепочки перезапусков). **Цикл теперь
держится на графике:** vendor.hwcomposer (Waydroid, ищет Wayland) exit 1 → onrestart
surfaceflinger → zygote → audioserver/netd/... Следующий этап — графика (свой composer/
Wayland-сервер). `nest/tools/bench/setup-binfmt.sh` — перерегистрация qemu после смены контейнера.

### Графика, шаг 1 + system_server (0.5.2)
- `wayland_server.{h,cpp}`: свой Wayland-сервер для `hwcomposer.waydroid.so` (wl_shm), кадры →
  `STATE/display.fb` (заголовок NFB1 64 байта + пиксели), касания ← `STATE/input.sock`
  (датаграммы `d|u|m ID X Y`). Приложение: `NestDisplayView.kt` (кнопка «Показать экран гостя»),
  `--display 720xH@320` по пропорциям телефона.
- vbinder: экземпляр на каждый open (`Hello::inst`), сокеты потоков на высоких fd + переподключение.
- **Утечка pipe движка (guest_log, без O_CLOEXEC) в init** → zygote: «Unsupported st_mode for FD 5:
  FIFO» при fork system_server. Исправлено (pipe2 O_CLOEXEC). Касалось и телефона.
- `sys.use_memfd=true` в копии vendor build.prop: libcutils берёт memfd, если ядро умеет
  F_SEAL_FUTURE_WRITE (5.1+), иначе сам откатывается на /dev/ashmem (телефон 4.14).
- Стенд-only (`NEST_HIDE_EMU_THREADS=1`, ставить только на qemu): трассировщик прячет поток qemu
  (comm linker/linker64) из getdents64 `/proc/N/task` (иначе zygote вечно ждёт «1 поток» перед
  fork); preload подменяет `set_*_seccomp_filter` / `install_setuidgid_seccomp_filter`
  (C++-символы libandroid_runtime) и `minijail_use_seccomp_filter` — qemu сам отвергает фильтры.
  `NEST_BENCH_ENV="QEMU_STRACE=1;QEMU_LOG_FILENAME=/ql/q-%d"` — strace qemu по процессам
  (каталог `$R/ql`, chmod 777). ART-код (libopenjdk) preload-обёртки НЕ видит (другой namespace).
- Стенд: **system_server стартует** («Entered the Android system server!»), доходит до
  StartWindowManagerService, bootanimation запускается. Цикл: vendor.gralloc-2-0 / audio-hal /
  omx получают SIGPIPE (на qemu место не видно) → onrestart surfaceflinger → zygote.
  wificond падает в цикле. Трассировщик пишет `SIGPIPE: syscall … fd …` при `-v` (точно на arm64?
  arg0 = x0 уже перезаписан — смотреть с осторожностью).

### 0.5.3 — по логу 0.5.2 с телефона
Телефон: system_server доходит до startOtherServices (дальше, чем стенд). Цикл:
1) hwcomposer: `wl_touch.down` с NULL surface (Waydroid не всегда делает xdg toplevel) → libwayland
   рвёт клиента → abort → перезапуск SF/zygote. Исправлено: фокус = toplevel, иначе поверхность,
   выдавшая кадр, иначе любая; без поверхности событие не шлётся.
2) wificond: hwbinder «Binder driver could not be opened» — гонка в брокере: Hello потока читался
   раньше Hello процесса → соединение закрывалось. Исправлено: такие соединения паркуются
   (`orphanThreads`) до прихода процесса.
3) Кнопка экрана по умолчанию называлась «Скрыть экран гостя» — владелец её не узнал; добавлена
   надпись-ожидание на чёрном экране.
Не разобрано: `E/FMQ grantorIdx must be less than 3` (FMQ через vbinder), SF ↔ hwcomposer.

### 0.5.4 — по логу 0.5.3
hwcomposer и wificond больше не падают. system_server убивает Watchdog («Blocked in handler on main
thread»): ждёт ISensors, а Waydroid sensors HAL без `waydroid.stub_sensors_hal=1` сразу выходит
(exit 0). В копию vendor build.prop добавлено `waydroid.stub_sensors_hal=1`,
`waydroid.multi_windows=false`. Стенд: HAL «ready», system_server до StartConnectivityService.

### 0.5.5 — лог 0.5.4 + расширенный лог тестера
Телефон 0.5.4: `startOtherServices` завершён (12.8 с), «SystemServer init took too long», затем
system_server умирает без видимой причины в хвосте logcat → zygote перезапуск. Чтобы видеть причину:
- движок (`-v`): PTRACE_O_TRACEEXIT → `[pid] died NAME: signal N` / `[pid] exit NAME: status N`
  (имя из argv — zygote-дети без exec), `[pid] SENDER sends signal N to T (NAME)` для 6/9/15/3/11
  (без групповых SIGKILL от init, повторы один раз), каждые 30 с `nest: t= mem_avail= swap_free= frames=`.
- лог (`GuestLogDigest.kt`): сводка в начале (смерти служб init, смерти процессов по движку, сигналы,
  вехи загрузки, последний SystemServerTiming, память), события движка без exec, ошибки E/F всего
  logcat со свёрткой повторов и стеками, tombstones (6), ANR (2), DropBox (crash/watchdog/anr).

### 0.5.6 — по логу 0.5.5 (новый лог сразу показал причину)
Телефон: system_server доходит до запуска приложений (systemui, phone, settings:CryptKeeper,
networkstack — все стартуют), но 8 раз падает SIGSEGV: SoundPool/Codec2 (ogg-звук UI) →
`E/ion: open /dev/ion failed: ENOENT` → null deref в libcodec2_vndk. Исправлено: /dev/ion
хоста привязан в гостя (appdomain может открыть его read-only), движок пишет
`nest: host /dev/ion: usable|<ошибка>|absent`. Память: ~0.9–1.1 ГБ свободно, swap убывает
(900 → 500 МБ за 5 мин) — следить. frames=1 (экран почти не обновляется — следующий вопрос).

### 0.5.7 — по логу 0.5.6
/dev/ion usable, SIGSEGV ушёл. Новая цепочка: AMS «Existing provider SettingsProvider is crashing;
detaching …» → приложения ждут провайдер настроек 20 с → ANR networkstack → system_server
«Lost network stack» (сам себя убивает). Причина (по коду AOSP 11): `isProcessAliveLocked()`
сверяет `Process.getUidForPid(pid)` (строка Uid: в /proc/<pid>/status) с uid процесса —
хост показывает uid приложения (10461), а не виртуальный 1000. Исправлено: трассировщик пишет
`<state>/attr/creds-<pid>` («ruid euid suid fsuid rgid egid sgid fsgid»), preload подменяет
Uid:/Gid: при open() /proc/<pid|self>[/task/N]/status (memfd). Тест proc_status_shows_virtual_ids.
Диагностика: `<state>/preload.log` (неудачные open в /proc), `nest: ps` раз в минуту
(процессы не в S/R/I), оба в логе тестера. ART/libopenjdk preload не видит — open() из Java
идёт мимо (только libandroid_runtime и др. из default namespace).

### 0.5.8 — по логу 0.5.7
«is crashing» почти ушёл (подмена Uid сработала), startOtherServices 20.5 с. Новый SIGSEGV
system_server — в нашем коде: vbinder_client.cpp clientIoctl, memcpy в read_buffer=NULL
(write-only BINDER_WRITE_READ, read_size 0) 28 байт ответа — брокер ответил на «брошенное»
ожидание чтения, и ответ достался следующему запросу. Исправлено: новый запрос сбрасывает
`t->waiting` (и пишет `nest: binder: pid … tid … sent a request while its read was pending`),
клиент никогда не копирует больше readSize / в NULL (`oversized read answer` в
vbinder-client.log, EIO). Символизация падений preload: build-id из tombstone →
`app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libnest_preload.so` + llvm-symbolizer.

### 0.5.9 — по логу 0.5.8
Брошенных ожиданий binder не было; SIGSEGV из-за них ушёл. Но «is crashing» → «Lost network stack»
остался: подмена Uid не срабатывала, т.к. libandroid_runtime/libbase (FORTIFY) открывают через
`__open_2`, а он шёл мимо virtualStatus. Теперь `__open_2/__openat_2` → наш openat.
Брокер пишет `nest: binder: pid N (state X) closed its binder device …` (живой процесс потерял
соединение процесса) и `instance reused`. Один SIGSEGV в libbinder (fault -12) после
«broker closed the connection» у того же pid — ждём новые строки брокера.

### 0.5.10 — регрессия 0.5.9
0.5.9: init падал сразу (reboot), probe vbinder «bad version»: `__open_2 → openat(…)` внутри
preload — с FORTIFY (NDK/bionic) 3-аргументный openat() сам превращается в `__openat_2` →
бесконечная рекурсия. Урок: **внутри preload никогда не вызывать имена libc, которые мы
экспортируем** (open/openat/stat/...), только `openImpl`/`pathCall`/`syscall`. Хост-тесты (glibc)
такое не ловят — **перед каждым APK гонять стенд (arm64 preload) хотя бы до ZygoteInit.**
Теперь все open-обёртки → `openImpl`. Стенд: init → zygote → system_server, creds-файлы есть.
Устройство гостя сейчас: ro.product.* = Waydroid / «WayDroid arm64 Device» (из образа).

### 0.6.0 — Android загрузился, экран ожил
Телефон 0.5.10: **«Boot is finished (79 с)», BOOT_COMPLETED, лаунчер и приложения запущены.**
«is crashing» исчез. Остались: media.swcodec abort «decWeak … too many times» (refcount
через vbinder/HIDL), SIGSEGV surfaceflinger (1 раз), Watchdog «Blocked in monitor
StorageManagerService on android.fg» (vold?), «closed its binder device» у живого pid в D.
Экран: frames=1 — это калибровочный 1x1 буфер hwcomposer. Исходники Waydroid hwc
(Apache-2.0, ref в scratchpad) → `select_mode`: `waydroid.active_apps` по умолчанию "none" =
closed_mode (окон нет). Добавлено `waydroid.active_apps=Waydroid` (+ ro.hardware.gralloc=default,
ro.hardware.egl=swiftshader). Стенд: frames растут (~5/с), кадр = загрузочная анимация LineageOS
(docs/first-guest-frame.png). Формат кадров ARGB8888 (0). `NEST_WAYLAND_LOG=файл` — трасса
Wayland-запросов.
Планы владельца (после загрузки): приложения не должны «триггериться» на VM (антиэмуляторные
проверки — убрать следы Waydroid/движка), профиль «сильного устройства» в ro.product.* (выбор
профиля в настройках VM). Ограничение: не обходим Play Integrity/банковскую аттестацию.

### Ждём от владельца
Тест **0.6.0-nest** (было 0.5.10 … 0.5.1) на телефоне (образ уже скачан, ставится поверх): «Система» → «Проверка движка Nest» → «Пробная загрузка
Android 11»: скачать образ → «Загрузить Android 11» → 3–5 минут → «Отправить лог».
В лог попадают вывод движка, kmsg гостя, logcat гостя и (по галочке) трасса binder.
**Следующий шаг — разобрать этот лог** и чинить то, что всплывёт на настоящем ядре 4.14
и под seccomp-политикой приложений Android.

---

## 4. Карта репозитория (`matryoshka/`)

- `nest/` — движок (C++17, CMake). `src/` — модули из таблицы выше, `tests/` — тесты
  (72 на сейчас; `test_vfs.cpp`, `test_tracer.cpp`, `test_vbinder.cpp`; вспомогательные `idprog.cpp` — набор
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
  По решению владельца ключ хранится в репозитории: `app/signing/tester.keystore`
  (alias `androiddebugkey`, пароли `android`, см. `app/signing/README.md`) и подключён в
  `signingConfigs.debug` — сборка из любой сессии и из CI ставится поверх, образ гостя
  сохраняется. Это тестовый ключ, не для публичного релиза.

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

- **Метка быстрого пути** не должна переживать вызов: ядро сохраняет регистры. Любая новая
  обёртка с меткой — только через `taggedSyscall` (метка = `fastPathTag(nr)`, обнуление после).
  Стенд на qemu такие ошибки не ловит — нужен тест на хосте (`fastpath_tag_does_not_leak…`).
- **seccomp-политика приложений Android** (SIGSYS) режет не только mount, но и set*id,
  setgroups, capset и др. Всё, что эмулируется в обработчике seccomp-остановки, должно
  работать и из `handleSigsys`; проверяется `--simulate-app-seccomp`.
- **Стенд ≠ телефон по источнику /vendor:** на стенде `/vendor` подменялся отдельным деревом;
  на телефоне он из тарболла. Проблемы вроде `VENDOR_VNDK_VERSION` всплывают только на телефоне.
  Для верности стенд надо гонять на том же rootfs, что в релизе (vendor внутри).
- **Недампящиеся процессы** (`PR_SET_DUMPABLE 0`, частое у служб Android): чтение их памяти без
  root требует, чтобы флаг оставался включённым у настоящего процесса — он виртуальный.
- **Статические программы гостя** (bootstrap linkerconfig и др.) не получают preload: всё, что
  виртуализирует preload, для них должен делать трассировщик (сейчас — владельцы в stat).
- **Стенд работает под root**, телефон — нет: всё, что требует привилегий хоста (xattr
  `security.*`, отрицательный nice, RT, mknod, lowering oom_score_adj), на стенде проходит
  молча. Проверять такие места тестом от непривилегированного пользователя (`setpriv`) или
  через отдельный корень гостя.
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
