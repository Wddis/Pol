#!/system/bin/sh
# Matryoshka VM — shared helpers for the root engine scripts.
# Sourced by mvm-net.sh, mvm-container.sh and mvm-hypervisor.sh.
#
# Every script receives the path of a run.env file written by the app. It defines (quoted):
#   MVM_ID MVM_DIR MVM_IMAGE_DIR MVM_DATA MVM_SHARE MVM_LOG_DIR MVM_ENGINE_DIR
#   MVM_MEM_MB MVM_CPUS MVM_NET_INDEX MVM_HOST_IP MVM_GUEST_IP MVM_APP_UID
#   MVM_WIDTH MVM_HEIGHT MVM_DPI MVM_GPU MVM_STORAGE_GB MVM_KERNEL MVM_RAMDISK MVM_CMDLINE MVM_CROSVM

set -u

mvm_load_env() {
    [ -f "$1" ] || { echo "run.env not found: $1" >&2; exit 2; }
    . "$1"
    MVM_HOST_IF="mvmh${MVM_NET_INDEX}"
    MVM_GUEST_IF_TMP="mvmg${MVM_NET_INDEX}"
    MVM_SUBNET="10.77.${MVM_NET_INDEX}.0/30"
    MVM_RT_TABLE=$((9700 + MVM_NET_INDEX))
    MVM_PIDFILE="$MVM_DIR/engine.pid"
    MVM_STATEFILE="$MVM_DIR/engine.state"
}

log() { echo "[mvm $(date +%H:%M:%S)] $*"; }
die() { echo "[mvm] ERROR: $*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

pid_alive() { [ -n "${1:-}" ] && [ -d "/proc/$1" ]; }

read_pid() { [ -f "$MVM_PIDFILE" ] && cat "$MVM_PIDFILE" 2>/dev/null; }

set_state() { echo "$1" > "$MVM_STATEFILE"; }

# Kill a process tree, politely first.
kill_tree() {
    _pid="$1"; _grace="${2:-10}"
    pid_alive "$_pid" || return 0
    kill -TERM "$_pid" 2>/dev/null
    _i=0
    while pid_alive "$_pid" && [ $_i -lt "$_grace" ]; do sleep 1; _i=$((_i + 1)); done
    pid_alive "$_pid" && kill -KILL "$_pid" 2>/dev/null
    return 0
}
