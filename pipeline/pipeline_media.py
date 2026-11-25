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

def native_part(src,start,end,profile,out,track=True,override=None):
    import cv2
    import numpy as np
    from render import detect_mask
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
            active=overlays(cv2.resize(frame,(1280,720),interpolation=cv2.INTER_AREA)) if supplied is None else supplied[i]
            if supplied is None:
                active={k:([tuple(round(a*b) for a,b in zip(box,scale)) for box in v] if k=="chat" else
                         tuple(round(a*b) for a,b in zip(v,scale)) if v else None) for k,v in active.items()}
            if not active["left"] or not active["right"]:
                if len(exceptions)<24 and (not exceptions or i-exceptions[-1][0]>=round(float(Fraction(fps)))):
                    top=cv2.resize(frame[:max(1,round(height*.115)),:],(480,56))
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
            cv2.putText(image,str(at),(4,51),cv2.FONT_HERSHEY_SIMPLEX,.5,(255,255,255),1)
            images.append(image)
        images += [np.zeros_like(images[0])]*(-len(images)%3)
        cv2.imwrite(str(out.with_suffix(".jpg")),np.vstack([np.hstack(images[j:j+3]) for j in range(0,len(images),3)]))
    write(out.with_suffix(".masks.json"),{"width":width,"height":height,"fps":fps,"frames":log,
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
    playlist=preview.parent/"video.ffconcat"
    playlist.write_text("ffconcat version 1.0\n"+"".join("file '"+str(p).replace("'","'\\''")+"'\n" for p in parts))
    def mapping(total):
        return ["-map","0:v:0","-map","1:a:0","-t",str(total),"-movflags","+faststart","-c:a","copy"]
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
