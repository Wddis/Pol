#!/usr/bin/env python3
"""Покадровый рендер эдита EffectCraft в N браузеров с докачкой упавших кусков.

    python3 render_frames.py steps.json frames_dir total_frames fps file1 [file2 ...] [--workers 2]

Делит недостающие кадры на непрерывные куски по числу потоков, запускает driver.js и повторяет,
пока все кадры 00000..total-1 не появятся (до 4 попыток). Больше 2 потоков не ставить (ROADMAP, грабли 2).
"""
import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))


def missing(d, total):
    return [n for n in range(total) if not os.path.exists(os.path.join(d, f"{n:05d}.png"))]


def ranges(ns):
    out = []
    for n in ns:
        if out and out[-1][1] == n:
            out[-1][1] = n + 1
        else:
            out.append([n, n + 1])
    return out


def split(rs, k):
    total = sum(b - a for a, b in rs)
    size = max(1, -(-total // k))
    out = []
    for a, b in rs:
        while a < b:
            out.append([a, min(b, a + size)])
            a += size
    return out


def run(job, steps, d, fps, files, env):
    a, b = job
    cfg = os.path.join(d, f"cfg_{a}_{b}.json")
    json.dump({"files": files, "steps": steps, "outDir": os.path.join(d, "_out"),
               "frames": {"from": a, "to": b, "fps": fps}, "framesDir": d}, open(cfg, "w"))
    r = subprocess.run(["node", os.path.join(HERE, "driver.js"), cfg], capture_output=True, text=True, env=env)
    return a, b, (r.stdout + r.stderr)[-500:]


def main(argv):
    workers = 2
    if "--workers" in argv:
        i = argv.index("--workers")
        workers = int(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    steps, d, total, fps, *files = argv
    steps, d, files = os.path.abspath(steps), os.path.abspath(d), [os.path.abspath(f) for f in files]
    total, fps = int(total), int(fps)
    os.makedirs(d, exist_ok=True)
    env = dict(os.environ)
    env.setdefault("NODE_PATH", subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
    for attempt in range(4):
        miss = missing(d, total)
        if not miss:
            break
        jobs = split(ranges(miss), workers)
        print(f"попытка {attempt + 1}: {len(miss)} кадров, куски {jobs}", flush=True)
        with ThreadPoolExecutor(workers) as ex:
            for a, b, log in ex.map(lambda j: run(j, steps, d, fps, files, env), jobs):
                print(a, b, log.replace("\n", " ")[-300:], flush=True)
    miss = missing(d, total)
    print("готово" if not miss else f"не отрендерены: {ranges(miss)}")
    return not miss


if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1:]) else 1)
