"""usage: python3 stt.py in.wav out.txt  (16k mono wav)"""
import sys, wave, numpy as np
from faster_whisper import WhisperModel
w = wave.open(sys.argv[1]); a = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
m = WhisperModel(__import__('os').environ.get('WM','small'), device='cpu', compute_type='int8', cpu_threads=4)
segs, _ = m.transcribe(a, language='ru', vad_filter=True)
with open(sys.argv[2], 'w') as f:
    for s in segs:
        line = f"{s.start:7.1f}-{s.end:7.1f} {s.text.strip()}"; f.write(line + '\n'); print(line, flush=True)
