#!/bin/bash
# mk.sh src.mp4 prefix out.mp4 "s1-e1 s2-e2 ..."   (times in src seconds)
src=$1; pre=$2; out=$3; segs=$4
i=0; list=""
for r in $segs; do a=${r%-*}; b=${r#*-}
ffmpeg -loglevel error -y -ss $a -to $b -i $src -i ${pre}_bg.png -i ${pre}_fg.png -filter_complex "\
[0:v]split[s][c];\
[s]crop=1348:758:12:112,scale=1080:607[scr];\
[c]crop=560:318:1360:762,split[c1][c2];[c2]crop=140:170:420:53,gblur=sigma=25[bl];[c1][bl]overlay=420:53,scale=1080:615[cam];\
[1:v][scr]overlay=0:380[t1];[t1][cam]overlay=0:1000[t2];[t2][2:v]overlay=0:0,fps=60,format=yuv420p[v]" \
 -map "[v]" -map 0:a -c:v libx264 -preset veryfast -crf 19 -c:a aac -b:a 192k -ar 48000 part_$i.mp4 || exit 1
list="$list file '$PWD/part_$i.mp4'\n"; i=$((i+1)); done
printf "$list" > parts.txt
ffmpeg -loglevel error -y -f concat -safe 0 -i parts.txt -c:v copy -af loudnorm=I=-14:TP=-1.5:LRA=11 -c:a aac -b:a 192k -movflags +faststart $out && rm -f part_*.mp4 parts.txt
