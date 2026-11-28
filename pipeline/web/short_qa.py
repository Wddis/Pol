"""Verify the vertical export against the approved master and its final audio."""
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from clip_core import run
from pipeline import FF, LIMIT, qa_file, read, review_sample, write

path, work, start, end, report = sys.argv[1:]
path, work = Path(path), Path(work)
start, end = float(start), float(end)
duration = end - start
master = read(work / "run.json")["deliverables"]["master"]
source_qa = read(work / "qa.json")
short = qa_file(path)
speech_path = path.with_suffix(".words.jsonl")
review_sample({"master": str(path), "duration": duration, "windows": []}, speech_path)
hits = read(speech_path.with_suffix(".hits.json"))

# Match the actual frames after applying the same geometry as the export.
stats = path.with_suffix(".ssim.log")
run(*FF, "-ss", start, "-i", master, "-i", path, "-t", duration,
    "-filter_complex", "[0:v]scale=1080:-2,pad=1080:1920:0:(oh-ih)/2,setsar=1,"
    "setpts=PTS-STARTPTS[ref];[1:v]setsar=1,setpts=PTS-STARTPTS[out];"
    f"[out][ref]ssim=stats_file={stats.name}:shortest=1[v]",
    "-map", "[v]", "-an", "-f", "null", "-", timeout=160, cwd=path.parent)
scores = [float(score) for score in re.findall(r"\bAll:([0-9.]+)", stats.read_text())]
failures = []
if len(scores) != short["frames"] or min(scores, default=0) < .94 or source_qa["failures"]:
    failures.append("mask_coverage_review")
if hits:
    failures.append("speech")
if short["audio"]["true_peak_dbtp"] > LIMIT:
    failures.append("true_peak")
if abs(short["duration"]-duration) > .1 or short["gaps"]:
    failures.append("frame_timeline")
windows = read(work/"edl.json")["censorship"]
from pipeline import source_windows
output_windows = source_windows(read(work/"edl.json"), windows)
metadata = read(work/"metadata.json")
write(report, {"status": "held" if failures else "ready", "failures": failures,
              "duration": short["duration"], "quality": "1080 × 1920",
              "frames_checked": len(scores), "speech_hits": len(hits),
              "censor_windows": sum(w["start"] < end and w["end"] > start for w in output_windows),
              "title": metadata["title"] + " · Shorts",
              "description": metadata["title"] + " — фрагмент основного видео.",
              "chapters": [[0, "Начало фрагмента"]]})
