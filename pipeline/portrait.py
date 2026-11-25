"""Extract a foreground portrait for the historical intro generator."""
import argparse
from pathlib import Path
import os

from PIL import Image
from clip_core import ROOT

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source",type=Path)
    parser.add_argument("output",type=Path)
    parser.add_argument("--crop",nargs=4,type=int,metavar=("LEFT","TOP","RIGHT","BOTTOM"))
    args = parser.parse_args()
    os.environ.setdefault("U2NET_HOME",str(ROOT/"models"))
    os.environ.setdefault("OMP_NUM_THREADS","1")
    from rembg import new_session, remove

    with Image.open(args.source) as original:
        frame = original.convert("RGB")
    if args.crop:
        frame = frame.crop(args.crop)
    cutout = remove(frame,session=new_session("u2netp")).convert("RGBA")
    box = cutout.getchannel("A").point(lambda value: 255 if value > 20 else 0).getbbox()
    if box is None:
        parser.error("В кадре не найден передний план.")
    cutout.crop(box).save(args.output)

if __name__ == "__main__":
    main()
