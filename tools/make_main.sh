#!/bin/bash
# One command → finished main video (rules of this branch), steps overlapped where possible, resumable.
# usage: tools/make_main.sh VOD FROM TO "cut_a-cut_b cut_a-cut_b ..." NAME [workdir]
#   FROM/TO, cuts — VOD seconds (cuts: empty donations etc., check them with tools/donations.py first)
# result: WORK/NAME.mp4 (1080p60 + intro «Ретро-ТВ»), WORK/NAME_preview_360p30.mp4, WORK/refs/*.jpg
# Steps (each skipped if its output exists → rerun continues after a crash):
#   1 segments 1080p60  ∥  (as soon as cut) 2 promo mask (chat_promo_mask.py) ∥ plan
#   3 render edit2 (no zoom, webcam untouched, blur widget/banner + promo mask) → 4 intro ∥ cover refs → 5 preview
set -e
T=$(cd "$(dirname "$0")" && pwd); VOD=$1; A=$2; B=$3; CUTS=$4; NAME=$5; W=${6:-$PWD/$NAME}
mkdir -p "$W" && cd "$W"; log() { echo "[$(date +%H:%M:%S)] $*" | tee -a make.log; }
if [ ! -s ev.mp4 ]; then
  log "1 download $A-$B"
  SEGS_ONLY=1 python3 $T/vod.py $VOD 1080p60 video.mp4 $(python3 -c "print(int($A)-15)") $(python3 -c "print(int($B)+15)") >> make.log 2>&1
  python3 $T/vodcut.py $VOD 1080p60 video.mp4.segs $(python3 -c "print($A-5)") $(python3 -c "print($B+5)") ev.mp4 > cut.log 2>&1
  rm -rf video.mp4.segs
fi
OFF=$(grep -o "offset [0-9.]*" cut.log | tail -1 | cut -d' ' -f2)
[ -s mask.mkv ] || { log "2 promo mask"; python3 $T/chat_promo_mask.py ev.mp4 mask.mkv > mask.log 2>&1 & MP=$!; }
python3 - "$A" "$B" "$CUTS" "$OFF" "$W" <<'P'
import json, sys
A, B, cuts, off, W = float(sys.argv[1]), float(sys.argv[2]), sys.argv[3], float(sys.argv[4]), sys.argv[5]
cl = []; cur = A
for c in sorted(tuple(map(float, x.split("-"))) for x in cuts.split()):
    cl.append((cur, c[0])); cur = c[1]
cl.append((cur, B))
BL = [[0, 0, 268, 108], [1610, 8, 302, 102]]
json.dump({"zoom": {"factor": 1.5, "anchor": [1, 1]}, "blur": BL, "mask": {"file": W + "/mask.mkv", "x": 1630, "y": 90, "w": 290, "h": 900},
           "clips": [{"s": round(a - off, 2), "e": round(b - off, 2), "zoom": [], "blur": list(BL)} for a, b in cl if b - a > 1]}, open("plan.json", "w"))
print("plan:", len(cl), "clips,", round(sum(b - a for a, b in cl) / 60, 1), "min")
P
[ -n "$MP" ] && wait $MP
if [ ! -s out.mp4 ]; then log "3 render"; python3 $T/edit2.py ev.mp4 plan.json out.mp4 2 >> make.log 2>&1; fi
log "4 intro + refs"; bash $T/add_intro.sh out.mp4 $NAME.mp4 >> make.log 2>&1 &
mkdir -p refs; D=$(ffprobe -v error -show_entries format=duration -of csv=p=0 out.mp4)
for i in 0 1 2 3 4 5; do ffmpeg -loglevel error -y -ss $(python3 -c "print($D*($i+0.5)/6)") -i out.mp4 -frames:v 1 -q:v 2 refs/frame_$i.jpg; done
wait
log "5 preview"; ffmpeg -loglevel error -y -i $NAME.mp4 -vf "scale=640:360,fps=30" -c:v libx264 -preset veryfast -crf 24 -c:a aac -b:a 128k ${NAME}_preview_360p30.mp4
log "DONE $W/$NAME.mp4"
