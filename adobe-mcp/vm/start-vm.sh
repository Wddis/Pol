#!/usr/bin/env bash
# Starts the Windows 11 VM (dockur/windows; pure software emulation when /dev/kvm is
# missing) that hosts the Higgsfield Premiere Pro / After Effects MCP servers, then
# registers both servers in Claude Code over ssh.
#
# With HF_TOKEN and HF_VM_REPO set, a saved image is restored first (see vm-image.sh);
# otherwise this does a fresh unattended install (one to two hours without KVM).
#
#   adobe-mcp/vm/start-vm.sh            # screen: http://127.0.0.1:8006  ssh: port 2222
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
VM_DIR=${VM_DIR:-$HOME/winvm}
# Windows 11 IoT Enterprise LTSC 2024 evaluation (24H2, 90 days from install), fetched by
# dockur from Microsoft's Evaluation Center. The Windows 10 LTSC 2021 evaluation image is
# past its built-in expiry and shuts itself down about once an hour.
WIN_VERSION=${WIN_VERSION:-11l}
NODE_VERSION=24.22.1
OPENSSH_URL=https://github.com/PowerShell/Win32-OpenSSH/releases/download/v9.8.3.0p2-Preview/OpenSSH-Win64.zip
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)
mkdir -p "$VM_DIR/storage" "$VM_DIR/oem"

command -v ssh-keygen >/dev/null && command -v zstd >/dev/null || apt-get install -y -q openssh-client zstd >/dev/null
docker info >/dev/null 2>&1 || {
    nohup dockerd >"$VM_DIR/dockerd.log" 2>&1 &
    for _ in $(seq 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
}

if [ ! -f "$VM_DIR/storage/data.img" ] && [ -n "${HF_TOKEN:-}" ] && [ -n "${HF_VM_REPO:-}" ]; then
    "$HERE/vm-image.sh" restore || echo "No saved image restored; doing a fresh install."
fi
[ -f ~/.ssh/id_ed25519 ] || { mkdir -p ~/.ssh; ssh-keygen -q -t ed25519 -N '' -f ~/.ssh/id_ed25519; }

# The sandbox re-signs TLS with its own CAs; hand them to Windows one file per certificate.
export_sandbox_cas() {
    rm -f "$1"/ca-*.crt
    cp /etc/ssl/certs/ca-certificates.crt "$1/ca-bundle.crt"
    python3 - "$1" <<'EOF'
import re, subprocess, sys
pem = open('/etc/ssl/certs/ca-certificates.crt').read()
for i, c in enumerate(re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', pem, re.S)):
    subject = subprocess.run(['openssl', 'x509', '-noout', '-subject'], input=c, capture_output=True, text=True).stdout
    if 'Anthropic' in subject:
        open(f'{sys.argv[1]}/ca-{i}.crt', 'w').write(c + '\n')
EOF
}

run_args=(-v "$VM_DIR/storage:/storage")
if [ ! -f "$VM_DIR/storage/data.img" ]; then
    cp "$HERE/oem/install.bat" "$HERE/oem/install.ps1" "$VM_DIR/oem/"
    [ -f "$VM_DIR/oem/node-x64.msi" ] || curl -fsSL -o "$VM_DIR/oem/node-x64.msi" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-x64.msi"
    [ -f "$VM_DIR/oem/OpenSSH-Win64.zip" ] || curl -fsSL -o "$VM_DIR/oem/OpenSSH-Win64.zip" "$OPENSSH_URL"
    cp ~/.ssh/id_ed25519.pub "$VM_DIR/oem/administrators_authorized_keys"
    export_sandbox_cas "$VM_DIR/oem"
    run_args+=(-v "$VM_DIR/oem:/oem")
fi

if [ -e /dev/kvm ]; then run_args+=(--device /dev/kvm -e KVM=Y); else run_args+=(-e KVM=N); fi
docker rm -f win >/dev/null 2>&1 || true
# The host CA bundle lets dockur's own ISO download pass the sandbox's TLS interception.
docker run -d --name win -e VERSION="$WIN_VERSION" \
    -v /etc/ssl/certs/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt:ro -e DISK_SIZE=44G -e RAM_SIZE=8G -e CPU_CORES="$(nproc)" \
    -e USER_PORTS=22 -p 127.0.0.1:8006:8006 -p 127.0.0.1:2222:22 --cap-add NET_ADMIN \
    --stop-timeout 300 "${run_args[@]}" dockurr/windows >/dev/null

echo "Waiting for Windows and its ssh server (screen: http://127.0.0.1:8006)..."
until ssh "${SSH_OPTS[@]}" -p 2222 Docker@127.0.0.1 'C:\npm-global\fnf-premiere.cmd doctor' >/dev/null 2>&1; do
    [ "$(docker inspect -f '{{.State.Status}}' win)" = running ] || { docker logs win | tail -20; exit 1; }
    sleep 30
done

# A restored image may trust an older generation of the sandbox CAs.
CA_TMP=$(mktemp -d); export_sandbox_cas "$CA_TMP"
scp "${SSH_OPTS[@]}" -P 2222 "$CA_TMP"/ca-*.crt Docker@127.0.0.1:C:/OEM/ >/dev/null
ssh "${SSH_OPTS[@]}" -p 2222 Docker@127.0.0.1 'for %f in (C:\OEM\ca-*.crt) do @certutil -addstore -f Root %f >nul' || true
rm -rf "$CA_TMP"

if command -v claude >/dev/null; then
    for entry in 'higgsfield-use-premiere @higgsfield_org\premiere-mcp' 'higgsfield-use-after-effects fnf-after-effects-mcp'; do
        set -- $entry
        claude mcp get "$1" >/dev/null 2>&1 || claude mcp add "$1" -- "$HERE/mcp-ssh.sh" "$2"
    done
fi
echo "VM ready. ssh: ssh -p 2222 Docker@127.0.0.1"
