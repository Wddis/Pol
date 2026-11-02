#!/system/bin/sh
# Matryoshka VM — private point-to-point network between host and one guest.
#
#   host  10.77.N.1/30  <-- mvmhN (tap or veth) -->  guest eth0 10.77.N.2/30
#
# * Only this app's UID gets a route to the guest (policy rule with uidrange), so other host
#   apps cannot reach the guest's adbd or services.
# * Guest traffic is NATed out of whatever network the host currently uses (Wi-Fi / mobile);
#   `refresh` re-targets it after the host switches networks.
#
# usage: mvm-net.sh <up-tap|up-veth PID|refresh|down> <run.env>

. "$(dirname "$0")/mvm-common.sh"
ACTION="$1"; shift
case "$ACTION" in up-veth) GUEST_PID="$1"; shift ;; esac
mvm_load_env "$1"

# Routing table of the host's current default network (Android keeps one table per network).
upstream_table() {
    ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* table \([^ ]*\).*/\1/p' | head -n1
}

upstream_if() {
    ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n1
}

rules_down() {
    while ip rule del pref $((9000 + MVM_NET_INDEX)) 2>/dev/null; do :; done
    while ip rule del pref $((9300 + MVM_NET_INDEX)) 2>/dev/null; do :; done
    ip route flush table "$MVM_RT_TABLE" 2>/dev/null
}

nat_down() {
    iptables -t nat -D POSTROUTING -s "$MVM_SUBNET" ! -o "$MVM_HOST_IF" -j MASQUERADE 2>/dev/null
    iptables -D FORWARD -i "$MVM_HOST_IF" -j ACCEPT 2>/dev/null
    iptables -D FORWARD -o "$MVM_HOST_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
    iptables -D INPUT -i "$MVM_HOST_IF" -j ACCEPT 2>/dev/null
}

rules_up() {
    rules_down
    # Host -> guest: only our app (and root, for the engine scripts) may use the link.
    ip route add "$MVM_SUBNET" dev "$MVM_HOST_IF" src "$MVM_HOST_IP" table "$MVM_RT_TABLE"
    ip rule add to "$MVM_SUBNET" uidrange "${MVM_APP_UID}-${MVM_APP_UID}" lookup "$MVM_RT_TABLE" pref $((9000 + MVM_NET_INDEX))
    ip rule add to "$MVM_SUBNET" uidrange 0-0 lookup "$MVM_RT_TABLE" pref $((9000 + MVM_NET_INDEX))
    # Guest -> internet: forwarded packets use the current upstream network's table.
    T="$(upstream_table)"
    if [ -n "$T" ]; then
        ip rule add iif "$MVM_HOST_IF" lookup "$T" pref $((9300 + MVM_NET_INDEX))
        log "upstream table: $T via $(upstream_if)"
    else
        log "no upstream network right now; guest will be offline until refresh"
    fi
}

nat_up() {
    nat_down
    echo 1 > /proc/sys/net/ipv4/ip_forward
    iptables -t nat -A POSTROUTING -s "$MVM_SUBNET" ! -o "$MVM_HOST_IF" -j MASQUERADE
    iptables -I FORWARD -i "$MVM_HOST_IF" -j ACCEPT
    iptables -I FORWARD -o "$MVM_HOST_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT
    iptables -I INPUT -i "$MVM_HOST_IF" -j ACCEPT
}

case "$ACTION" in
    up-tap)
        ip link del "$MVM_HOST_IF" 2>/dev/null
        ip tuntap add dev "$MVM_HOST_IF" mode tap || die "cannot create tap (CONFIG_TUN missing?)"
        ip addr add "$MVM_HOST_IP/30" dev "$MVM_HOST_IF"
        ip link set "$MVM_HOST_IF" up
        rules_up; nat_up
        ;;
    up-veth)
        ip link del "$MVM_HOST_IF" 2>/dev/null
        ip link add "$MVM_HOST_IF" type veth peer name "$MVM_GUEST_IF_TMP" || die "cannot create veth (CONFIG_VETH missing?)"
        ip link set "$MVM_GUEST_IF_TMP" netns "$GUEST_PID" || die "cannot move veth into guest"
        ip addr add "$MVM_HOST_IP/30" dev "$MVM_HOST_IF"
        ip link set "$MVM_HOST_IF" up
        # The guest side is renamed to eth0 inside the namespace by mvm-container.sh stage2.
        rules_up; nat_up
        ;;
    refresh)
        rules_up
        ;;
    down)
        rules_down; nat_down
        ip link del "$MVM_HOST_IF" 2>/dev/null
        ;;
    *) die "unknown action $ACTION" ;;
esac
exit 0
