#!/bin/bash
# add_intro.sh video.mp4 out.mp4 [intro.mp4]
# Puts the channel intro (assets/intro/intro_t2x2.mp4) in front of a finished MAIN video (not Shorts).
# Rule since 12.11.2026 (owner): every NEW main video gets this intro.
# Fast path: stream copy (no re-encode) when the video matches the intro (1920x1080, 60 fps, h264 yuv420p,
# AAC 48 kHz stereo — what edit2.py produces). Otherwise the intro is re-encoded to match the video first.
set -e
v=$1; out=$2; dir=$(cd "$(dirname "$0")/.." && pwd); intro=${3:-$dir/assets/intro/intro_t2x2.mp4}
sig() { ffprobe -v error -show_entries stream=codec_name,width,height,r_frame_rate,pix_fmt,sample_rate,channels -of csv=p=0 "$1" | sort | tr '\n' ';'; }
tmp=$(mktemp -d)
if [ "$(sig "$v")" != "$(sig "$intro")" ]; then
  echo "params differ -> re-encoding intro to match the video"
  r=$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$v")
  wh=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 "$v")
  ffmpeg -loglevel error -y -i "$intro" -vf "scale=$wh,fps=$r,format=yuv420p" -c:v libx264 -preset veryfast -crf 16 \
    -c:a aac -b:a 192k -ar 48000 -ac 2 -video_track_timescale 15360 "$tmp/intro.mp4"
  intro=$tmp/intro.mp4
fi
printf "file '%s'\nfile '%s'\n" "$intro" "$(cd "$(dirname "$v")" && pwd)/$(basename "$v")" > "$tmp/list.txt"
ffmpeg -loglevel error -y -f concat -safe 0 -i "$tmp/list.txt" -c copy -movflags +faststart "$out"
rm -rf "$tmp"
d_in=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$intro" 2>/dev/null || echo 0)
echo "done $out  video=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$v")s  out=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out")s"
