#!/usr/bin/env python3
"""Пословные тайминги (ElevenLabs Scribe words.json) -> субтитры .srt + список фраз с таймкодами.

Используется: 1) для расшифровки уроков, 2) для пословных субтитров в эдитах (диалоговая часть).

    python3 words_to_srt.py words.json out.srt [--speed 1.2] [--cut START:DUR ...] [--fix fixes.json]
                            [--max-chars 42] [--max-dur 4.0]

--speed : аудио было ускорено (atempo) перед распознаванием -> время умножается обратно
--cut   : вырезанные паузы (silenceremove), START в секундах ОРИГИНАЛА; время после них сдвигается на DUR
--fix   : {"ошибка распознавания": "правильно", ...} — замены по словам/фразам (регистр сохраняется как в fixes)
"""
import argparse
import json
import re


def to_orig(t, speed, cuts):
    t = t * speed
    shift = 0.0
    for start, dur in sorted(cuts):
        if t + shift >= start:
            shift += dur
    return t + shift


def ts(t):
    ms = int(round(t * 1000))
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def apply_fixes(text, fixes):
    for a, b in fixes.items():
        text = re.sub(r"(?<![\w-])" + re.escape(a) + r"(?![\w-])", b, text)
    return text


def build(words, speed=1.0, cuts=(), max_chars=42, max_dur=4.0):
    ws = [dict(w, start=to_orig(w["start"], speed, cuts), end=to_orig(w["end"], speed, cuts))
          for w in words if w.get("type", "word") == "word"]
    subs, cur = [], []
    for w in ws:
        if cur:
            text = " ".join(x["text"] for x in cur + [w])
            if (len(text) > max_chars or w["end"] - cur[0]["start"] > max_dur
                    or w["start"] - cur[-1]["end"] > 0.8):
                subs.append(cur)
                cur = []
        cur.append(w)
        if re.search(r"[.!?…]$", w["text"]) and len(" ".join(x["text"] for x in cur)) > 12:
            subs.append(cur)
            cur = []
    if cur:
        subs.append(cur)
    return [(s[0]["start"], s[-1]["end"], " ".join(x["text"] for x in s)) for s in subs]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("words")
    ap.add_argument("out")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--cut", action="append", default=[])
    ap.add_argument("--fix")
    ap.add_argument("--max-chars", type=int, default=42)
    ap.add_argument("--max-dur", type=float, default=4.0)
    a = ap.parse_args()
    d = json.load(open(a.words))
    words = d["words"] if isinstance(d, dict) else d
    cuts = [tuple(map(float, c.split(":"))) for c in a.cut]
    fixes = json.load(open(a.fix)) if a.fix else {}
    subs = build(words, a.speed, cuts, a.max_chars, a.max_dur)
    with open(a.out, "w") as f:
        for i, (s, e, t) in enumerate(subs, 1):
            f.write(f"{i}\n{ts(s)} --> {ts(max(e, s + 0.3))}\n{apply_fixes(t, fixes)}\n\n")
    print(a.out, len(subs), "субтитров")


if __name__ == "__main__":
    main()
