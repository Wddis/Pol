#!/bin/bash
# GitHub Actions runner: start the bot for up to ~5.6 h; the next run (hourly schedule, concurrency queue) takes over.
. ~/venv/bin/activate
if [ -z "$TG_BOT_TOKEN" ]; then echo "::error::TG_BOT_TOKEN is empty: add it in Settings → Secrets and variables → Actions → Secrets (Repository secret)"; exit 1; fi
echo "secrets: token yes, owner $([ -n "$TG_OWNER_ID" ] && echo yes || echo NO), cookies $([ -n "$YT_COOKIES_B64" ] && echo yes || echo NO), gemini $([ -n "$GEMINI_API_KEY" ] && echo yes || echo NO)"
# free ~30 GB for 1080p60 renders (preinstalled SDKs the bot never uses)
sudo rm -rf /usr/share/dotnet /usr/local/lib/android /opt/ghc /opt/hostedtoolcache/CodeQL /usr/local/share/boost /usr/share/swift 2>/dev/null || true
df -h ~ | tail -1
mkdir -p ~/t2x2work; W=~/t2x2work
# Studio cookies: the freshest copy (after the owner signed in through the pult) is kept ENCRYPTED in the Actions cache
# (public repo: caches must not hold plain session cookies); the secret is only the first-run fallback.
KEY="$(printf %s "$TG_BOT_TOKEN" | sha256sum | cut -c1-64)"
if [ -s $W/cookies.enc ] && openssl enc -d -aes-256-cbc -pbkdf2 -pass pass:"$KEY" -in $W/cookies.enc -out $W/cookies.txt 2>/dev/null; then
  echo "cookies: from cache"
elif [ -n "$YT_COOKIES_B64" ]; then echo "$YT_COOKIES_B64" | base64 -d > $W/cookies.txt; echo "cookies: from secret"; fi
[ -f $W/cookies.txt ] && chmod 600 $W/cookies.txt
export T2X2_WORK=$W SHORTS_FONTS=~/fonts ASSIST_MODEL=~/models/qwen3b.gguf ASSIST_THREADS=3 GHA_RUN=1
timeout --signal=TERM ${BOT_MINUTES:-335}m python3 tools/tgbot/bot.py 2>&1 | sed -u 's/[0-9]\{9,10\}:[A-Za-z0-9_-]\{30,\}/<token>/g' || true
# keep the session for the next run (encrypted), never the plain file
[ -s $W/cookies.txt ] && openssl enc -aes-256-cbc -pbkdf2 -salt -pass pass:"$KEY" -in $W/cookies.txt -out $W/cookies.enc && rm -f $W/cookies.txt
echo "bot run finished (next run takes over)"
