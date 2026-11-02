# Архитектура Matryoshka

## Слои

| Слой | Где | Ответственность |
|---|---|---|
| UI | `ui/**` | Jetpack Compose, Material 3. Экраны: устройства, создание, живой дисплей, приложения, файлы, настройки, образы, система |
| Оркестратор | `core/VmManager.kt` | Жизненный цикл VM (prepare → start → ждать adbd → ждать `sys.boot_completed` → post-boot), состояния, логи |
| Движки | `core/engine/*`, `assets/engine/*.sh` | Запуск и остановка гостя. Root-движки — shell-скрипты, выполняемые через `su` |
| Гость | `core/guest/*` | Всё, что делается *внутри* VM, идёт через ADB: установка и экспорт APK, файлы, питание, Magisk, microG |
| ADB | `core/adb/*` | Собственная реализация протокола (транспорт, RSA-аутентификация, мультиплексирование потоков, sync). Внешних зависимостей нет |
| Дисплей | `core/display/*` | scrcpy-server (Apache-2.0) внутри гостя → H.264 → `MediaCodec` хоста → `Surface`. Ввод идёт обратно по control-сокету |
| Образы | `core/image/*` | Каталог, докачка, SHA-256, импорт `.zip`, разбор boot.img |
| Хост | `core/host/*` | Проверка возможностей устройства (KVM, binderfs, root, crosvm, AVF) и постоянная `su`-сессия |
| Сервис | `service/VmService.kt` | Foreground-сервис, wakelock только пока работает VM, перестройка сети при смене сети хоста |

## Хранение

```
files/
  images/<image-id>/       образ (только чтение, общий): kernel, ramdisk.img, boot.img, system.img, vendor.img
                           │ или rootfs.tar.gz → rootfs/ (распаковывается один раз, root-owned)
  vms/<vm-id>/
    config.json            VmConfig
    data/                  /data гостя (контейнер) или userdata.img (разреженный; гипервизор и эмулятор)
    share/                 передаётся гостю как /mvm (9p или bind): adb_keys, ipconfig.txt, mvm.prop
    boot/                  boot, пропатченный Magisk (kernel.magisk, ramdisk.magisk.img), — у каждой VM свой
    logs/                  crosvm.log / container.log / qemu.log / console.log
    run.env                параметры для root-скриптов
  keys/adbkey.*            RSA-2048 ключ приложения для adbd гостя
```

Удалить VM — значит удалить её каталог. Образ остаётся общим для остальных VM.

## Сеть и безопасность

* У каждой VM своя /30-сеть `10.77.N.0/30`: хост `.1`, гость `.2`.
* `mvm-net.sh` добавляет policy-routing правило `to 10.77.N.0/30 uidrange <uid приложения>`.
  До гостя может достучаться **только Matryoshka** (и root); другие приложения хоста — нет.
* adbd гостя работает с `ro.adb.secure=1` и доверяет только ключу приложения: ключ
  записывается в `/data/misc/adb/adb_keys` через `/mvm`.
* Исходящий трафик гостя уходит через NAT в текущую сеть хоста. При смене сети вызывается
  `mvm-net.sh refresh`.
* Эмулятор работает без root, поэтому использует user-mode сеть QEMU (slirp) с пробросом adb на
  `127.0.0.1:15500+N`. Защиту обеспечивает adb-ключ.

## Загрузка гостя

**Гипервизор:** `mvm-hypervisor.sh` проверяет, какие флаги поддерживает crosvm (`--block`
или `--disk`, `--net tap-name=` или `--tap-name`, `--gpu`, `--shared-dir`), создаёт tap-интерфейс и
запускает `crosvm run` с ядром и ramdisk. По возможности используется системный
`/apex/com.android.virt/bin/crosvm`. First-stage init гостя монтирует `vda` (system) и `vdb`
(vendor), а `vdc` форматирует при первой загрузке (`formattable`).

**Контейнер:** `unshare --mount --pid --uts --ipc --net [--cgroup] --fork`, затем stage2 внутри:
приватный tmpfs `/dev` с минимальным набором устройств (без блочных!), свой binderfs,
`proc`, `sysfs` только для чтения со скрытыми block-классами, без selinuxfs (libselinux гостя
считает SELinux выключенным, а политика хоста продолжает действовать). Потом `pivot_root` и
`init second_stage`. Ограничения по памяти и CPU задаются через memcg и cpuctl.

**Эмулятор:** `qemu-system-aarch64 -M virt -cpu max` (TCG), virtio-blk/net/gpu, 9p-share.
QEMU распространяется лицензией GPL, поэтому живёт в отдельном APK (`dev.matryoshka.engine.qemu`).
Android разрешает запускать бинарники только из APK, поэтому QEMU лежит там как
`libqemu-system-aarch64.so`.

## Дисплей

Гость рендерит экран сам (virtio-gpu и drm_hwcomposer, либо SwiftShader). scrcpy-server внутри
гостя захватывает дисплей и кодирует его встроенным в гостя кодеком. Приложение открывает
`localabstract:scrcpy_<id>` через ADB и подаёт NAL-пакеты в аппаратный декодер хоста прямо на
`Surface`. При смене ориентации приходит новый SPS/PPS, и декодер пересоздаётся. Касания
передаются в координатах видео (32-байтовые сообщения scrcpy 2.x). Текст с экранной
клавиатуры хоста уходит как `INJECT_TEXT`.

## Почему так, а не иначе

* **Почему не «виртуализация приложений»** в духе VirtualApp: это подмена API внутри одного
  процесса, а не отдельная ОС. Требование было «настоящее устройство внутри устройства».
* **Почему не AVF API напрямую:** `VirtualMachineManager` — системный API, требующий
  `MANAGE_VIRTUAL_MACHINE` (signature|privileged). Обычному приложению он недоступен, поэтому
  используется тот же crosvm через root. Если Matryoshka установлен как привилегированное
  приложение, разрешение уже объявлено в манифесте, и это задел на будущий AVF-лаунчер.
* **Почему свой ADB-клиент:** нет зависимостей, полный контроль над потоками (нужен
  `localabstract:` для дисплея) и простая проверка лицензии.
