#!/bin/bash
# tint_intro.sh PRESET in.mp4 out.mp4 — the same intro in another modern colour grade (the bot's «новое интро в оттенках»).
# Presets: neon (magenta/cyan, punchy) · brand (channel's new blue+yellow design) · cold · warm · pastel · mono_pop.
# Output keeps the intro format (1920x1080@60, H.264 High, AAC 48k) so add_intro.sh can still stream-copy it.
set -e; P=$1; IN=$2; OUT=$3
case "$P" in
  neon)     F="eq=saturation=1.3:contrast=1.08,colorbalance=rs=.04:bs=.2:bh=.12:gh=-.04" ;;
  brand)    F="eq=saturation=1.25:contrast=1.06,colorbalance=bs=.28:bm=.12:rh=.14:gh=.12:bh=-.18" ;;
  cold)     F="colortemperature=temperature=9500,eq=saturation=1.1:contrast=1.05" ;;
  warm)     F="colortemperature=temperature=4200,eq=saturation=1.15:contrast=1.04" ;;
  pastel)   F="eq=saturation=0.75:brightness=0.04:contrast=0.95,colorbalance=rh=.05:bh=.06" ;;
  mono_pop) F="hue=s=0.15,eq=contrast=1.15,colorbalance=bh=.2:bm=.1" ;;
  *) echo "unknown preset $P (neon|brand|cold|warm|pastel|mono_pop)"; exit 2 ;;
esac
ffmpeg -loglevel error -y -i "$IN" -vf "$F,format=yuv420p" -c:v libx264 -preset slow -crf 21 -maxrate 30M -bufsize 60M -profile:v high -r 60 \
  -c:a copy -video_track_timescale 15360 -movflags +faststart "$OUT"
echo "done $OUT"
