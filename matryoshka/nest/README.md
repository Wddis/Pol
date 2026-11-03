# Nest — движок userspace-виртуализации

Nest запускает гостевой Android как набор обычных процессов приложения Matryoshka, без root и без
KVM. Это движок для телефонов вроде Redmi Note 8. Подробное обоснование — в
[docs/RESEARCH.md](../docs/RESEARCH.md).

## Что уже есть (этапы 0–2)

| Модуль | Файл | Что делает |
|---|---|---|
| vfs | `src/vfs.*` | Таблица монтирования гостя (длиннейший префикс), разрешение путей со следованием гостевым симлинкам *внутри* гостя, обратное преобразование путей хоста в пути гостя |
| arch | `src/arch.*` | Доступ к регистрам и памяти трассируемого процесса для arm64 (телефоны) и x86_64 (тесты) |
| tracer | `src/tracer.*` | seccomp-фильтр останавливает только «интересные» вызовы (~70 из ~300), ptrace-супервизор переписывает пути, `execve` (запуск через *гостевой* загрузчик, shebang), `getcwd`/`readlink` для `/proc`, AF_UNIX-сокеты, `/proc/self`; поддельный root (uid 0, успешные `setuid`/`chown`); следит за всеми потомками (fork/clone/exec) |
| ids | `src/tracer.cpp` (`handleIds`) | Виртуальные UID/GID у каждого процесса по правилам ядра Linux (`setuid`, `setresuid`, сохранённый UID), наследуются при `fork` и `exec`. Root есть только у тех, кому его дал гость (init) или Magisk |
| fast path | `src/preload.cpp`, `src/fastpath.h` | `libnest_preload.so` подгружается в каждый гостевой процесс (трассировщик подставляет `LD_PRELOAD` при каждом `execve`), сам переводит абсолютные пути и вызывает ядро с меткой в 6-м регистре; seccomp пропускает такие вызовы без остановки. Экспортирует только обёртки libc |
| vbinder | `src/vbinder.*`, `src/vbinder_client.*`, `src/vbinder_proto.h` | Binder-драйвер в userspace для трёх контекстов (binder / hwbinder / vndbinder). Брокер работает потоком внутри `nest-run` (`-B сокет`), клиент — в `libnest_preload.so` и прозрачно подменяет `/dev/*binder` для неизменённого `libbinder`. Реализованы узлы и handles, стеки транзакций у каждого потока (вложенные вызовы), oneway, `BR_ACQUIRE`/`BR_RELEASE`, уведомления о смерти, передача fd (`FD`, `FDA`), scatter-gather буферы (`PTR`, hwbinder), пул потоков. Данные транзакций копируются один раз (`process_vm_readv` в общий буфер получателя). Отправителю сообщается виртуальный UID из трассировщика |
| android | `src/android_profile.*`, `src/guest_log.*`, `src/vfs_share.*` | Окружение для Android-гостя без root: свой `/dev` с разрешёнными приложению устройствами, `/dev/kmsg` в файл (журнал загрузки), подмена `/proc/version`, `/proc/filesystems`, `/proc/cmdline` (без selinuxfs — libselinux гостя видит SELinux выключенным), SELinux-атрибуты процессов (`/proc/*/attr/*`), APEX-модули; ранний logcat гостя из `/dev/socket/logdw`; живая таблица монтирования для быстрого пути. Эмуляция `mount`/`umount2`/`pivot_root`/`reboot` и др., включая случай SIGSYS от seccomp-политики Android |
| caps / pid 1 | `src/tracer.cpp` | Виртуальные capabilities (`capget`/`capset`, bounding/ambient, securebits, keepcaps) с переходами ядра при `setuid` и `exec`; гостевой init видит себя PID 1 (`getpid`, `kill`, `/proc/1`), `kill(-1)` не выходит за пределы гостя; `setrlimit` выше лимита хоста для привилегированного гостя |
| cgroups / mounts | `src/tracer.cpp`, `src/vfs.*` | Новое монтирование скрывает всё смонтированное под ним (кроме инфраструктуры движка); виртуальные cgroup/cpuset с управляющими файлами; `getsockname`/`accept` возвращают гостевые пути сокетов |
| bpf | `src/bpf_emu.*` | eBPF-карты и программы в userspace (для bpfloader, netd, статистики трафика): семантика hash/array/per-CPU/LRU, pin/get в `/sys/fs/bpf`, настоящие fd через подмену вызова на `openat` |
| logcat tee | `src/guest_log.*` | Копия всего logcat гостя в `STATE/logcat.txt` с пересылкой в гостевой logd |
| probe | `src/probe.*` | Реальная проверка механизмов на телефоне (`libnest_run.so --probe DIR`) |
| nest-run | `src/nest_run.cpp` | Супервизор: `nest-run -b /=rootfs -b /data=… -- /system/bin/sh` |

На Android супервизор упакован как `lib/arm64-v8a/libnest_run.so`. Исполнять файлы из каталога
нативных библиотек приложению разрешено всегда. Гостевые бинарники запускаются из данных
приложения, поэтому targetSdk = 28.

## Тесты

```bash
cmake -S nest -B build-nest && cmake --build build-nest -j && ./build-nest/nest_tests
```

66 тестов (включая 32-битные варианты на хостах с `g++-multilib`). Тесты трассировщика собирают мини-rootfs из настоящих бинарников Linux вместе с их
библиотеками и загрузчиком. Это та же ситуация, что с гостевым Android, которому нужен свой
`linker64`. Проверяются: чужой корень, запуск через гостевой загрузчик, shebang, абсолютные
гостевые симлинки, потомки, `getcwd`, `/proc/self/*`, поддельный root, ошибки `ENOENT`, а также
замер стоимости перехвата.

## Дальше

- **Оптимизация перехвата:** кэш разрешённых путей; перевод в быстрый путь `getuid` и других частых вызовов.
- **Этап 3 (идёт):** загрузка Android 11. На локальном стенде (qemu-user вместо процессора
  телефона) настоящий init LineageOS 18.1 доходит до `zygote-start` и `boot`: работают logd, lmkd,
  servicemanager, hwservicemanager, vold, apexd, bpfloader, statsd, zygote/zygote64, surfaceflinger,
  audioserver и HAL-службы. Дальше стенд упирается в ограничения qemu-user (нет seccomp для гостя,
  нет netlink sock_diag), поэтому следующий шаг — пробная загрузка на телефоне (экран «Проверка
  движка Nest» → «Пробная загрузка Android 11»).
- **Этап 4:** графика (свой HWC/gralloc поверх GPU хоста), ввод, звук, сеть.
