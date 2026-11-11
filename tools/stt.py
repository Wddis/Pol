"""Speech-to-text for stream audio (faster-whisper, CPU, int8, batched).
usage: python3 stt.py in.wav out.txt [start_sec end_sec]   (16k mono wav)
env: WM=base|small (default base), BS=batch size (default 16), TH=cpu threads (default 4)

Batched mode, measured 11.11.2026 on 4 cores, 600 s of stream audio, model base:
  sequential (old)                 15.6x realtime, 242 segments
  BatchedInferencePipeline bs=16   27.0x realtime, 179 segments (~5 s), ~same text
  (small, batched: 10x realtime — use only for short disputed spots)
without_timestamps=False is REQUIRED: by default batched mode returns one segment per
~30 s VAD chunk, too coarse to find event boundaries.
Lines are flushed as they come, so the file can be read while it is being written."""
import sys, os, wave, numpy as np
from faster_whisper import WhisperModel, BatchedInferencePipeline
wav, out = sys.argv[1], sys.argv[2]
w = wave.open(wav); sr = w.getframerate()
a = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
b = float(sys.argv[4]) if len(sys.argv) > 4 else w.getnframes() / sr
w.setpos(int(a * sr)); x = np.frombuffer(w.readframes(int((b - a) * sr)), np.int16).astype(np.float32) / 32768
m = WhisperModel(os.environ.get('WM', 'base'), device='cpu', compute_type='int8', cpu_threads=int(os.environ.get('TH', '4')))
segs, _ = BatchedInferencePipeline(model=m).transcribe(x, language='ru', batch_size=int(os.environ.get('BS', '16')),
                                                       beam_size=1, without_timestamps=False)
with open(out, 'w') as f:
    for s in segs:
        line = f"{a + s.start:7.1f}-{a + s.end:7.1f} {s.text.strip()}"; f.write(line + '\n'); f.flush(); print(line, flush=True)
