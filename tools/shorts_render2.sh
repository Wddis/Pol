#!/bin/bash
# shorts_render2.sh src.mp4 prefix out.mp4 "s1-e1 s2-e2 ..."   (times in src seconds)
# MAIN Shorts renderer since 12.11.2026 (owner chose design v2 «clean»). Template first:
#   python3 tools/shorts_template2.py --assets clean "СТРОКА 1|СТРОКА 2" prefix [--talk]
# Layout (template v2): screen card 1008x567 at 36,392; webcam card 1008x567 at 36,983; TALK: one card 1008x1158 at 36,392.
# Crops from the 1920x1080 source (env): SCR="w:h:x:y" (screen), CAM="w:h:x:y" (webcam), TALK="w:h:x:y" (full-screen
# webcam layout, build the template with --talk too). Each crop is scaled to FILL its card (centre crop, no squash).
# The webcam is NEVER blurred (owner's rule, 11.11.2026). Old grunge template: tools/shorts_render.sh (archive).
src=$1; pre=$2; out=$3; segs=$4
SCR=${SCR:-1099:618:405:100}; CAM=${CAM:-640:364:1280:716}
fill() { echo "crop=$1,scale=$2:$3:force_original_aspect_ratio=increase:flags=lanczos,crop=$2:$3"; }
i=0; list=""
for r in $segs; do a=${r%-*}; b=${r#*-}
if [ -n "$TALK" ]; then FC="[0:v]$(fill $TALK 1008 1158)[cam];[1:v][cam]overlay=36:392[t2];[t2][2:v]overlay=0:0,fps=60,format=yuv420p[v]"
else FC="[0:v]split[s][c];[s]$(fill $SCR 1008 567)[scr];[c]$(fill $CAM 1008 567)[cam];[1:v][scr]overlay=36:392[t1];[t1][cam]overlay=36:983[t2];[t2][2:v]overlay=0:0,fps=60,format=yuv420p[v]"; fi
ffmpeg -loglevel error -y -ss $a -to $b -i $src -i ${pre}_bg.png -i ${pre}_fg.png -filter_complex "$FC" \
 -map "[v]" -map 0:a -c:v libx264 -preset veryfast -crf 19 -c:a aac -b:a 192k -ar 48000 part_${pre}_$i.mp4 || exit 1
list="$list file '$PWD/part_${pre}_$i.mp4'\n"; i=$((i+1)); done
printf "$list" > parts_${pre}.txt
ffmpeg -loglevel error -y -f concat -safe 0 -i parts_${pre}.txt -c:v copy -af loudnorm=I=-14:TP=-1.5:LRA=11 -c:a aac -b:a 192k -movflags +faststart $out && rm -f part_${pre}_*.mp4 parts_${pre}.txt
