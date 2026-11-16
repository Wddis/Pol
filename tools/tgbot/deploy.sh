#!/bin/bash
# One-command deploy of the «Двойная резка» Telegram bot to an always-on Ubuntu server
# (Oracle Cloud Always Free VM — ARM Ampere A1 or x86, Ubuntu 22.04/24.04; any VPS works).
# Run ON THE SERVER:
#   curl -fsSL https://raw.githubusercontent.com/Wddis/Pol/ccr-afb88262-upss12/tools/tgbot/deploy.sh | sudo bash
# It asks for the bot token (hidden input), installs everything, starts the bot as a systemd service
# (auto-restart on crash and after reboot) and pulls updates from the branch every 30 minutes.
set -e
BRANCH=${BRANCH:-ccr-afb88262-upss12}; DIR=/opt/t2x2; ENVF=/etc/t2x2bot.env; U=t2x2
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
echo "== 1/6 system packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq && apt-get install -y -qq git ffmpeg xvfb python3-pip python3-venv curl zip build-essential cmake ca-certificates >/dev/null
if ! command -v node >/dev/null || [ "$(node -v | cut -c2- | cut -d. -f1)" -lt 18 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash - >/dev/null && apt-get install -y -qq nodejs >/dev/null; fi
id $U >/dev/null 2>&1 || useradd -m -s /bin/bash $U
echo "== 2/6 code ($BRANCH)"
if [ -d $DIR/.git ]; then git -C $DIR fetch -q origin $BRANCH && git -C $DIR checkout -q $BRANCH && git -C $DIR reset -q --hard origin/$BRANCH
else git clone -q -b $BRANCH https://github.com/Wddis/Pol $DIR; fi
chown -R $U:$U $DIR
echo "== 3/6 python & node deps (10–20 min the first time: whisper models, llama.cpp build)"
sudo -u $U bash -c "cd $DIR && python3 -m venv .venv && . .venv/bin/activate && pip install -q -U pip && \
  pip install -q requests numpy pillow scipy yt-dlp yt-dlp-ejs faster-whisper rapidocr-onnxruntime && \
  CMAKE_ARGS='-DGGML_NATIVE=ON' pip install -q llama-cpp-python && \
  python -c \"from faster_whisper import WhisperModel as W; [W(m,device='cpu',compute_type='int8') for m in ('base','small')]\" && \
  npm install -s playwright@1 >/dev/null"
cd $DIR && npx -y playwright install-deps chromium >/dev/null 2>&1 || true
sudo -u $U bash -c "cd $DIR && npx -y playwright install chromium >/dev/null"
echo "== 4/6 fonts and the AI model (Qwen2.5-3B, 2 GB)"
sudo -u $U bash -c "mkdir -p $DIR/fonts $DIR/models && cd $DIR/fonts && for p in @fontsource/russo-one@5.3.0 @fontsource/montserrat@5.2.5; do npm pack -s \$p >/dev/null; done && \
  for f in *.tgz; do tar xzf \$f; cp package/files/*-cyrillic-*-normal.woff package/files/*-latin-*-normal.woff . 2>/dev/null; rm -rf package \$f; done"
[ -s $DIR/models/qwen3b.gguf ] || sudo -u $U curl -fsSL -o $DIR/models/qwen3b.gguf https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf
echo "== 5/6 settings"
if [ ! -s $ENVF ]; then
  read -rsp "Bot token from @BotFather (input hidden): " TOK </dev/tty; echo
  read -rp "Your Telegram numeric id (Enter = pair later with /pair code from the log): " OWN </dev/tty
  umask 077; { echo "TG_BOT_TOKEN=$TOK"; [ -n "$OWN" ] && echo "TG_OWNER_ID=$OWN"
    echo "T2X2_WORK=/home/$U/t2x2work"; echo "SHORTS_FONTS=$DIR/fonts"; echo "ASSIST_MODEL=$DIR/models/qwen3b.gguf"
    echo "ASSIST_THREADS=3"; echo "PATH=$DIR/.venv/bin:/usr/local/bin:/usr/bin:/bin"; } > $ENVF
  chown root:$U $ENVF; chmod 640 $ENVF
fi
echo "== 6/6 services"
cat > /etc/systemd/system/t2x2bot.service <<EOF
[Unit]
Description=Dvoinaya Rezka Telegram bot
After=network-online.target
Wants=network-online.target
[Service]
User=$U
WorkingDirectory=$DIR
EnvironmentFile=$ENVF
ExecStart=$DIR/.venv/bin/python $DIR/tools/tgbot/bot.py
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/t2x2bot-update.service <<EOF
[Unit]
Description=Update the bot from the git branch
[Service]
Type=oneshot
ExecStart=/bin/bash -c 'cd $DIR && old=\$(sudo -u $U git rev-parse HEAD) && sudo -u $U git fetch -q origin $BRANCH && sudo -u $U git reset -q --hard origin/$BRANCH && [ "\$old" != "\$(sudo -u $U git rev-parse HEAD)" ] && systemctl restart t2x2bot || true'
EOF
cat > /etc/systemd/system/t2x2bot-update.timer <<EOF
[Unit]
Description=Update the bot every 30 min
[Timer]
OnBootSec=5min
OnUnitActiveSec=30min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload && systemctl enable -q --now t2x2bot t2x2bot-update.timer && systemctl restart t2x2bot
sleep 8; journalctl -u t2x2bot -n 5 --no-pager | sed 's/[0-9]\{9,10\}:[A-Za-z0-9_-]\{30,\}/<token>/g'
echo
echo "DONE. The bot runs as a service (auto-restart, auto-update from $BRANCH every 30 min)."
echo "Logs: journalctl -u t2x2bot -f    · bot logs for Claude: /home/$U/t2x2work/logs/*.jsonl (or /logs in Telegram)"
echo "In Telegram: /pair <code above> (if you did not enter your id) → /cookies (send cookies file) → /panel → /start"
