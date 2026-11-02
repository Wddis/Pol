#!/system/bin/sh
# Matryoshka VM — HYPERVISOR engine (crosvm on KVM).
#
# Starts a real virtual machine with its own Linux kernel. Prefers the host's own AVF crosvm
# (/apex/com.android.virt/bin/crosvm, present on Android 13+ devices with pKVM) and falls back
# to the crosvm engine pack. The command line is assembled from feature probes because crosvm's
# flags changed across releases.
#
# Disks (virtio-blk, in this order => /dev/block/vda, vdb, vdc inside the guest):
#   vda  system.img   read-only, shared by all VMs of this image
#   vdb  vendor.img   read-only, shared
#   vdc  userdata.img read-write, private to this VM (sparse file, formatted by the guest)
# Shared folder: $MVM_SHARE as 9p tag "mvmcfg", mounted at /mvm in the guest.
#
# usage: mvm-hypervisor.sh <start|stop|status> <run.env>

. "$(dirname "$0")/mvm-common.sh"
ACTION="$1"
mvm_load_env "$2"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SOCK="$MVM_DIR/crosvm.sock"

help_has() { echo "$CROSVM_HELP" | grep -q -- "$1"; }

build_args() {
    CROSVM="$MVM_CROSVM"
    [ -x "$CROSVM" ] || die "crosvm not executable: $CROSVM"
    CROSVM_HELP="$("$CROSVM" run --help 2>&1)"

    A="run --disable-sandbox --socket $SOCK"
    A="$A --mem $MVM_MEM_MB --cpus $MVM_CPUS"

    if help_has "--block"; then
        A="$A --block path=$MVM_IMAGE_DIR/system.img,ro=true"
        A="$A --block path=$MVM_IMAGE_DIR/vendor.img,ro=true"
        A="$A --block path=$MVM_DATA/userdata.img,sparse=true"
    else
        A="$A --disk $MVM_IMAGE_DIR/system.img --disk $MVM_IMAGE_DIR/vendor.img --rwdisk $MVM_DATA/userdata.img"
    fi

    if help_has "tap-name"; then
        if help_has "--net "; then A="$A --net tap-name=$MVM_HOST_IF"; else A="$A --tap-name $MVM_HOST_IF"; fi
    fi

    help_has "--shared-dir" && A="$A --shared-dir $MVM_SHARE:mvmcfg:type=p9"

    # Display: virtio-gpu. The guest's drm_hwcomposer + minigbm drive it; the screen reaches the
    # app through the guest-side H.264 encoder (scrcpy server), so no host window is needed.
    case "$MVM_GPU" in
        HOST_ACCELERATED) GPU_BACKEND="gfxstream" ;;
        *) GPU_BACKEND="2d" ;;
    esac
    if [ "$GPU_BACKEND" = "gfxstream" ] && ! "$CROSVM" version 2>&1 | grep -qi gfxstream && ! help_has gfxstream; then
        log "crosvm has no gfxstream support, using 2d backend + guest SwiftShader"
        GPU_BACKEND="2d"
    fi
    if help_has "--gpu"; then
        A="$A --gpu backend=$GPU_BACKEND,width=$MVM_WIDTH,height=$MVM_HEIGHT"
    fi

    help_has "--serial" && A="$A --serial type=file,path=$MVM_LOG_DIR/console.log,hardware=serial,num=1,console=true"

    # crosvm enables virtio-balloon by default, letting the host reclaim idle guest RAM.

    CMDLINE="console=ttyS0 androidboot.hardware=mvm androidboot.mvm.engine=hypervisor"
    CMDLINE="$CMDLINE androidboot.mvm.width=$MVM_WIDTH androidboot.mvm.height=$MVM_HEIGHT androidboot.mvm.dpi=$MVM_DPI"
    CMDLINE="$CMDLINE $MVM_CMDLINE"
    A="$A --initrd $MVM_RAMDISK"
}

start() {
    pid_alive "$(read_pid)" && { log "already running"; return 0; }
    [ -c /dev/kvm ] || die "/dev/kvm not available on this host"
    mkdir -p "$MVM_DATA" "$MVM_SHARE" "$MVM_LOG_DIR"
    if [ ! -f "$MVM_DATA/userdata.img" ]; then
        # Sparse: occupies only what the guest actually writes.
        truncate -s "${MVM_STORAGE_GB}G" "$MVM_DATA/userdata.img" || die "cannot create userdata.img"
    fi
    build_args
    rm -f "$SOCK"
    sh "$SELF_DIR/mvm-net.sh" up-tap "$2" || log "network setup failed; guest will be offline"
    set_state starting
    log "crosvm $A --params \"$CMDLINE\" $MVM_KERNEL"
    # shellcheck disable=SC2086
    nohup "$CROSVM" $A --params "$CMDLINE" "$MVM_KERNEL" >>"$MVM_LOG_DIR/crosvm.log" 2>&1 &
    echo $! > "$MVM_PIDFILE"
    sleep 1
    pid_alive "$(read_pid)" || { set_state failed; tail -n 30 "$MVM_LOG_DIR/crosvm.log" >&2; die "crosvm exited immediately"; }
    set_state running
    log "VM started, pid $(read_pid)"
}

stop() {
    set_state stopping
    PID="$(read_pid)"
    if pid_alive "$PID" && [ -S "$SOCK" ] && [ -x "${MVM_CROSVM:-}" ]; then
        "$MVM_CROSVM" stop "$SOCK" >/dev/null 2>&1
        i=0; while pid_alive "$PID" && [ $i -lt 10 ]; do sleep 1; i=$((i + 1)); done
    fi
    kill_tree "$PID" 5
    sh "$SELF_DIR/mvm-net.sh" down "$2"
    rm -f "$MVM_PIDFILE" "$SOCK"
    set_state stopped
    log "VM stopped"
}

status() {
    if pid_alive "$(read_pid)"; then echo running; else echo stopped; fi
}

case "$ACTION" in
    start) start "$@" ;;
    stop) stop "$@" ;;
    status) status ;;
    *) die "unknown action $ACTION" ;;
esac
