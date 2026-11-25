from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT / "evidence"
LEGACY = ROOT / "legacy" if (ROOT / "legacy/tools").is_dir() else ROOT.parent
OUTPUT = ROOT / "output"

def run(*args, **kwargs):
    kwargs.setdefault("timeout", 180)
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)

def read(path):
    return json.loads(Path(path).read_text())

def write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    tmp.replace(path)

def timeline(keep, intro, source):
    cursor = intro
    for start, end in keep:
        if start <= source < end:
            return round(cursor + source - start, 6)
        cursor += end - start
    return None

def seconds(text):
    items = [float(part) for part in str(text).replace(",", ".").split(":")]
    if len(items) > 3 or any(part < 0 for part in items):
        raise ValueError("Таймкод должен быть в секундах или ЧЧ:ММ:СС.")
    return sum(part * 60**power for power, part in enumerate(reversed(items)))

def probe(path):
    result = run("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", path,
                 capture_output=True, text=True)
    return json.loads(result.stdout)

def loudness(path):
    result = run("ffmpeg", "-hide_banner", "-nostats", "-i", path, "-map", "0:a:0",
                 "-af", "ebur128=peak=true", "-f", "null", "-", capture_output=True, text=True)
    summary = result.stderr.rsplit("Summary:", 1)[1]
    return {key: float(re.search(pattern, summary).group(1)) for key, pattern in
            {"lufs": r"I:\s+(-?[\d.]+) LUFS", "lra": r"LRA:\s+(-?[\d.]+) LU",
             "true_peak_dbtp": r"Peak:\s+(-?[\d.]+) dBFS"}.items()}
