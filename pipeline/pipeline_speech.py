"""Bounded PCM chunks and fixed-token alignment for censorship."""
import json
import math
from pathlib import Path
import re

from clip_core import probe,run,write
from pipeline_media import FF

def signal(path,start,length):
    import numpy as np
    return np.frombuffer(run(*FF,"-ss",start,"-i",path,"-t",length,"-map","0:a:0",
                            "-ac",1,"-ar",16000,"-f","f32le","-",capture_output=True,timeout=90).stdout,dtype="<f4")

def model(name="small",threads=2):
    from faster_whisper import WhisperModel
    return WhisperModel(name,device="cpu",compute_type="int8",cpu_threads=threads)

def chunks(source,dest,spans=None,prior=None,quality="base"):
    from faster_whisper import BatchedInferencePipeline
    minute=120
    duration=float(probe(source)["format"]["duration"])
    trans=BatchedInferencePipeline(model=model(quality,1))
    d=Path(dest)
    partial=d.with_suffix(".partial.jsonl")
    ranges=spans or [(0,duration)]
    blocks=[]
    for start,last in ranges:
        core=start
        while core<last-.05:
            end=min(core+minute,last)
            blocks.append((core,end))
            core=end
    with partial.open("w") as out:
        if prior:
            out.write(Path(prior).read_text())
            out.flush()
        for core,stop in blocks:
            left=max(0,core-2)
            end=min(duration,stop+2)
            a=signal(source,left,end-left)
            segments,_=trans.transcribe(a,language="ru",word_timestamps=True,vad_filter=True,batch_size=8,
                                          beam_size=1,condition_on_previous_text=False)
            for s in segments:
                words=[]
                for w in s.words:
                    first,last=w.start+left,w.end+left
                    if core<=.5*(first+last)<stop:
                        words.append(dict(w._asdict(),start=first,end=last))
                if words:
                    row={"start":words[0]["start"],"end":words[-1]["end"],
                         "text":"".join(w["word"] for w in words),"words":words}
                    print(json.dumps(row,ensure_ascii=False),file=out)
            out.flush()
            print(f"PCM {core:.0f}–{stop:.1f} s",flush=True)
    partial.replace(d)

def force_align(source,segments,keep,offset,dest):
    from qa import CURSE
    from faster_whisper.tokenizer import Tokenizer
    import numpy as np
    candidates=[s for s in segments if any(CURSE.search(w["word"]) and any(a<=w["start"]<b for a,b in keep)
                                     for w in s["words"])]
    if not candidates:
        write(dest,[])
        return
    m=model()
    tok=Tokenizer(m.hf_tokenizer,m.model.is_multilingual,task="transcribe",language="ru")
    result=[]
    length=float(probe(source)["format"]["duration"])
    pcm=signal(source,0,length)
    def key(s):
        return re.sub(r"[^а-яёa-z]","",s.lower())
    for row in candidates:
        base=max(0,row["start"]-offset-.4)
        end=min(base+29.8,max(base+.25,row["end"]-offset+.4))
        a=pcm[round(base*16000):round(end*16000)]
        if not len(a):
            continue
        tokens=tok.encode(row["text"])
        if not tokens:
            continue
        feat=m.feature_extractor(a)
        frames=min(3000,math.ceil(len(a)/m.feature_extractor.hop_length))
        ap=m.find_alignment(tok,[tokens],m.encode(feat[...,:3000]),frames)
        fixed=ap[0] if ap else []
        remaining=list(fixed)
        for w in row["words"]:
            if not CURSE.search(w["word"]) or not any(x<=w["start"]<y for x,y in keep):
                continue
            possible=[v for v in remaining if key(v["word"])==key(w["word"])]
            closest=min(possible,key=lambda v:abs(base+offset+v["start"]-w["start"])) if possible else None
            if closest is not None and abs(base+offset+closest["start"]-w["start"])<.55:
                remaining.remove(closest)
                x,y=base+offset+closest["start"],base+offset+closest["end"]
                valid=.05<=y-x<=1.5
            else:
                valid=False
            if not valid:
                x,y=w["start"],max(w["start"]+.07,w["end"])
            result.append({"start":round((x+y)/2,4),"end":round(y+.015,4),
                           "word":w["word"],"word_start":x,"word_end":y,
                           "alignment":"fixed_tokens" if valid else "first_pass"})
    write(dest,result)
