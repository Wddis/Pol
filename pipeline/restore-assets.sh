#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p evidence
git show 12ffd76:assets/intro/intro_t2x2.mp4 > evidence/intro-original-2026-11-12.mp4.tmp
mv evidence/intro-original-2026-11-12.mp4.tmp evidence/intro-original-2026-11-12.mp4
git show 12ffd76:assets/intro/avatar_channel.png > evidence/avatar-channel-2026-11-12.png.tmp
mv evidence/avatar-channel-2026-11-12.png.tmp evidence/avatar-channel-2026-11-12.png
