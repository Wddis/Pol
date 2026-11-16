#!/bin/bash
# GitHub Actions runner: start the bot for up to ~5.6 h; the next run (hourly schedule, concurrency queue) takes over.
. ~/venv/bin/activate
mkdir -p ~/t2x2work
if [ -n "$YT_COOKIES_B64" ]; then echo "$YT_COOKIES_B64" | base64 -d > ~/t2x2work/cookies.txt; chmod 600 ~/t2x2work/cookies.txt; fi
export T2X2_WORK=~/t2x2work SHORTS_FONTS=~/fonts ASSIST_MODEL=~/models/qwen3b.gguf ASSIST_THREADS=3 GHA_RUN=1
timeout --signal=TERM ${BOT_MINUTES:-335}m python3 tools/tgbot/bot.py 2>&1 | sed -u 's/[0-9]\{9,10\}:[A-Za-z0-9_-]\{30,\}/<token>/g' || true
echo "bot run finished (next run takes over)"
