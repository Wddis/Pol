# Гостевой образ Android 11

Конфигурация компактного устройства AOSP 11 `mvm_arm64`, который Matryoshka загружает
на всех трёх движках.

```
device/mvm/            дерево устройства: BoardConfig, продукт, HAL, init, fstab, sepolicy, overlay
device/mvm/removepkgs  модуль-«перекрытие», который выкидывает ненужные приложения
patches/               ограниченный signature spoofing для microG (python-патчер по якорям)
kernel/mvm.config      фрагмент конфигурации ядра (virtio, 9p, binderfs, zram)
tools/build-guest.sh   полный конвейер: repo sync → патч → сборка → rootfs → bundle
tools/make-container-rootfs.sh  system.img + vendor.img → rootfs.tar.gz для движка «Контейнер»
tools/mkbundle.py      manifest.json + .zip для импорта, catalog.json для хостинга
```

## Сборка

1. Ядро: возьмите `android11-5.4` или `android12-5.10` common kernel (arm64), объедините
   `gki_defconfig` с `kernel/mvm.config` и соберите `Image`.
2. `tools/build-guest.sh <aosp-dir> <Image>`. Скрипт сделает `repo sync` нужного тега, подключит
   `device/matryoshka/mvm`, применит патч и выполнит `lunch mvm_arm64-userdebug`.
3. Результат: `out/<id>.zip`. Его можно импортировать в приложении («Образы → Импорт файла») или
   выложить файлы в GitHub Releases и сгенерировать каталог:
   `mkbundle.py out/<id> --id <id> --name ... --base-url https://github.com/<you>/<repo>/releases/download/<tag> --catalog catalog.json`.

## Публикация образа и лицензии

В образе есть ядро Linux (GPL-2.0). Публикуя образ, публикуйте и соответствующий исходный
код ядра (или письменное предложение его предоставить) вместе с `kernel/mvm.config`.
Компоненты AOSP распространяются в основном по Apache-2.0. Проприетарных бинарников Google
в образе нет.
