#!/bin/bash
# One command → finished main video (rules of this branch), steps overlapped where possible, resumable.
# usage: [ORIG=<original YouTube id> | GAME=1] [MAT=0] [PROMO=1] tools/make_main.sh VOD FROM TO "cut_a-cut_b cut_a-cut_b ..." NAME [workdir]
#   FROM/TO, cuts — VOD seconds (cuts: empty donations etc., check them with tools/donations.py first)
# result: WORK/NAME.mp4 (1080p60 + intro «Ретро-ТВ»), WORK/NAME_preview_360p30.mp4, WORK/refs/ (strict cover_refs set: original cover, faces ×8, cutouts ×4, screens ×4, sheet, zip)
# Steps (each skipped if its output exists → rerun continues after a crash):
#   1 segments 1080p60  ∥  2 plan  (chat promo mask only with PROMO=1 — off since 16.11)
#   3 render edit2 (no zoom, webcam untouched, blur widget/banner) → 4 intro → mat mute (mat_mute.py, MAT=0 off) ∥ cover refs → 5 preview
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
# chat promo blur — OFF since 16.11 (owner: promo in chat most likely does not affect 18+); PROMO=1 turns it back on
if [ "$PROMO" = 1 ]; then [ -s mask.mkv ] || { log "2 promo mask"; python3 $T/chat_promo_mask.py ev.mp4 mask.mkv > mask.log 2>&1 & MP=$!; }; fi
python3 - "$A" "$B" "$CUTS" "$OFF" "$W" <<'P'
import json, sys
A, B, cuts, off, W = float(sys.argv[1]), float(sys.argv[2]), sys.argv[3], float(sys.argv[4]), sys.argv[5]
cl = []; cur = A
for c in sorted(tuple(map(float, x.split("-"))) for x in cuts.split()):
    cl.append((cur, c[0])); cur = c[1]
cl.append((cur, B))
BL = [[0, 0, 268, 108], [1610, 8, 302, 102]]
import os
mask = {"mask": {"file": W + "/mask.mkv", "x": 1630, "y": 90, "w": 290, "h": 900}} if os.environ.get("PROMO") == "1" else {}
# BLUR_SCENES="a-b a-b" (VOD s): blur the WHOLE game picture except the webcam window (18+ scenes; owner 18.11: blur, don't cut)
# webcam window of the layout: env BLUR_CAM="x,y" (top-left of the webcam window, it runs to the bottom-right corner)
cx, cy = map(int, os.environ.get("BLUR_CAM", "1398,662").split(","))
SC = []
for r in os.environ.get("BLUR_SCENES", "").split():
    a0, b0 = map(float, r.split("-")); SC += [[0, 0, 1920, cy, a0 - off, b0 - off], [0, cy, cx, 1080 - cy, a0 - off, b0 - off]]
json.dump({"zoom": {"factor": 1.5, "anchor": [1, 1]}, "blur": BL, **mask,
           "clips": [{"s": round(a - off, 2), "e": round(b - off, 2), "zoom": [], "blur": list(BL) + SC} for a, b in cl if b - a > 1]}, open("plan.json", "w"))
print("plan:", len(cl), "clips,", round(sum(b - a for a, b in cl) / 60, 1), "min")
P
[ -n "$MP" ] && wait $MP
if [ ! -s out.mp4 ]; then log "3 render"; python3 $T/edit2.py ev.mp4 plan.json out.mp4 2 >> make.log 2>&1; fi
log "4 intro (+ mat mute) + refs"
( bash $T/add_intro.sh out.mp4 ${NAME}_nomm.mp4 >> make.log 2>&1 && \
  if [ "$MAT" = 0 ]; then mv ${NAME}_nomm.mp4 $NAME.mp4; \
  else python3 $T/mat_mute.py ${NAME}_nomm.mp4 $NAME.mp4 --report mat_report.txt >> make.log 2>&1 && rm -f ${NAME}_nomm.mp4; fi ) &
# cover refs — STRICT set (docs/cover-refs.md): reaction → ORIG=<YouTube id/url of the original> (its cover is required),
# game → GAME=1. Without either the step fails on purpose (no refs without the original's cover).
if [ -n "$ORIG" ]; then python3 $T/cover_refs.py out.mp4 refs --orig "$ORIG" >> make.log 2>&1
elif [ -n "$GAME" ]; then python3 $T/cover_refs.py out.mp4 refs --game >> make.log 2>&1
else log "REFS: set ORIG=<original id> (reaction) or GAME=1 (game) and rerun"; fi
wait
log "5 preview"; ffmpeg -loglevel error -y -i $NAME.mp4 -vf "scale=640:360,fps=30" -c:v libx264 -preset veryfast -crf 24 -c:a aac -b:a 128k ${NAME}_preview_360p30.mp4
log "DONE $W/$NAME.mp4"
