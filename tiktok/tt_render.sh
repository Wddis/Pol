#!/bin/bash
# tt_render.sh src.mp4 "СТРОКА 1|СТРОКА 2" out.mp4 "s1-e1 s2-e2 ..." [--talk]
# TikTok cut in the YouTube Shorts style v2 «clean», using the tools of branch ccr-afb88262-upss12 AS IS
# (read-only copy at $TOOLS, default /home/user/pol-tools/tools), plus a music bed under the voice.
# env: SCR/CAM/TALK crops (as shorts_render2.sh), MUSIC (track), MUSIC_DB (bed gain, default -17), MUSIC_SS (start, s).
set -e
TOOLS=${TOOLS:-/home/user/pol-tools/tools}
export SHORTS_FONTS=${SHORTS_FONTS:-/tmp/claude-0/-home-user-Pol/21f855aa-1037-5ef8-98f2-de37327dd4d8/scratchpad/fonts/all}
MUSIC=${MUSIC:-/home/user/Pol/music/viyanexe_original_sound.mp3}; MUSIC_DB=${MUSIC_DB:--17}; MUSIC_SS=${MUSIC_SS:-0}
src=$1; title=$2; out=$3; segs=$4; talk=$5
pre=tt_$(basename "${out%.*}")
python3 $TOOLS/shorts_template2.py --assets clean "$title" $pre $talk
[ "$talk" = --talk ] && export TALK=${TALK:-956:1080:402:0}
bash $TOOLS/shorts_render2.sh "$src" $pre voice_$pre.mp4 "$segs"
d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 voice_$pre.mp4)
fo=$(python3 -c "print(max(0,$d-1.2))")
ffmpeg -loglevel error -y -i voice_$pre.mp4 -stream_loop -1 -ss $MUSIC_SS -i "$MUSIC" -filter_complex \
 "[1:a]aresample=48000,volume=${MUSIC_DB}dB,afade=t=in:d=0.3,afade=t=out:st=$fo:d=1.2[m];[0:a][m]amix=inputs=2:duration=first:normalize=0[a]" \
 -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -ar 48000 -t $d mix_$pre.mp4
python3 $TOOLS/norm_audio.py mix_$pre.mp4 "$out"
rm -f voice_$pre.mp4 mix_$pre.mp4 ${pre}_bg.png ${pre}_fg.png
