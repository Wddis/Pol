"""Workers for native-resolution rendering and reusable analysis."""
import hashlib
import json
import re
import subprocess
from fractions import Fraction
from pathlib import Path

from clip_core import probe, read, run, write

FF = ("ffmpeg","-nostdin","-hide_banner","-loglevel","error","-nostats","-y")

def checksum(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f,"sha256").hexdigest()

def analyze_audio(source, dest):
    import qa
    qa.words(source, dest)

def analyze_visual(source, dest):
    import cv2
    from PIL import Image, ImageDraw
    from render import detect_mask
    duration = float(probe(source)["format"]["duration"])
    times = [round(duration*i/10,2) for i in range(10)]
    sheet = Image.new("RGB",(1600,720))
    draw = ImageDraw.Draw(sheet)
    findings = []
    for i,t in enumerate(times):
        result = run(*FF,"-ss",t,"-i",source,"-t",1,"-frames:v",1,"-vf","scale=320:180",
                     "-f","image2pipe","-c:v","png","-threads",1,"-",capture_output=True)
        from io import BytesIO
        image = Image.open(BytesIO(result.stdout)).convert("RGB")
        x,y = i%5*320,i//5*180
        sheet.paste(image,(x,y))
        draw.text((x+4,y+4),f"{t:.1f} s",fill="white",stroke_width=2,stroke_fill="black")
        import numpy as np
        frame=cv2.resize(np.array(image)[:,:,::-1],(1280,720))
        flags=detect_mask(frame)
        crop=image.crop((0,0,208,108)).resize((624,324))
        png = BytesIO()
        crop.save(png, format="PNG")
        try:
            ocr=run("tesseract","stdin","stdout","-l","eng","--psm","11",
                    input=png.getvalue(),capture_output=True,timeout=6).stdout.decode()
        except (OSError, subprocess.SubprocessError):
            ocr=""
        findings.append({"time":t,"overlays":{k:bool(v) for k,v in flags.items()},
                         "donation_candidate":bool(re.search(r"\b(?:RUB|USD|DONAT)\b|₽",ocr,re.I))})
    # Image dimensions stay constant for visual comparisons across runs.
    sheet.crop((0,0,1600,360)).save(dest.with_suffix(".jpg"))
    write(dest,findings)

def overlays(frame):
    import cv2
    import numpy as np
    from render import detect_mask
    out=detect_mask(frame)
    # Locate the red graphic across the vertical band above the webcam.
    # It moves with the stream layout. Size and chroma distinguish it from chat.
    band=frame[:390,1050:1280]
    b,g,r=band.astype(np.int16).transpose(2,0,1)
    red=((r>135)&(r-g>67)&(r-b>54)).astype(np.uint8)
    n,_,stats,_=cv2.connectedComponentsWithStats(cv2.dilate(red,np.ones((3,7),np.uint8)))
    choices=[(area,x,y,w,h) for x,y,w,h,area in stats[1:n] if 37<=w<=211 and 12<=h<=71 and area>=450 and x>=12]
    if choices:
        _,x,y,w,h=max(choices)
        right=min(1280,1050+x+w+56)
        out["right"]=tuple(map(int,(1050+max(0,x-5),max(0,y-5),right,min(390,y+h+5))))
    return out

def bright_right_banner(frame):
    """Recover the red board on red game scenery using its brighter color and shape."""
    import cv2
    import numpy as np
    x0,y0 = 1068,145
    band = frame[y0:228,x0:1278].astype(np.int16)
    b,g,r = band.transpose(2,0,1)
    saturated = ((r>187)&(r-g>131)&(r-b>105)).astype(np.uint8)
    n,_,stats,_ = cv2.connectedComponentsWithStats(
        cv2.morphologyEx(saturated,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8)))
    candidates = [(area,x,y,w,h) for x,y,w,h,area in stats[1:n]
                  if 60<=w<=153 and 32<=h<=66 and x<=33 and y<=29 and area>=.33*w*h]
    if not candidates:
        return None
    _,x,y,w,h = max(candidates)
    # The artwork attached to the board is visible as a separate colored
    # rectangle. Verify it is present before extending the mask.
    rx,ry = x+w,y+h
    icon = band[max(0,y+2):min(len(band),ry+7),rx:min(len(band[0]),rx+60)]
    if icon.shape[1] < 23:
        return None
    blue,green,red = icon.transpose(2,0,1)
    accented = ((red>167)&(red-green>75))|((green>150)&(blue>120)&(red>140))
    if accented.mean()<.075:
        return None
    return (x0+max(0,x-5),y0+max(0,y-5),min(1278,x0+rx+53),min(228,y0+ry+6))


def overlay_proxy(frame):
    """Resize the detector's pixel bands at the same phase as a full-frame resize."""
    import cv2
    import numpy as np

    if frame.shape != (1080,1920,3):
        return cv2.resize(frame,(1280,720),interpolation=cv2.INTER_AREA)

    # render.detect_mask reads [:75,:185] and the right-hand 250 columns.
    # overlays additionally reads [:390,1050:1280]. The right-hand band
    # extends through y=434 to include the lowest chat-mask reads.
    # Even proxy boundaries are multiples of three in the native image:
    # INTER_AREA then samples exactly the pixels of the full-frame resize.
    proxy=np.zeros((720,1280,3),dtype=frame.dtype)
    for x,y,right,bottom in ((0,0,186,76),(1030,0,1280,434)):
        region=frame[y*3//2:bottom*3//2,x*3//2:right*3//2]
        proxy[y:bottom,x:right]=cv2.resize(region,(right-x,bottom-y),interpolation=cv2.INTER_AREA)
    return proxy

def recover_banner(frame, previous):
    """Use the last verified banner template only on short detector gaps."""
    import cv2
    if previous is None:
        return None
    box, template, age = previous
    if age > 8:
        return None
    x,y,right,bottom = box
    x0,y0 = max(0,x-12),max(0,y-12)
    region = frame[y0:min(frame.shape[0],bottom+12),x0:min(frame.shape[1],right+12)]
    if template.std() < 8:
        return None
    _, score, _, (dx,dy) = cv2.minMaxLoc(cv2.matchTemplate(region,template,cv2.TM_CCOEFF_NORMED))
    if score < .94:
        return None
    return (x0+dx,y0+dy,x0+dx+right-x,y0+dy+bottom-y)


def rotating_right_banner(frame, previous, chat):
    """Find the rotating red graphic when it narrows below the standard detector."""
    import cv2
    import numpy as np
    if previous is None or previous[2] > 24:
        return None
    (left, top, right, bottom), _, _ = previous
    px, py = frame.shape[1]/1920, frame.shape[0]/1080
    x0, x1 = max(0,left-round(150*px)), min(frame.shape[1],right+round(97*px))
    y0, y1 = max(0,top-round(27*py)), min(frame.shape[0],bottom+round(38*py))
    for x,y,r,b in chat:
        if x < x1 and r > x0 and y > top:
            y1 = min(y1,y)
    if x1 <= x0 or y1 <= y0:
        return None
    crop = frame[y0:y1,x0:x1]
    b,g,r = crop.astype(np.int16).transpose(2,0,1)
    red = ((r>87)&(r-g>48)&(r-b>31)).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(cv2.dilate(red,np.ones((3,3),np.uint8)))
    supported = [i for i in range(1,count) if stats[i,cv2.CC_STAT_AREA] >= max(12,125*px*py)]
    if not supported:
        return None
    points = cv2.findNonZero(np.isin(labels,supported).astype(np.uint8))
    x,y,w,h = cv2.boundingRect(points)
    if w < 13*px or h < 7*py:
        return None
    margin = max(3,round(6*px))
    return (x0+max(0,x-margin),y0+max(0,y-margin),
            x0+min(x1-x0,x+w+margin),y0+min(y1-y0,y+h+margin))


VITTRACK = Path("/workspace/t2x2/models/vittrack.onnx")
VITTRACK_SHA256 = "2990f0b7cd44d92afa48cd97db6de7be113fc1d9594fddb74e2725c10478e91d"


def vittrack_fingerprint():
    return VITTRACK_SHA256 if VITTRACK.is_file() and checksum(VITTRACK) == VITTRACK_SHA256 else None


class RightBannerTracker:
    """Use a verified banner seed to distinguish banner content from chat-like colors."""
    def __init__(self):
        self.enabled = bool(vittrack_fingerprint())
        self.seed = None
        self.tracker = None
        self.last_clear = -1
        self.recoveries = 0

    def remember(self, frame, box, frame_index):
        if not self.enabled:
            return
        if self.seed is None or frame_index-self.last_clear >= 9 or self.tracker is not None:
            x,y,r,b = box
            sy = frame.shape[0]/1080
            sx = frame.shape[1]/1920
            if 145*sx <= r-x <= 365*sx and 48*sy <= b-y <= 140*sy:
                self.seed = (frame.copy(), box)
                self.last_clear = frame_index
                self.tracker = None

    def locate(self, frame, observed, frame_index):
        import cv2
        import numpy as np
        if self.seed is None or frame_index-self.last_clear > 90:
            return None
        image, anchor = self.seed
        ax,ay,ar,ab = anchor
        if self.tracker is None:
            try:
                params = cv2.TrackerVit_Params()
                params.net = str(VITTRACK)
                params.tracking_score_threshold = .72
                self.tracker = cv2.TrackerVit_create(params)
                self.tracker.init(image, (ax,ay,ar-ax,ab-ay))
            except cv2.error:
                self.enabled = False
                self.seed = None
                return None
        ok, (x,y,w,h) = self.tracker.update(frame)
        if not ok or self.tracker.getTrackingScore() < .72:
            return None
        sx,sy = frame.shape[1]/1920,frame.shape[0]/1080
        if abs(x-ax)>36*sx or abs(y-ay)>19*sy or not .75 <= w/(ar-ax) <= 1.18 or not .72 <= h/(ab-ay) <= 1.13:
            return None
        r,b = x+w,y+h
        if not 0 <= x < r <= frame.shape[1] or not 0 <= y < b <= frame.shape[0]:
            return None
        ox,oy,orr,ob = observed or (x,y,r,b)
        if observed:
            intersection = max(0,min(r,orr)-max(x,ox))*max(0,min(b,ob)-max(y,oy))
            if intersection < .61*w*h:
                return None
        crop = frame[y:b,x:r].astype(np.int16)
        blue,green,red = crop.transpose(2,0,1)
        board = (red>130)&(red-green>65)&(red-blue>45)
        if board.mean() < (.19 if observed else .27):
            return None
        # Recover the template's clipped sides while staying above the original
        # banner/chat boundary learned from an uncontested frame.
        left = max(0,min(x,ox,ax)-round(3*sx))
        top = max(0,min(y,oy,ay)-round(2*sy))
        right = min(frame.shape[1],max(r,ar)+round(2*sx))
        bottom = min(frame.shape[0],ab,max(b,min(ob,ab)))
        self.recoveries += 1
        return (left,top,right,bottom)


def native_part(src,start,end,profile,out,track=True,override=None,banners_only=False):
    import cv2
    import numpy as np
    width,height,fps=profile
    count=round((end-start)*float(Fraction(fps)))
    length=count/float(Fraction(fps))
    video_tmp=out.with_suffix(".tmp.mp4")
    encode=("-an","-c:v","libx264","-preset","ultrafast","-crf","19","-threads","2",
            "-pix_fmt","yuv420p","-colorspace","bt709","-color_trc","bt709","-color_primaries","bt709",
            "-color_range","tv","-video_track_timescale","120000")
    if not track:
        run(*FF,"-threads",2,"-ss",start,"-i",src,"-t",length,"-vf",f"scale={width}:{height},fps={fps}",
            "-frames:v",count,*encode,video_tmp,timeout=180)
        video_tmp.replace(out)
        return
    resize=f"fps={fps},scale={width}:{height}"
    decoder=[*FF,"-threads","2","-filter_threads","1","-ss",str(start),"-i",str(src),"-t",str(length),
             "-an","-vf",resize,"-frames:v",str(count),"-pix_fmt","bgr24","-c:v","rawvideo","-threads","1","-f","rawvideo","-"]
    encoder=[*FF,"-f","rawvideo","-pixel_format","bgr24","-video_size",f"{width}x{height}","-framerate",fps,
             "-i","-","-t",str(length),"-vf","scale=out_color_matrix=bt709","-frames:v",str(count),*encode,str(video_tmp)]
    log=[]
    exceptions=[]
    previous = {"left": None, "right": None}
    recovered = 0
    tracked_right = RightBannerTracker() if banners_only and not override else None
    supplied=read(override)["frames"] if override else None
    if supplied is not None and len(supplied)!=count:
        raise ValueError("Длина исправленной маски отличается от длины видеочасти.")
    cv2.setNumThreads(1)
    size=width*height*3
    scale=(width/1280,height/720)*2
    with subprocess.Popen(decoder,stdout=subprocess.PIPE) as reader, subprocess.Popen(encoder,stdin=subprocess.PIPE) as writer:
        for i in range(count):
            buf=reader.stdout.read(size)
            if len(buf)!=size:
                raise RuntimeError(f"Ожидалось {count} кадров, получено {i}.")
            frame=np.frombuffer(buf,dtype=np.uint8).reshape(height,width,3).copy()
            proxy = overlay_proxy(frame) if supplied is None else None
            active=overlays(proxy) if supplied is None else supplied[i]
            if banners_only and supplied is None and not active["right"]:
                candidate = bright_right_banner(proxy)
                if candidate:
                    active["right"] = candidate
            if supplied is None:
                active={k:([tuple(round(a*b) for a,b in zip(box,scale)) for box in v] if k=="chat" else
                         tuple(round(a*b) for a,b in zip(v,scale)) if v else None) for k,v in active.items()}
            if banners_only and supplied is None:
                for key in previous:
                    box = active[key]
                    if box:
                        x,y,right,bottom = box
                        if right-x >= 8 and bottom-y >= 8:
                            previous[key] = (box,frame[y:bottom,x:right].copy(),0)
                    elif previous[key]:
                        prior, template, age = previous[key]
                        previous[key] = (prior,template,age+1)
                        box = recover_banner(frame,previous[key])
                        if not box and key == "right":
                            box = rotating_right_banner(frame,previous[key],active["chat"])
                        if box:
                            active[key] = box
                            recovered += 1
            if banners_only:
                chat=active["chat"]
                active={**active,"chat":[]}
                for key in ("left","right"):
                    box=active[key]
                    if box and any(max(box[0],c[0])<min(box[2],c[2]) and
                                   max(box[1],c[1])<min(box[3],c[3]) for c in chat):
                        active[key] = tracked_right.locate(frame,box,i) if key=="right" and tracked_right else None
                    elif key=="right" and tracked_right:
                        if box:
                            tracked_right.remember(frame,box,i)
                        elif supplied is None:
                            active[key] = tracked_right.locate(frame,None,i)
            if not active["left"] or not active["right"]:
                if len(exceptions)<9 and (not exceptions or i-exceptions[-1][0]>=round(float(Fraction(fps)))):
                    top=cv2.resize(frame,(320,180))
                    exceptions.append((i,top))
            for x,y,r,b in (box for box in [active["left"],active["right"],*active["chat"]] if box):
                if not (0<=x<r<=width and 0<=y<b<=height):
                    raise RuntimeError("Маска выходит за пределы кадра.")
                crop=frame[y:b,x:r]
                small=cv2.resize(crop,(max(1,(r-x)//4),max(1,(b-y)//4)),interpolation=cv2.INTER_AREA)
                small=cv2.GaussianBlur(small,(0,0),4.2*width/1280,borderType=cv2.BORDER_REPLICATE)
                frame[y:b,x:r]=cv2.resize(small,(r-x,b-y))
            writer.stdin.write(frame.tobytes())
            log.append(active)
        writer.stdin.close()
        if reader.wait(timeout=10) or writer.wait(timeout=15):
            raise RuntimeError("Ошибка декодера или энкодера части.")
    if exceptions:
        images=[]
        for at,image in exceptions:
            cv2.putText(image,str(at),(4,175),cv2.FONT_HERSHEY_SIMPLEX,.5,(255,255,255),1)
            images.append(image)
        images += [np.zeros_like(images[0])]*(-len(images)%3)
        cv2.imwrite(str(out.with_suffix(".jpg")),np.vstack([np.hstack(images[j:j+3]) for j in range(0,len(images),3)]),[cv2.IMWRITE_JPEG_QUALITY,60])
    write(out.with_suffix(".masks.json"),{"width":width,"height":height,"fps":fps,"frames":log,"tracked_recoveries":recovered,
          "model_recoveries":tracked_right.recoveries if tracked_right else 0,
          "missing_left":sum(not row["left"] for row in log),"missing_right":sum(not row["right"] for row in log)})
    video_tmp.replace(out)

def make_audio(spec,dest):
    from render import SR, envelope, pcm
    import numpy as np
    chunks=[pcm(spec["intro"],0,spec["intro_seconds"])]
    chunks.extend(pcm(spec["source"],a-spec["offset"],b-a) for a,b in spec["keep"])
    audio=np.concatenate(chunks)
    envelope(audio,spec["windows"],.006)
    dur=len(audio)/SR
    wav=dest.with_suffix(".wav")
    run(*FF,"-f","f32le","-ar",SR,"-ac",2,"-i","-","-t",dur,"-c:a","pcm_f32le",wav,input=audio.tobytes(),timeout=60)
    settings="I=-14.9:TP=-2.2:LRA=20"
    scan=run("ffmpeg","-nostdin","-hide_banner","-loglevel","info","-nostats","-i",wav,"-t",dur,
             "-af",f"loudnorm={settings}:print_format=json","-f","null","-",capture_output=True,text=True,timeout=150)
    measured=json.loads(re.findall(r"\{[^{}]*\}",scan.stderr)[-1])
    flags={"I":"input_i","LRA":"input_lra","TP":"input_tp","thresh":"input_thresh"}
    args=":".join(f"measured_{arg}={measured[key]}" for arg,key in flags.items())
    tmp=dest.with_suffix(".tmp.m4a")
    if not all(float(measured[key]) > -100 for key in flags.values()):
        raise ValueError("Полностью тихой дорожке нельзя назначить целевое значение LUFS.")
    run(*FF,"-i",wav,"-t",dur,"-af",f"loudnorm={settings}:{args}:offset={measured['target_offset']}:linear=true",
        "-c:a","aac","-ar",SR,"-b:a","160k",tmp,timeout=150)
    tmp.replace(dest)

def make_pair(parts,audio,profile,dur,preview,master):
    width,height,_=profile
    small=(min(854,width)//2*2,min(480,height)//2*2)
    playlist=master.parent/"video.ffconcat"
    playlist.write_text("ffconcat version 1.0\n"+"".join("file '"+str(p).replace("'","'\\''")+"'\n" for p in parts))
    def mapping(total):
        return ["-map","0:v:0","-map","1:a:0","-t",str(total),"-movflags","+faststart","-c:a","copy"]
    if preview is None:
        tmp=master.with_suffix(".tmp.mp4")
        run(*FF,"-f","concat","-safe",0,"-i",playlist,"-i",audio,*mapping(dur),
            "-c:v","copy",tmp,timeout=max(180,dur/2))
        tmp.replace(master)
        return
    mobile=[Path(part).with_suffix(".preview.mp4") for part in parts]
    if all(p.exists() for p in mobile):
        secondary=preview.parent/"mobile.ffconcat"
        secondary.write_text("ffconcat version 1.0\n"+"".join("file '"+str(p)+"'\n" for p in mobile))
        for image,dest in ((secondary,preview),(playlist,master)):
            tmp=dest.with_suffix(".tmp.mp4")
            run(*FF,"-f","concat","-safe",0,"-i",image,"-i",audio,*mapping(dur),"-c:v","copy",tmp,
                timeout=max(180,dur/2))
            tmp.replace(dest)
        return
    # HD uses the already assembled native video. Only the mobile copy is encoded.
    small_tmp=preview.with_suffix(".tmp.mp4")
    master_tmp=master.with_suffix(".tmp.mp4")
    run(*FF,"-threads",2,"-filter_threads",1,"-f","concat","-safe",0,"-i",playlist,"-i",audio,
        *mapping(dur),"-vf",f"scale={small[0]}:{small[1]}","-c:v","libx264","-preset","ultrafast",
        "-crf",23,"-threads",2,small_tmp,*mapping(dur),"-c:v","copy",master_tmp,timeout=600)
    small_tmp.replace(preview)
    master_tmp.replace(master)

def fast_part(src,start,end,profile,out,track=True,override=None):
    """Analyze a proxy, then use one native decode to render the two versions."""
    import cv2
    import numpy as np
    w,h,fps=profile
    fl=float(Fraction(fps))
    count=round((end-start)*fl)
    length=count/fl
    tmp=out.with_suffix(".tmp.mp4")
    mobile=out.with_suffix(".preview.mp4")
    mobile_tmp=mobile.with_suffix(".tmp.mp4")
    enc=("-an","-c:v","libx264","-preset","ultrafast","-crf","21","-threads","2",
         "-pix_fmt","yuv420p","-color_range","tv","-colorspace","bt709",
         "-color_trc","bt709","-color_primaries","bt709","-video_track_timescale","120000")
    direct=(*FF,"-threads",2,"-ss",start,"-i",src)
    if not track:
        run(*direct,"-filter_complex_threads",1,"-filter_complex",
            f"[0:v]fps={fps},scale={w}:{h},split[a][b];[b]scale=854:480[small]",
            "-map","[small]","-t",length,"-frames:v",count,*enc,mobile_tmp,
            "-map","[a]","-t",length,"-frames:v",count,*enc,tmp,timeout=190)
        mobile_tmp.replace(mobile)
        tmp.replace(out)
        return
    if override:
        raise ValueError("Редактируемая нативная маска требует обычного воркера.")
    cv2.setNumThreads(1)
    mk=out.with_suffix(".overlay.mkv")
    mktemp=mk.with_suffix(".tmp.mkv")
    read_command=[str(a) for a in [*direct,"-filter_threads",1,"-t",length,"-an",
       "-vf",f"fps={fps},scale=640:360:flags=fast_bilinear","-frames:v",count,
       "-pix_fmt","bgr24","-c:v","rawvideo","-threads",1,"-f","rawvideo","-"]]
    write_command=[str(a) for a in [*FF,"-f","rawvideo","-pixel_format","gray","-video_size","640x360",
       "-framerate",fps,"-i","-","-t",length,"-frames:v",count,"-an","-c:v","ffv1","-threads",1,mktemp]]
    rows=[]
    shots=[]
    with subprocess.Popen(read_command,stdout=subprocess.PIPE) as decoder,subprocess.Popen(write_command,stdin=subprocess.PIPE) as writer:
        for i in range(count):
            raw=decoder.stdout.read(640*360*3)
            if len(raw)!=640*360*3:
                raise RuntimeError(f"Маски: {i} из {count} кадров.")
            image=np.frombuffer(raw,np.uint8).reshape(360,640,3)
            # The legacy detector only inspects the banner and chat regions.
            frame=np.zeros((720,1280,3),np.uint8)
            frame[:80,:192]=cv2.resize(image[:40,:96],(192,80))
            frame[:448,1024:]=cv2.resize(image[:224,512:],(256,448))
            row=overlays(frame)
            if not row["left"] or not row["right"]:
                if len(shots)<12 and (not shots or i-shots[-1][0]>=int(fl)):
                    shots.append((i,cv2.resize(image[:224,512:],(128,112))))
            alpha=np.zeros((360,640),np.uint8)
            for box in [row["left"],row["right"],*row["chat"]]:
                if box:
                    x,y,r,b=box
                    alpha[y//2:(b+1)//2,x//2:(r+1)//2]=255
            writer.stdin.write(alpha.tobytes())
            rows.append(row)
        writer.stdin.close()
        if decoder.wait(timeout=12) or writer.wait(timeout=15):
            raise RuntimeError("Не удалось записать маску.")
    mktemp.replace(mk)
    if shots:
        tiles=[]
        for i,im in shots:
            cv2.putText(im,str(i),(2,106),cv2.FONT_HERSHEY_SIMPLEX,.4,(255,255,255),1)
            tiles.append(im)
        tiles+= [np.zeros_like(tiles[0])]*(-len(tiles)%4)
        cv2.imwrite(str(out.with_suffix(".jpg")),np.vstack([np.hstack(tiles[j:j+4]) for j in range(0,len(tiles),4)]))
    sx,sy=w/640,h/360
    def region(tag,m,v,x,y,ww,hh):
        box=tuple(round(v*p)//2*2 for v,p in zip((ww,hh,x,y),(sx,sy,sx,sy)))
        bw,bh,bx,by=box
        graph=(f"[{v}]crop={bw}:{bh}:{bx}:{by},scale={max(2,bw//4)}:{max(2,bh//4)},"
               f"gblur=sigma={4.2*w/1280},scale={bw}:{bh}[{tag}blur];"
               f"[{m}]crop={ww}:{hh}:{x}:{y},scale={bw}:{bh}:flags=neighbor,format=gray[{tag}mask];"
               f"[{tag}blur][{tag}mask]alphamerge[{tag}]")
        return graph,bx,by
    left,lx,ly=region("left","ml","vl",0,0,96,40)
    right,rx,ry=region("right","mr","vr",512,0,128,224)
    graph=(f"[0:v]fps={fps},scale={w}:{h},split=3[base][vl][vr];[1:v]split=2[ml][mr];"
           +left+";"+right+f";[base][left]overlay={lx}:{ly}[one];[one][right]overlay={rx}:{ry},"
           "split[hd][low];[low]scale=854:480[small]")
    run(*direct,"-i",mk,"-filter_complex_threads",2,"-filter_complex",graph,
        "-map","[small]","-t",length,"-frames:v",count,*enc,mobile_tmp,
        "-map","[hd]","-t",length,"-frames:v",count,*enc,tmp,timeout=500)
    write(out.with_suffix(".masks.json"),{"width":1280,"height":720,"fps":fps,"frames":rows,
           "coordinates":"1280x720 normalized", "missing_left":sum(not z["left"] for z in rows),
           "missing_right":sum(not z["right"] for z in rows)})
    mobile_tmp.replace(mobile)
    tmp.replace(out)
