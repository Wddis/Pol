#!/bin/bash
# tt_render_v2.sh src.mp4 "LINE1|LINE2" out.mp4 "s1-e1 ..." [--talk]
# Style 2 «simple TikTok»: black background, webcam on top (1080x700), stream screen below (1080x608), caption at the seam
# (red/white, black outline). TALK: webcam only, 1080x1308. Audio: mat_mute + norm_audio from branch ccr-afb88262-upss12 (as is), music bed.
set -e
TOOLS=${TOOLS:-/home/user/pol-tools/tools}; D=$(dirname "$0")
MUSIC=${MUSIC:-/home/user/Pol/music/viyanexe_original_sound.mp3}; MUSIC_DB=${MUSIC_DB:--17}
SCR=${SCR:-1264:711:328:0}; CAM=${CAM:-440:285:1300:745}; TALK=${TALK:-1046:1080:437:0}
src=$1; title=$2; out=$3; segs=$4; talk=$5; pre=v2_$(basename "${out%.*}")
fill() { echo "crop=$1,scale=$2:$3:force_original_aspect_ratio=increase:flags=lanczos,crop=$2:$3"; }
if [ "$talk" = --talk ]; then Y=1500; FC="[0:v]$(fill $TALK 1080 1308)[c];[1:v][c]overlay=0:250[t]"
else Y=950; FC="[0:v]split[a][b];[a]$(fill $CAM 1080 700)[c];[b]$(fill $SCR 1080 608)[s];[1:v][c]overlay=0:250[t0];[t0][s]overlay=0:950[t]"; fi
python3 $D/tt_v2_overlay.py "$title" ${pre}_txt.png $Y
ffmpeg -loglevel error -y -f lavfi -i color=black:s=1080x1920:r=60 -frames:v 1 ${pre}_bg.png
i=0; list=""
for r in $segs; do a=${r%-*}; b=${r#*-}
ffmpeg -loglevel error -y -ss $a -to $b -i $src -loop 1 -i ${pre}_bg.png -loop 1 -i ${pre}_txt.png -filter_complex \
 "$FC;[t][2:v]overlay=0:0,fps=60,format=yuv420p[v]" -map "[v]" -map 0:a -shortest -c:v libx264 -preset veryfast -crf 19 \
 -c:a aac -b:a 192k -ar 48000 part_${pre}_$i.mp4
list="$list file '$PWD/part_${pre}_$i.mp4'\n"; i=$((i+1)); done
printf "$list" > parts_${pre}.txt
ffmpeg -loglevel error -y -f concat -safe 0 -i parts_${pre}.txt -c copy cat_${pre}.mp4
python3 $TOOLS/norm_audio.py cat_${pre}.mp4 nm_${pre}.mp4
python3 $TOOLS/mat_mute.py nm_${pre}.mp4 voice_${pre}.mp4 --report mat_${pre}.txt
d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 voice_${pre}.mp4); fo=$(python3 -c "print(max(0,$d-1.2))")
ffmpeg -loglevel error -y -i voice_${pre}.mp4 -stream_loop -1 -i "$MUSIC" -filter_complex \
 "[1:a]aresample=48000,volume=${MUSIC_DB}dB,afade=t=in:d=0.3,afade=t=out:st=$fo:d=1.2[m];[0:a][m]amix=inputs=2:duration=first:normalize=0[a]" \
 -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -ar 48000 -t $d mix_${pre}.mp4
python3 $TOOLS/norm_audio.py mix_${pre}.mp4 "$out" --tp -3
rm -f part_${pre}_*.mp4 parts_${pre}.txt cat_${pre}.mp4 nm_${pre}.mp4* voice_${pre}.mp4* mix_${pre}.mp4 ${pre}_bg.png ${pre}_txt.png
