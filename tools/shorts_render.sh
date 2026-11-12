#!/bin/bash
# shorts_render.sh src.mp4 prefix out.mp4 "s1-e1 s2-e2 ..."   (times in src seconds)
# Layout crops per stream layout (x:y in the 1920x1080 source), override via env:
#   SCR="w:h:x:y"  screen part -> 1080x607 panel   (default: browser layout of T2x2, centre of the player,
#                                                  above the webcam and left of chat/banner)
#   CAM="w:h:x:y"  webcam      -> 1080x615 panel   (default: webcam bottom-right 640x364)
# The webcam is NEVER blurred (owner's rule, 11.11.2026).
src=$1; pre=$2; out=$3; segs=$4
SCR=${SCR:-1099:618:405:100}; CAM=${CAM:-640:364:1280:716}
i=0; list=""
for r in $segs; do a=${r%-*}; b=${r#*-}
ffmpeg -loglevel error -y -ss $a -to $b -i $src -i ${pre}_bg.png -i ${pre}_fg.png -filter_complex "\
[0:v]split[s][c];\
[s]crop=$SCR,scale=1080:607:flags=lanczos[scr];\
[c]crop=$CAM,scale=1080:615:flags=lanczos[cam];\
[1:v][scr]overlay=0:380[t1];[t1][cam]overlay=0:1000[t2];[t2][2:v]overlay=0:0,fps=60,format=yuv420p[v]" \
 -map "[v]" -map 0:a -c:v libx264 -preset veryfast -crf 19 -c:a aac -b:a 192k -ar 48000 part_${pre}_$i.mp4 || exit 1
list="$list file '$PWD/part_${pre}_$i.mp4'\n"; i=$((i+1)); done
printf "$list" > parts_${pre}.txt
ffmpeg -loglevel error -y -f concat -safe 0 -i parts_${pre}.txt -c:v copy -af loudnorm=I=-14:TP=-1.5:LRA=11 -c:a aac -b:a 192k -movflags +faststart $out && rm -f part_${pre}_*.mp4 parts_${pre}.txt
