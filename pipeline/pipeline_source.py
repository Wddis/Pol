"""Download only selected time intervals from one Twitch media playlist."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import re
from urllib.parse import urljoin
from urllib.request import urlopen

from clip_core import read, run, write
from pipeline_media import FF

def fetch(url, attempts=3):
    for attempt in range(attempts):
        try:
            with urlopen(url,timeout=24) as req:
                return req.read()
        except OSError:
            if attempt+1==attempts:
                raise

def index(url):
    manifest=fetch(url).decode()
    if not manifest.startswith("#EXTM3U"):
        raise ValueError("Ожидался медиа-плейлист HLS.")
    elapsed=0.
    duration=None
    init=None
    rows=[]
    for line in manifest.splitlines():
        if line.startswith("#EXT-X-MAP"):
            init=urljoin(url,re.search(r'URI="([^"]+)"',line).group(1))
        elif line.startswith("#EXTINF:"):
            duration=float(line.split(":",1)[1].split(",")[0])
        elif line and not line.startswith("#") and duration is not None:
            rows.append((elapsed,duration,urljoin(url,line),init))
            elapsed+=duration
            duration=None
    return rows

def download(info,fmt,lo,hi,out):
    formats=read(info)["formats"]
    url=next(f["url"] for f in formats if f["format_id"]==fmt)
    chosen=[(i,*item) for i,item in enumerate(index(url)) if item[0]<hi and item[0]+item[1]>lo]
    if not chosen or chosen[-1][1]+chosen[-1][2]<hi-.05:
        raise ValueError("Архив ещё не содержит весь запрошенный интервал.")
    out.parent.mkdir(parents=True,exist_ok=True)
    cache=out.with_suffix(".segments")
    cache.mkdir(exist_ok=True)
    def part(row):
        i,at,length,uri,init=row
        dest=cache/f"{i:06d}.mp4"
        if not dest.exists():
            buf=fetch(uri)
            tmp=dest.with_suffix(".tmp")
            tmp.write_bytes(buf)
            tmp.replace(dest)
        return dest
    raw=out.with_suffix(".raw.mp4")
    with ThreadPoolExecutor(12) as pool:
        paths=list(pool.map(part,chosen))
    with raw.open("wb") as f:
        import shutil
        previous=None
        for row,path in zip(chosen,paths,strict=True):
            init=row[-1]
            if init and init!=previous:
                f.write(fetch(init))
            previous=init
            with path.open("rb") as chunk:
                shutil.copyfileobj(chunk,f)
    source_start=chosen[0][1]
    span=chosen[-1][1]+chosen[-1][2]-source_start
    tmp=out.with_suffix(".tmp.mkv")
    maps=("-map","0:a:0") if fmt.lower()=="audio_only" else ("-map","0:v:0","-map","0:a:0")
    run(*FF,"-i",raw,"-t",span,*maps,"-c","copy",tmp,timeout=max(120,span/3))
    tmp.replace(out)
    raw.unlink()
    result={"source":str(out.resolve()),"offset":source_start,"duration":span,"segments":len(chosen),"format":fmt}
    write(out.with_suffix(".manifest.json"),result)
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return result

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("info",type=Path)
    p.add_argument("format")
    p.add_argument("start",type=float)
    p.add_argument("end",type=float)
    p.add_argument("output",type=Path)
    a=p.parse_args()
    download(a.info,a.format,a.start,a.end,a.output)
