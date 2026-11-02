#!/system/bin/sh
# Matryoshka VM — guest-side boot hook (runs as root from init at post-fs-data, before
# system_server). Reads the files the host app wrote into /mvm.

SRC=/mvm

if [ -f "$SRC/adb_keys" ]; then
    mkdir -p /data/misc/adb
    cp "$SRC/adb_keys" /data/misc/adb/adb_keys
    chown system:shell /data/misc/adb/adb_keys
    chmod 0640 /data/misc/adb/adb_keys
fi

if [ -f "$SRC/ipconfig.txt" ]; then
    mkdir -p /data/misc/ethernet
    cp "$SRC/ipconfig.txt" /data/misc/ethernet/ipconfig.txt
    chown system:system /data/misc/ethernet /data/misc/ethernet/ipconfig.txt
    chmod 0700 /data/misc/ethernet
    chmod 0600 /data/misc/ethernet/ipconfig.txt
fi

# key=value properties; read-only ones can still be set once here because nothing has set them.
if [ -f "$SRC/mvm.prop" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            ''|'#'*) ;;
            *) setprop "$key" "$value" ;;
        esac
    done < "$SRC/mvm.prop"
fi

# Kernel command line hand-off (hypervisor / emulator): androidboot.mvm.* -> ro.boot.mvm.*
d="$(getprop ro.boot.mvm.dpi)"
[ -n "$d" ] && [ -z "$(getprop ro.sf.lcd_density)" ] && setprop ro.sf.lcd_density "$d"

exit 0
