# The bot's own cloud Linux («песочница агента»): a desktop on Xvfb + dev tools. No bot secrets inside.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 DISPLAY=:1
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
      python3 python3-pip python3-venv ffmpeg git curl wget ca-certificates xvfb fluxbox xterm xdotool imagemagick \
      fonts-dejavu-core fonts-noto-color-emoji nodejs npm jq zip unzip procps nano x11-apps >/dev/null \
 && pip3 install -q --break-system-packages numpy pillow matplotlib requests \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /work
# desktop: virtual screen 1600x900 + window manager; the agent screenshots it with `import -window root`
CMD ["bash", "-c", "Xvfb :1 -screen 0 1600x900x24 >/dev/null 2>&1 & sleep 1; fluxbox >/dev/null 2>&1 & sleep infinity"]
