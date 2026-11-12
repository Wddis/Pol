#!/bin/bash
# every 8 min: refresh VOD audio, transcribe only the new tail, append; fetch new 1080p60 segments
# usage: tools/live_update.sh <VOD_ID> <workdir>   (run detached: setsid nohup ... &). Needs transcript_live.txt in workdir
#   (create it with the first full transcription: python3 tools/stt.py audio.wav transcript_live.txt).
cd "${2:-.}"; S=${FW_SRC_PARENT:-..}; VOD=${1:?usage: live_update.sh VOD_ID [workdir]}
while true; do
  last=$(tail -1 transcript_live.txt | awk -F'-' '{print int($1)}'); [ -z "$last" ] && last=0
  python3 /home/user/Pol/tools/vod.py $VOD Audio_Only audio.mp4 >/dev/null 2>&1 || { echo "audio fetch failed"; sleep 120; continue; }
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 audio.mp4 | cut -d. -f1)
  if [ "$dur" -gt $((last+60)) ]; then
    ffmpeg -loglevel error -y -i audio.mp4 -ac 1 -ar 16000 audio.wav
    nice -n 10 python3 /home/user/Pol/tools/stt.py audio.wav new.txt $((last+1)) $dur >/dev/null 2>&1
    cat new.txt >> transcript_live.txt
    SEGS_ONLY=1 python3 /home/user/Pol/tools/vod.py $VOD 1080p60 video.mp4 $((last-30)) $dur >/dev/null 2>&1
    echo "VOD ${dur}s ($((dur/60)) min); transcript up to $(tail -1 transcript_live.txt | cut -c1-8)s; last: $(tail -1 transcript_live.txt | cut -c17-100)"
  else
    echo "VOD ${dur}s — no new audio (stream ended?)"
  fi
  sleep 480
done
