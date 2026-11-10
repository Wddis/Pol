"""Render covers for every video in posts.json -> covers/<message id>.jpg.

Usage:  python3 make_covers.py            # all posts
        python3 make_covers.py 59 60      # only these message ids
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from cover import make  # noqa: E402

only = {int(x) for x in sys.argv[1:]}
os.makedirs(os.path.join(HERE, "covers"), exist_ok=True)
for p in json.load(open(os.path.join(HERE, "posts.json"), encoding="utf-8")):
    n = len(p["ids"])
    for i, mid in enumerate(p["ids"]):
        if only and mid not in only:
            continue
        d = dict(p)
        if d.get("event"):
            d["event"] = d["event"].upper()
        if n > 1:
            d["part"] = f"Часть {i + 1} из {n}"
        out = os.path.join(HERE, "covers", f"{mid}.jpg")
        make(d, out)
        print("rendered", out)
