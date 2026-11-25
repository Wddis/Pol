"""Render editable VOD ranges with per-frame masks and a separate audio pass."""
import hashlib
import subprocess
from pathlib import Path

import cv2
import numpy as np

from clip_core import ROOT, OUTPUT, loudness, read, run, write

SIZE = (1280, 720)
FPS = 30
SR = 44100
COLR = "h264_metadata=video_full_range_flag=0:colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1"
cv2.setNumThreads(1)

def bounds(mask, x, y, minimum):
    yy, xx = np.nonzero(mask)
    if len(xx) < minimum:
        return None
    height, width = mask.shape
    return (x + max(0, int(xx.min())-2), y + max(0, int(yy.min())-2),
            x + min(width, int(xx.max())+3), y + min(height, int(yy.max())+3))

def detect_mask(frame):
    # Search the banners within their observed overlay bands. The color
    # support is measured again on every frame, including during rotations.
    left = frame[:75, :185]
    b, g, r = left.astype(np.int16).transpose(2,0,1)
    pale = (b > 126) & (r > 102) & (b-g > 13) & (b-r > 5) & (b-r < 85)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(
        cv2.morphologyEx(pale.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5,5),np.uint8)))
    left_box = None
    if count > 1:
        largest = 1 + int(np.argmax(stats[1:,cv2.CC_STAT_AREA]))
        left_box = bounds(labels == largest, 0, 0, 370)
    right = frame[:78, 1030:]
    b, g, r = right.astype(np.int16).transpose(2,0,1)
    red = (r > 90) & (r-g > 55) & (r-b > 41)
    right_box = bounds(red, 1030, 0, 35)
    if right_box is not None and right_box[2]-right_box[0] > 75:
        # Include the attached image using its neutral highlights below y=15.
        x = max(1198, right_box[2]-15)
        b, g, r = frame[15:72,x:1276].astype(np.int16).transpose(2,0,1)
        icon = bounds((r > 156) & (g > 135) & (b > 113), x, 15, 20)
        if icon:
            right_box = tuple(op(a,b) for a,b,op in zip(right_box,icon,(min,min,max,max)))
    out = {"left":left_box,"right":right_box,"chat":[]}
    region = frame[230:434,1090:1280]
    b, g, r = region.astype(np.int16).transpose(2,0,1)
    maroon = (r > 45)&(r < 95)&(g > 25)&(g < 55)&(b > 30)&(b < 65)&(r-g > 12)&(r > b)
    active = maroon.mean(1) > .2
    for a,z in zip(np.flatnonzero(np.diff(np.r_[False,active,False]))[::2],
                   np.flatnonzero(np.diff(np.r_[False,active,False]))[1::2]):
        if z-a >= 6:
            cols = np.flatnonzero(maroon[a:z].mean(0) > .25)
            if len(cols) > .65 * region.shape[1] and cols[0] < 26:
                out["chat"].append((1090+int(cols[0]),max(230,228+int(a)),
                                    1091+int(cols[-1]),min(434,232+int(z))))
    return out

def paint(frame, mask):
    for box in [mask["left"],mask["right"],*mask["chat"]]:
        if box:
            x,y,r,b = box
            if not (0 <= x < r <= SIZE[0] and 0 <= y < b <= SIZE[1]):
                raise ValueError(f"Маска выходит за кадр: {box}")
            crop = frame[y:b,x:r]
            # Blur at a quarter resolution, then interpolate inside the mask.
            reduced = cv2.resize(crop,(max(1,(r-x)//4),max(1,(b-y)//4)),interpolation=cv2.INTER_AREA)
            soft = cv2.GaussianBlur(reduced,(0,0),4.2,borderType=cv2.BORDER_REPLICATE)
            frame[y:b,x:r] = cv2.resize(soft,(r-x,b-y),interpolation=cv2.INTER_LINEAR)

def video_part(source, start, end, out, track, override=None):
    tmp = out.with_suffix(".tmp.mp4")
    frames = round((end-start)*FPS)
    common = ["ffmpeg","-v","error","-y"]
    enc = ["-an","-c:v","libx264","-threads","2","-preset","veryfast","-crf","20","-profile:v","high",
           "-pix_fmt","yuv420p","-colorspace","bt709","-color_trc","bt709","-color_primaries","bt709",
           "-color_range","tv","-video_track_timescale","15360"]
    if not track:
        run(*common,"-threads","2","-ss",start,"-i",source,"-an","-vf","scale=1280:720,fps=30",
            "-frames:v",frames,*enc,tmp)
        tmp.replace(out)
        return
    supplied = read(override)["frames"] if override else None
    if supplied is not None and len(supplied) != frames:
        raise ValueError(f"В маске {len(supplied)} строк, ожидалось {frames}.")
    masks = []
    row_size = SIZE[0]*SIZE[1]*3
    with subprocess.Popen([*common,"-threads","2","-filter_threads","1","-ss",str(start),"-i",str(source),
                           "-an","-vf","fps=30,scale=1280:720","-frames:v",str(frames),
                           "-pix_fmt","bgr24","-f","rawvideo","-threads","1","-"],
                          stdout=subprocess.PIPE) as decoder, \
         subprocess.Popen([*common,"-f","rawvideo","-pixel_format","bgr24","-video_size","1280x720",
                           "-framerate","30","-i","-",*enc,str(tmp)],stdin=subprocess.PIPE) as encoder:
        for i in range(frames):
            buf = decoder.stdout.read(row_size)
            if len(buf) != row_size:
                break
            frame = np.frombuffer(buf,np.uint8).reshape((720,1280,3)).copy()
            mask = detect_mask(frame) if supplied is None else supplied[i]
            masks.append(mask)
            paint(frame,mask)
            encoder.stdin.write(frame.tobytes())
        encoder.stdin.close()
        if decoder.wait() or encoder.wait() or len(masks) != frames:
            raise RuntimeError(f"Декодировано {len(masks)} из {frames} кадров")
    write(out.with_suffix(".masks.json"),{"fps":FPS,"start_in_input":start,"frames":masks})
    tmp.replace(out)

def pcm(source,start,duration):
    proc = run("ffmpeg","-v","error","-ss",start,"-i",source,"-t",duration,"-map","0:a:0",
               "-ac","2","-ar",SR,"-f","f32le","-",capture_output=True)
    samples = np.frombuffer(proc.stdout,"<f4").reshape(-1,2)
    size = round(duration*SR)
    if len(samples) < size:
        samples = np.pad(samples,((0,size-len(samples)),(0,0)))
    return samples[:size]

def envelope(audio,windows,fade):
    for window in windows:
        a = max(0,round(window["start"]*SR))
        b = min(len(audio),round(window["end"]*SR))
        if a >= b:
            continue
        pad = round(fade*SR)
        before = min(a,pad)
        after = min(len(audio)-b,pad)
        audio[a-before:a] *= np.linspace(1,0,before)[:,None]
        audio[a:b] = 0
        audio[b:b+after] *= np.linspace(0,1,after)[:,None]

def audio_assembly(plan,sources):
    all_audio = np.concatenate([pcm(src,a,b-a) for src,a,b,_ in sources])
    if plan.get("censorship"):
        edits = read(ROOT/plan["censorship"])
        envelope(all_audio,edits["windows"],edits["fade_seconds"])
    audio = OUTPUT/"assembly.wav"
    run("ffmpeg","-v","error","-y","-f","f32le","-ar",SR,"-ac","2","-i","-",
        "-c:a","pcm_f32le",audio,input=all_audio.tobytes())
    lm = loudness(audio)
    target_gain = -14.7 - lm["lufs"]
    print(f"Смесь {lm}; усиление {target_gain:+.1f} dB",flush=True)
    return audio,target_gain

def part_path(cache,src,a,b,track,override=None):
    st = src.stat()
    edited = hashlib.sha256(override.read_bytes()).hexdigest() if override else ""
    key = f"v1:{src.resolve()}:{st.st_size}:{st.st_mtime_ns}:{a}:{b}:{track}:{edited}"
    return cache/(hashlib.sha256(key.encode()).hexdigest()[:16]+".mp4")

def render(edl_path, preview=False):
    plan = read(edl_path)
    OUTPUT.mkdir(exist_ok=True)
    cache = OUTPUT / "parts"
    cache.mkdir(exist_ok=True)
    sources = []
    if plan.get("intro"):
        sources.append((ROOT/plan["intro"],0,plan["intro_seconds"],False))
    for a,b in plan["vod_keep"]:
        sources.append((ROOT/plan["source"],a-plan["offset"],b-plan["offset"],plan.get("track_banners",False)))
    parts = []
    overrides = plan.get("mask_overrides",{})
    for i,(src,a,b,track) in enumerate(sources):
        entry = overrides.get(str(i))
        override = ROOT/entry if entry else None
        part = part_path(cache,src,a,b,track,override)
        if not part.exists():
            print(f"Видео {src.name} {a:.3f}–{b:.3f}",flush=True)
            video_part(src,a,b,part,track,override)
        parts.append(part)
    audio,target_gain = audio_assembly(plan,sources)
    playlist = OUTPUT/"parts.ffconcat"
    playlist.write_text("ffconcat version 1.0\n"+"".join(f"file parts/{p.name}\n" for p in parts))
    out = OUTPUT/("draft.mp4" if preview else "final.mp4")
    temp = out.with_suffix(".tmp.mp4")
    video_args = ["-vf","scale=640:360","-c:v","libx264","-preset","ultrafast","-crf","23"] if preview else ["-c:v","copy","-bsf:v", COLR]
    run("ffmpeg","-v","error","-y","-f","concat","-safe","0","-i",playlist,"-i",audio,
        "-map","0:v:0","-map","1:a:0",*video_args,
        "-af",f"volume={target_gain}dB,alimiter=limit=0.81:attack=5:release=50:level=false:latency=1",
        "-c:a","aac","-b:a","128k","-ar",SR,"-movflags","+faststart",temp)
    temp.replace(out)
    write(OUTPUT/"last_render.json",{"edl":str(edl_path),"preview":preview,"output":out.name,
                                    "parts":[str(p.relative_to(ROOT)) for p in parts]})
    print(out)
