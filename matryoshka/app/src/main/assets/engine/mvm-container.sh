#!/system/bin/sh
# Matryoshka VM — CONTAINER engine.
#
# Boots the guest Android 11 userspace on the host kernel inside fresh mount / PID / IPC / UTS /
# network (/ cgroup) namespaces, with a private binderfs instance so the guest has its own
# servicemanager, hwservicemanager and vndservicemanager.
#
# Isolation notes (see docs/LIMITATIONS.md):
#  * The guest sees its own filesystem tree, process tree, binder context, network stack.
#  * Block devices of the host are hidden from the guest (no /dev/block, masked sysfs).
#  * This is environment isolation, not a hard security boundary against a malicious guest
#    *root* user — use the HYPERVISOR engine when that matters.
#
# usage: mvm-container.sh <prepare|start|stop|status|destroy|stage2> <run.env>

. "$(dirname "$0")/mvm-common.sh"
ACTION="$1"
mvm_load_env "$2"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
ROOTFS="$MVM_IMAGE_DIR/rootfs"

prepare() {
    [ -d "$ROOTFS/system" ] && { log "rootfs already extracted"; return 0; }
    [ -f "$MVM_IMAGE_DIR/rootfs.tar.gz" ] || die "rootfs.tar.gz missing"
    log "extracting rootfs (one-time, shared by all VMs using this image)"
    mkdir -p "$ROOTFS.tmp"
    tar -xzpf "$MVM_IMAGE_DIR/rootfs.tar.gz" -C "$ROOTFS.tmp" || die "extraction failed"
    mkdir -p "$ROOTFS.tmp/mvm" "$ROOTFS.tmp/data" "$ROOTFS.tmp/dev" "$ROOTFS.tmp/proc" "$ROOTFS.tmp/sys"
    mv "$ROOTFS.tmp" "$ROOTFS"
    log "rootfs ready"
}

start() {
    pid_alive "$(read_pid)" && { log "already running"; return 0; }
    grep -q binder /proc/filesystems || die "host kernel has no binderfs (CONFIG_ANDROID_BINDERFS)"
    [ -d "$ROOTFS/system" ] || prepare
    mkdir -p "$MVM_DATA" "$MVM_SHARE" "$MVM_LOG_DIR"
    chown 1000:1000 "$MVM_DATA"; chmod 0771 "$MVM_DATA"
    set_state starting

    NS="--mount --pid --uts --ipc --net --fork"
    unshare --help 2>&1 | grep -q -- "--cgroup\|-C" && NS="$NS --cgroup"
    # shellcheck disable=SC2086
    nohup unshare $NS sh "$SELF" stage2 "$2" >>"$MVM_LOG_DIR/container.log" 2>&1 &
    UNSHARE_PID=$!
    # The namespace init is the child of `unshare`.
    i=0; NSINIT=""
    while [ $i -lt 50 ] && [ -z "$NSINIT" ]; do
        NSINIT="$(pgrep -P "$UNSHARE_PID" 2>/dev/null | head -n1)"
        [ -z "$NSINIT" ] && sleep 0.1
        i=$((i + 1))
    done
    [ -n "$NSINIT" ] || die "container did not start (see container.log)"
    echo "$NSINIT" > "$MVM_PIDFILE"
    sh "$(dirname "$SELF")/mvm-net.sh" up-veth "$NSINIT" "$2" || log "network setup failed; guest will be offline"
    limit_resources "$NSINIT"
    # Release stage2: it waits for the veth to appear before exec'ing the guest init.
    touch "$MVM_DIR/.net-ready"
    set_state running
    log "container started, ns-init pid $NSINIT"
}

# Best-effort memory / CPU limits via cgroup v1 (Android mounts memcg at /dev/memcg).
limit_resources() {
    if [ -d /dev/memcg ]; then
        CG="/dev/memcg/mvm_$MVM_NET_INDEX"
        mkdir -p "$CG" 2>/dev/null && {
            echo $((MVM_MEM_MB * 1024 * 1024)) > "$CG/memory.limit_in_bytes" 2>/dev/null
            echo "$1" > "$CG/cgroup.procs" 2>/dev/null
        }
    fi
    if [ -d /dev/cpuctl ]; then
        CG="/dev/cpuctl/mvm_$MVM_NET_INDEX"
        mkdir -p "$CG" 2>/dev/null && {
            # cfs quota = cores * period
            echo 100000 > "$CG/cpu.cfs_period_us" 2>/dev/null
            echo $((MVM_CPUS * 100000)) > "$CG/cpu.cfs_quota_us" 2>/dev/null
            echo "$1" > "$CG/cgroup.procs" 2>/dev/null
        }
    fi
}

mknod_like_host() { # name type
    [ -e "/dev/$1" ] || return 0
    MAJ=$((0x$(stat -c %t "/dev/$1"))); MIN=$((0x$(stat -c %T "/dev/$1")))
    mknod -m 666 "$R/dev/$1" "$2" "$MAJ" "$MIN"
}

# Runs as PID 1 of the new namespaces.
stage2() {
    R="$ROOTFS"
    rm -f "$MVM_DIR/.net-ready"
    mount --make-rprivate /

    # Root of the guest. /data is private per VM; the image is shared read-only.
    mount --bind "$R" "$R"
    mount -o remount,bind,ro,suid "$R" 2>/dev/null || mount -o remount,bind,suid "$R"
    mount -t tmpfs -o mode=0755,nosuid tmpfs "$R/dev"
    mount --bind "$MVM_DATA" "$R/data"
    mount -o remount,bind,suid "$R/data"
    mount --bind "$MVM_SHARE" "$R/mvm"
    # Mounts that first-stage init would normally create (we enter init at second_stage).
    # The guest image is built with flattened APEXes, so apexd needs no loop devices.
    mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs "$R/mnt"
    [ -d "$R/debug_ramdisk" ] && mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs "$R/debug_ramdisk"

    # Minimal device set. No block devices: the guest can never see host partitions.
    mknod -m 666 "$R/dev/null" c 1 3
    mknod -m 666 "$R/dev/zero" c 1 5
    mknod -m 666 "$R/dev/full" c 1 7
    mknod -m 666 "$R/dev/random" c 1 8
    mknod -m 666 "$R/dev/urandom" c 1 9
    mknod -m 666 "$R/dev/tty" c 5 0
    ln -s null "$R/dev/kmsg"
    mknod_like_host ashmem c
    mknod_like_host fuse c
    mknod_like_host tun c 2>/dev/null || true
    [ -e /dev/net/tun ] && { mkdir -p "$R/dev/net"; MAJ=$((0x$(stat -c %t /dev/net/tun))); MIN=$((0x$(stat -c %T /dev/net/tun))); mknod -m 666 "$R/dev/net/tun" c $MAJ $MIN; ln -s net/tun "$R/dev/tun"; }
    mknod_like_host ion c 2>/dev/null || true
    [ -d /dev/dma_heap ] && { mkdir -p "$R/dev/dma_heap"; for h in /dev/dma_heap/*; do n=$(basename "$h"); MAJ=$((0x$(stat -c %t "$h"))); MIN=$((0x$(stat -c %T "$h"))); mknod -m 666 "$R/dev/dma_heap/$n" c $MAJ $MIN; done; }
    mkdir -p "$R/dev/pts" "$R/dev/socket" "$R/dev/binderfs"
    mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "$R/dev/pts"
    ln -s pts/ptmx "$R/dev/ptmx"

    # Private binder contexts.
    mount -t binder binder "$R/dev/binderfs" || { echo "binderfs mount failed"; exit 1; }
    for b in binder hwbinder vndbinder; do
        [ -e "$R/dev/binderfs/$b" ] && ln -s "binderfs/$b" "$R/dev/$b"
    done

    # Kernel interfaces. sysfs read-only, block devices masked. selinuxfs is NOT mounted: the
    # guest's libselinux then treats SELinux as disabled, the host policy stays in force.
    mount -t proc proc "$R/proc"
    mount -t sysfs -o ro,nosuid,nodev,noexec sysfs "$R/sys"
    for p in block dev/block class/block class/mtd fs/selinux; do
        [ -d "$R/sys/$p" ] && mount -t tmpfs -o ro,mode=0555 tmpfs "$R/sys/$p" 2>/dev/null
    done

    hostname "mvm-$MVM_NET_INDEX" 2>/dev/null

    # Wait for the host to hand over the veth, then make it the guest's eth0.
    i=0
    while [ ! -f "$MVM_DIR/.net-ready" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
    ip link set lo up
    if ip link show "$MVM_GUEST_IF_TMP" >/dev/null 2>&1; then
        ip link set "$MVM_GUEST_IF_TMP" name eth0
    fi

    cd "$R" || exit 1
    mkdir -p .oldroot
    if pivot_root . .oldroot 2>/dev/null; then
        umount -l /.oldroot
        rmdir /.oldroot 2>/dev/null
        exec /system/bin/env -i PATH=/system/bin:/system/xbin ANDROID_CONTAINER=mvm /system/bin/init second_stage
    else
        exec chroot "$R" /system/bin/env -i PATH=/system/bin:/system/xbin ANDROID_CONTAINER=mvm /system/bin/init second_stage
    fi
}

stop() {
    PID="$(read_pid)"
    set_state stopping
    # The app asks the guest to power off via adb first; this is the fallback.
    kill_tree "$PID" 15
    sh "$(dirname "$SELF")/mvm-net.sh" down "$2"
    rm -f "$MVM_PIDFILE" "$MVM_DIR/.net-ready"
    for cg in "/dev/memcg/mvm_$MVM_NET_INDEX" "/dev/cpuctl/mvm_$MVM_NET_INDEX"; do rmdir "$cg" 2>/dev/null; done
    set_state stopped
    log "container stopped"
}

status() {
    if pid_alive "$(read_pid)"; then echo running; else echo stopped; fi
}

destroy() {
    stop "$@" >/dev/null 2>&1
    rm -rf "$MVM_DATA"
}

case "$ACTION" in
    prepare) prepare ;;
    start) start "$@" ;;
    stage2) stage2 ;;
    stop) stop "$@" ;;
    status) status ;;
    destroy) destroy "$@" ;;
    remove-rootfs) rm -rf "$ROOTFS" "$ROOTFS.tmp" ;;
    *) die "unknown action $ACTION" ;;
esac
