"""Write audio in bounded blocks and normalize one common AAC track."""
import json
import re
import subprocess

from clip_core import run
from pipeline_media import FF

def prepare(spec,dest):
    from render import SR, envelope, pcm
    plan=[(spec["intro"],0,spec["intro_seconds"])]
    plan.extend((spec["source"],a-spec["offset"],b-spec["offset"]) for a,b in spec["keep"])
    duration=sum(b-a for _,a,b in plan)
    wav=dest.with_suffix(".wav")
    window=spec["windows"]
    position=0.0
    args=[str(v) for v in [*FF,"-f","f32le","-ar",SR,"-ac",2,"-i","-","-t",duration,"-c:a","pcm_f32le",wav]]
    with subprocess.Popen(args,stdin=subprocess.PIPE) as p:
        for src,start,end in plan:
            t=start
            while t<end-1e-6:
                size=min(90,end-t)
                block=pcm(src,t,size).copy()
                edits=[dict(w,start=w["start"]-position,end=w["end"]-position) for w in window
                       if w["end"]+.006>position and w["start"]-.006<position+size]
                envelope(block,edits,.006)
                p.stdin.write(block.tobytes())
                position+=size
                t+=size
        p.stdin.close()
        if p.wait(timeout=40):
            raise RuntimeError("PCM не собран.")
    settings="I=-14.9:TP=-2.2:LRA=20"
    scan=run("ffmpeg","-nostdin","-hide_banner","-loglevel","info","-nostats","-i",wav,"-t",duration,
             "-af",f"loudnorm={settings}:print_format=json","-f","null","-",capture_output=True,text=True,
             timeout=max(160,duration/3))
    measured=json.loads(re.findall(r"\{[^{}]*\}",scan.stderr)[-1])
    flags={"I":"input_i","LRA":"input_lra","TP":"input_tp","thresh":"input_thresh"}
    if not all(float(measured[key]) > -100 for key in flags.values()):
        raise ValueError("Невозможно назначить LUFS полностью тихой дорожке.")
    args=":".join(f"measured_{arg}={measured[key]}" for arg,key in flags.items())
    tmp=dest.with_suffix(".tmp.m4a")
    run(*FF,"-i",wav,"-t",duration,"-af",f"loudnorm={settings}:{args}:offset={measured['target_offset']}:linear=true",
        "-c:a","aac","-ar",SR,"-b:a","160k",tmp,timeout=max(160,duration/3))
    tmp.replace(dest)
