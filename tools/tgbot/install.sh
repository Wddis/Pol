#!/bin/bash
# One-time install of the bot's dependencies on Ubuntu/Debian (VPS, Oracle Cloud Free, WSL...). Run from the repo root.
set -e
sudo apt-get update && sudo apt-get install -y ffmpeg xvfb nodejs npm python3-pip zip
pip3 install --break-system-packages requests numpy pillow scipy yt-dlp yt-dlp-ejs faster-whisper rapidocr-onnxruntime
npm install playwright@1 && npx playwright install --with-deps chromium
python3 -c "from faster_whisper import WhisperModel as W; [W(m,device='cpu',compute_type='int8') for m in ('base','small')]"
mkdir -p fonts && cd fonts && for p in @fontsource/russo-one@5.3.0 @fontsource/montserrat@5.2.5; do npm pack $p >/dev/null; done && \
  for f in *.tgz; do tar xzf $f; cp package/files/*-{cyrillic,latin}-*{400,600,800,900}-normal.woff . 2>/dev/null; rm -rf package; done; cd ..
echo "OK. Run:  TG_BOT_TOKEN=... SHORTS_FONTS=$PWD/fonts python3 tools/tgbot/bot.py"
