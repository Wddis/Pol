#!/bin/bash
# GitHub Actions runner: install the bot's dependencies (heavy parts are cached between runs: venv, models, browser).
set -e
sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg xvfb zip >/dev/null
[ -x ~/venv/bin/python ] || python3 -m venv ~/venv
. ~/venv/bin/activate
pip install -q -U pip
pip install -q requests numpy pillow scipy yt-dlp yt-dlp-ejs faster-whisper rapidocr-onnxruntime
python -c "import llama_cpp" 2>/dev/null || pip install -q llama-cpp-python --prefer-binary --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu
python -c "from faster_whisper import WhisperModel as W; [W(m,device='cpu',compute_type='int8') for m in ('base','small')]"
npm install -s --no-save playwright@1 >/dev/null
npx -y playwright install --with-deps chromium >/dev/null
if [ ! -s ~/fonts/russo-one-cyrillic-400-normal.woff ]; then
  mkdir -p ~/fonts && cd ~/fonts && for p in @fontsource/russo-one@5.3.0 @fontsource/montserrat@5.2.5; do npm pack -s $p >/dev/null; done
  for f in *.tgz; do tar xzf $f; cp package/files/*-cyrillic-*-normal.woff package/files/*-latin-*-normal.woff . 2>/dev/null || true; rm -rf package $f; done; cd - >/dev/null
fi
mkdir -p ~/models
[ -s ~/models/qwen3b.gguf ] || curl -fsSL -o ~/models/qwen3b.gguf https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf
echo "install ok"
