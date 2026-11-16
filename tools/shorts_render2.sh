#!/bin/bash
# shorts_render2.sh src.mp4 prefix out.mp4 "s1-e1 s2-e2 ..."   (times in src seconds)
# MAIN Shorts renderer since 12.11.2026 (owner chose design v2 «clean»). Template first:
#   python3 tools/shorts_template2.py --assets clean "СТРОКА 1|СТРОКА 2" prefix [--talk]
# Layout (template v2): screen card 1008x567 at 36,392; webcam card 1008x567 at 36,983; TALK: one card 1008x1158 at 36,392.
# Crops from the 1920x1080 source (env): SCR="w:h:x:y" (screen), CAM="w:h:x:y" (webcam), TALK="w:h:x:y" (full-screen
# webcam layout, build the template with --talk too). Each crop is scaled to FILL its card (centre crop, no squash).
# Mat is muted by default (mat_mute.py, owner 16.11; MAT=0 turns it off). The webcam is NEVER blurred (owner's rule, 11.11.2026). Old grunge template: tools/shorts_render.sh (archive).
src=$1; pre=$2; out=$3; segs=$4
SCR=${SCR:-1264:711:328:0}; CAM=${CAM:-620:349:1300:716}
fill() { echo "crop=$1,scale=$2:$3:force_original_aspect_ratio=increase:flags=lanczos,crop=$2:$3"; }
i=0; list=""
for r in $segs; do a=${r%-*}; b=${r#*-}
if [ -n "$TALK" ]; then FC="[0:v]$(fill $TALK 1008 1158)[cam];[1:v][cam]overlay=36:392[t2];[t2][2:v]overlay=0:0,fps=60,format=yuv420p[v]"
else FC="[0:v]split[s][c];[s]$(fill $SCR 1008 567)[scr];[c]$(fill $CAM 1008 567)[cam];[1:v][scr]overlay=36:392[t1];[t1][cam]overlay=36:983[t2];[t2][2:v]overlay=0:0,fps=60,format=yuv420p[v]"; fi
ffmpeg -loglevel error -y -ss $a -to $b -i $src -i ${pre}_bg.png -i ${pre}_fg.png -filter_complex "$FC" \
 -map "[v]" -map 0:a -c:v libx264 -preset veryfast -crf 19 -c:a aac -b:a 192k -ar 48000 part_${pre}_$i.mp4 || exit 1
list="$list file '$PWD/part_${pre}_$i.mp4'\n"; i=$((i+1)); done
printf "$list" > parts_${pre}.txt
ffmpeg -loglevel error -y -f concat -safe 0 -i parts_${pre}.txt -c copy cat_${pre}.mp4 && \
 python3 "$(dirname "$0")/norm_audio.py" cat_${pre}.mp4 nm_${pre}.mp4 && \
 if [ "$MAT" = 0 ]; then mv nm_${pre}.mp4 $out; else python3 "$(dirname "$0")/mat_mute.py" nm_${pre}.mp4 $out --report mat_${pre}.txt && rm -f nm_${pre}.mp4 nm_${pre}.mp4.words.json; fi && \
 rm -f part_${pre}_*.mp4 parts_${pre}.txt cat_${pre}.mp4
