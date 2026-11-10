import json, sys
from faster_whisper import WhisperModel
import os
OFF = float(os.environ.get("OFF", "0"))  # start of win.wav in the source video, seconds
m = WhisperModel("small", device="cpu", compute_type="int8", cpu_threads=4)
import wave, numpy as np
wv = wave.open("win.wav"); audio = np.frombuffer(wv.readframes(wv.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
segs, info = m.transcribe(audio, language="ru", word_timestamps=True, vad_filter=True, beam_size=1)
words, lines = [], []
for s in segs:
    lines.append({"start": round(s.start + OFF, 2), "end": round(s.end + OFF, 2), "text": s.text.strip()})
    for w in s.words or []:
        words.append({"start": round(w.start + OFF, 2), "end": round(w.end + OFF, 2), "word": w.word})
json.dump(words, open("words.json", "w"), ensure_ascii=False)
json.dump(lines, open("lines.json", "w"), ensure_ascii=False, indent=0)
print("segments", len(lines), "words", len(words))
