#!/usr/bin/env bash
# Saves the VM disk (and the ssh key that opens it) to a PRIVATE Hugging Face dataset
# repo, or restores it, so a new cloud session skips the hour-long Windows install.
# The image contains an Adobe sign-in session: never store it anywhere public.
#
#   HF_TOKEN=... HF_VM_REPO=user/win-adobe-vm vm-image.sh save|restore
set -euo pipefail
VM_DIR=${VM_DIR:-$HOME/winvm}
: "${HF_TOKEN:?set HF_TOKEN (write access to HF_VM_REPO)}" "${HF_VM_REPO:?set HF_VM_REPO, e.g. user/win-adobe-vm}"
ARCHIVE=winvm.tar.zst

[ -x "$VM_DIR/venv/bin/python" ] || { python3 -m venv "$VM_DIR/venv"; "$VM_DIR/venv/bin/pip" install -q huggingface_hub; }
PY="$VM_DIR/venv/bin/python"

case "${1:-}" in
save)
    # docker stop sends an ACPI shutdown and waits up to --stop-timeout for Windows.
    docker stop win >/dev/null 2>&1 || true
    rm -f "$VM_DIR"/storage/*.iso    # the install ISO is not needed once Windows is on disk
    mkdir -p "$VM_DIR/ssh" && cp ~/.ssh/id_ed25519 ~/.ssh/id_ed25519.pub "$VM_DIR/ssh/"
    tar --sparse -C "$VM_DIR" -cf - storage ssh \
        | zstd -T0 -6 -q -o "$VM_DIR/$ARCHIVE"
    "$PY" - "$VM_DIR/$ARCHIVE" <<'EOF'
import os, sys
from huggingface_hub import HfApi
api, repo = HfApi(token=os.environ['HF_TOKEN']), os.environ['HF_VM_REPO']
api.create_repo(repo, repo_type='dataset', private=True, exist_ok=True)
if not api.repo_info(repo, repo_type='dataset').private:
    sys.exit(f'{repo} is not private; refusing to upload the VM image')
api.upload_file(path_or_fileobj=sys.argv[1], path_in_repo='winvm.tar.zst', repo_id=repo, repo_type='dataset')
EOF
    rm -f "$VM_DIR/$ARCHIVE"
    echo "Saved to hf://datasets/$HF_VM_REPO/$ARCHIVE"
    ;;
restore)
    mkdir -p "$VM_DIR"
    "$PY" - "$VM_DIR" <<'EOF'
import os, sys
from huggingface_hub import hf_hub_download
hf_hub_download(os.environ['HF_VM_REPO'], 'winvm.tar.zst', repo_type='dataset', local_dir=sys.argv[1], token=os.environ['HF_TOKEN'])
EOF
    zstd -d -q -c "$VM_DIR/$ARCHIVE" | tar --sparse -xf - -C "$VM_DIR"
    mkdir -p ~/.ssh
    install -m 600 "$VM_DIR/ssh/id_ed25519" ~/.ssh/id_ed25519
    install -m 644 "$VM_DIR/ssh/id_ed25519.pub" ~/.ssh/id_ed25519.pub
    rm -f "$VM_DIR/$ARCHIVE"
    echo "Restored $VM_DIR/storage"
    ;;
*) echo "usage: $0 save|restore" >&2; exit 2 ;;
esac
