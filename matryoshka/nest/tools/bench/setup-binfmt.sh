#!/bin/bash
# Bench: (re)register qemu-user for arm64/arm guest binaries. A fresh container loses binfmt_misc.
set -u
mount | grep -q binfmt_misc || mount -t binfmt_misc none /proc/sys/fs/binfmt_misc
reg() {  # name magic mask interpreter
    [ -e "/proc/sys/fs/binfmt_misc/$1" ] && return 0
    printf ':%s:M::%s:%s:%s:OCF\n' "$1" "$2" "$3" "$4" > /proc/sys/fs/binfmt_misc/register
}
reg qemu-aarch64 '\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7\x00' \
    '\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff' /usr/bin/qemu-aarch64-static
reg qemu-arm '\x7fELF\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\x28\x00' \
    '\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff' /usr/bin/qemu-arm-static
ls /proc/sys/fs/binfmt_misc
