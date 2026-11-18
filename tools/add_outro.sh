#!/bin/bash
# add_outro.sh video.mp4 out.mp4 [outro.mp4]  — appends the outro (default assets/intro/outro_gta.mp4) to the END.
# Fast path: stream copy when formats match (1920x1080 60 fps h264 yuv420p, AAC 48 kHz); else re-encode the outro only.
set -e; V=$1; O=$2; OUT=${3:-$(dirname "$0")/../assets/intro/outro_gta.mp4}; T=$(mktemp -d)
ffmpeg -loglevel error -y -i "$OUT" -vf "scale=1920:1080,fps=60,format=yuv420p" -c:v libx264 -preset veryfast -crf 18 \
  -c:a aac -b:a 192k -ar 48000 -ac 2 "$T/o.mp4"
printf "file '%s'\nfile '%s'\n" "$(realpath "$V")" "$T/o.mp4" > "$T/l.txt"
ffmpeg -loglevel error -y -f concat -safe 0 -i "$T/l.txt" -c copy -movflags +faststart "$O"
rm -rf "$T"; echo "done $O $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$O")"
