#!/usr/bin/env bash
# Saves the VM disk (and the ssh key that opens it) to a PRIVATE Hugging Face dataset
# repo, or restores it, so a new cloud session skips the hours-long Windows install.
# The image contains an Adobe sign-in session: never store it anywhere public.
#
# Both directions stream in 2 GB parts, so the session disk never has to hold the
# archive and the unpacked VM at the same time.
#
#   HF_TOKEN=... HF_VM_REPO=user/win-adobe-vm vm-image.sh save|restore
set -euo pipefail
VM_DIR=${VM_DIR:-$HOME/winvm}
: "${HF_TOKEN:?set HF_TOKEN (write access to HF_VM_REPO)}" "${HF_VM_REPO:?set HF_VM_REPO, e.g. user/win-adobe-vm}"
export HF_TOKEN HF_VM_REPO

[ -x "$VM_DIR/venv/bin/python" ] || { python3 -m venv "$VM_DIR/venv"; "$VM_DIR/venv/bin/pip" install -q huggingface_hub; }
PY="$VM_DIR/venv/bin/python"

case "${1:-}" in
save)
    "$PY" - <<'EOF'
import os, sys
from huggingface_hub import HfApi
api, repo = HfApi(token=os.environ['HF_TOKEN']), os.environ['HF_VM_REPO']
api.create_repo(repo, repo_type='dataset', private=True, exist_ok=True)
if not api.repo_info(repo, repo_type='dataset').private:
    sys.exit(f'{repo} is not private; refusing to upload the VM image')
if any(f.startswith('winvm/') for f in api.list_repo_files(repo, repo_type='dataset')):
    api.delete_folder('winvm', repo_id=repo, repo_type='dataset')
EOF
    # docker stop sends an ACPI shutdown and waits up to --stop-timeout for Windows.
    docker stop win >/dev/null 2>&1 || true
    # Without dockur's windows.boot marker a restored disk would be wiped and reinstalled.
    [ -f "$VM_DIR/storage/windows.boot" ] || { echo "No windows.boot marker: install not finished?" >&2; exit 1; }
    rm -f "$VM_DIR"/storage/*.iso    # the install ISO is not needed once Windows is on disk
    mkdir -p "$VM_DIR/ssh" && cp ~/.ssh/id_ed25519 ~/.ssh/id_ed25519.pub "$VM_DIR/ssh/"
    # split hands each part to upload-part.py on stdin; it buffers the part in a
    # self-deleting temp file under $VM_DIR and uploads it as winvm/$FILE.
    export TMPDIR="$VM_DIR"
    tar --sparse -C "$VM_DIR" -cf - storage ssh | zstd -T0 -6 -q \
        | split -b 2G -d -a 3 --filter="\"$PY\" \"$(dirname "$0")/upload-part.py\"" - part.
    echo "Saved to hf://datasets/$HF_VM_REPO/winvm/"
    ;;
restore)
    mkdir -p "$VM_DIR"
    parts=$("$PY" - <<'EOF'
import os
from huggingface_hub import HfApi
files = HfApi(token=os.environ['HF_TOKEN']).list_repo_files(os.environ['HF_VM_REPO'], repo_type='dataset')
print('\n'.join(sorted(f for f in files if f.startswith('winvm/part.'))))
EOF
)
    [ -n "$parts" ] || { echo "No saved image in $HF_VM_REPO" >&2; exit 1; }
    for p in $parts; do
        curl -fsSL -H "Authorization: Bearer $HF_TOKEN" "https://huggingface.co/datasets/$HF_VM_REPO/resolve/main/$p"
    done | zstd -d -q | tar --sparse -xf - -C "$VM_DIR"
    mkdir -p ~/.ssh
    install -m 600 "$VM_DIR/ssh/id_ed25519" ~/.ssh/id_ed25519
    install -m 644 "$VM_DIR/ssh/id_ed25519.pub" ~/.ssh/id_ed25519.pub
    echo "Restored $VM_DIR/storage"
    ;;
*) echo "usage: $0 save|restore" >&2; exit 2 ;;
esac
