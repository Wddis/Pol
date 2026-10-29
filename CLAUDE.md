# Notes for Claude sessions

The owner works from an Android phone and talks Russian; reply in Russian.

## Windows VM with Adobe Premiere Pro / After Effects (Higgsfield MCP)

When the owner asks for "the Windows VM", "the emulator", Premiere, After Effects or the
Higgsfield MCP servers, they mean the setup in `adobe-mcp/` (see `adobe-mcp/README.md`):

- `adobe-mcp/vm/start-vm.sh` starts Windows 11 LTSC Evaluation under dockur/windows
  in this container (software emulation, no KVM), installs Node 24, both Higgsfield MCP
  packages and OpenSSH, and registers `higgsfield-use-premiere` /
  `higgsfield-use-after-effects` in Claude Code over ssh (`adobe-mcp/vm/mcp-ssh.sh`).
  Run it in the background (`nohup ... > ~/winvm/start-vm.log &`) and watch the log for
  `VM ready`. Screen: http://127.0.0.1:8006 (drive it with Playwright), ssh: port 2222,
  user `Docker`.
- With `HF_TOKEN` and `HF_VM_REPO` in the environment, start-vm.sh first restores the saved
  disk from that private Hugging Face dataset (about 10-15 minutes instead of a 2-3 hour
  install). After changing the VM (new apps, sign-ins), save it again with
  `adobe-mcp/vm/vm-image.sh save` before the session ends. The image holds an Adobe
  sign-in: never upload it anywhere public.
- Adobe account: Creative Cloud signs in through a device link (`https://to.adobe.com/...`)
  shown in the installer; approve it in a browser signed in to the owner's Adobe ID
  (ask the owner for the e-mail code if a new sign-in needs one; never ask for passwords
  in chat).
- Known limits: no GPU in the VM, roughly 10-30x slower than a real PC, about 30 GB of
  session disk. The Windows evaluation cannot activate through the sandbox's TLS
  interception, so it shuts down about once an hour; the container's restart policy boots it
  again. Plan long operations (downloads, installs, renders) around that.
- dockur keeps the disk only if `~/winvm/storage/windows.boot` exists; without it every
  container start wipes the disk and reinstalls. If the session container restarted while
  Windows setup was still running, the setup is broken anyway; start over.
