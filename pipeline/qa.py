"""Local technical and speech review. Platform restrictions are reviewed in Studio."""
import json
from pathlib import Path
import re

from clip_core import loudness, probe, run

CURSE = re.compile(
    r"бл[яе](?:д[ья]|т[ья])?|п[иеё]зд|[хx]у[йеяёюи]|"
    r"[её]бан|[её]бат|[её]баш|долбо[её]б|по[её]б|раз[ъеё]+б|[её]буч",re.I)

def words(path, target):
    import numpy as np
    from faster_whisper import WhisperModel

    target = Path(target)
    temp = target.with_suffix(".tmp.jsonl")
    result = run("ffmpeg","-v","error","-i",path,"-vn","-ac","1","-ar","16000",
                 "-f","f32le","-",capture_output=True)
    signal = np.frombuffer(result.stdout,dtype="<f4")
    model = WhisperModel("small",device="cpu",compute_type="int8",cpu_threads=2)
    segments,_ = model.transcribe(signal,language="ru",word_timestamps=True,vad_filter=True,
                                  condition_on_previous_text=False,beam_size=3)
    with temp.open("w") as out:
        for s in segments:
            row = {"start":s.start,"end":s.end,"text":s.text,"words":[w._asdict() for w in s.words]}
            print(json.dumps(row,ensure_ascii=False),file=out)
    temp.replace(target)

def inspect(path, transcript=None):
    info = probe(path)
    v = next(stream for stream in info["streams"] if stream["codec_type"] == "video")
    a = next(stream for stream in info["streams"] if stream["codec_type"] == "audio")
    candidates = None
    if transcript and Path(transcript).exists():
        candidates = []
        for line in Path(transcript).read_text().splitlines():
            row = json.loads(line)
            for word in row["words"]:
                if CURSE.search(word["word"]):
                    candidates.append(word)
    return {"duration":float(info["format"]["duration"]),
            "video":{key:v.get(key) for key in ("codec_name","profile","level","width","height","avg_frame_rate",
                     "nb_frames","bit_rate","pix_fmt","color_space","color_range","color_primaries","color_transfer")},
            "audio":{**loudness(path),"sample_rate":a["sample_rate"],"bit_rate":a.get("bit_rate")},
            "asr_candidates":candidates,
            "platform_checks":"Content ID и возрастные ограничения проверяются в YouTube Studio владельцем."}
