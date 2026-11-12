"""Greeting clip for the intro: the phrase cut in a real pause (never inside a word) + a soft echo tail that fades.
usage: python3 make_voice.py audio_source start end out.wav [tail_seconds=1.35]
Pick start/end by ENERGY, not by whisper word times: whisper puts word ends too early (e.g. «ребята» ends at 256.24
by whisper, but the final «а» sounds until ~256.47 after the «т» stop) — cutting there gives «ребят».
Check: transcribe the result, the last word must be complete.
Current intro: stream 10.11 (VOD 2924895000) 254.55–256.50 «Ой, ну что, здравствуйте, ребята!»"""
import sys, subprocess, wave, numpy as np
src, a, b, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", a, "-to", b, "-i", src, "-af",
                "highpass=f=80,loudnorm=I=-15:TP=-2:LRA=9,aresample=48000", "-ac", "1", "-ar", "48000", "/tmp/_dry.wav"], check=True)
w = wave.open("/tmp/_dry.wav"); sr = w.getframerate(); d = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
n = len(d); fi, fo = int(0.02 * sr), int(0.012 * sr)            # tiny edge fades only — the cut is in a pause
d[:fi] *= np.linspace(0, 1, fi); d[-fo:] *= np.linspace(1, 0, fo)
def lp(x, fc):
    k = 1 - np.exp(-2 * np.pi * fc / sr); y = np.empty_like(x); s = 0.0
    for i in range(len(x)): s += k * (x[i] - s); y[i] = s
    return y
wet = lp(d, 3200); wet = wet - lp(wet, 220)                    # warm, soft band for the repeats
TAIL = float(sys.argv[5]) if len(sys.argv) > 5 else 1.35; o = np.zeros(n + int(TAIL * sr), np.float32); o[:n] += d
for k, (dt, g) in enumerate([(0.15, .36), (0.30, .26), (0.45, .19), (0.60, .135), (0.75, .095), (0.90, .065), (1.05, .045)]):
    if dt > TAIL - 0.2: break
    s_ = lp(wet, 3200 - k * 350) if k else wet; i = int(dt * sr); o[i:i + n] += g * s_   # each repeat darker/quieter
t = np.arange(len(o)) / sr; t0 = n / sr + 0.15                  # fade ONLY the echo tail, after the voice ends
o *= np.where(t < t0, 1.0, np.cos(np.clip((t - t0) / (t[-1] - t0), 0, 1) * np.pi / 2) ** 2)
o /= np.abs(o).max() / 0.89
with wave.open(out, "wb") as f:
    f.setnchannels(2); f.setsampwidth(2); f.setframerate(sr); f.writeframes((np.stack([o, o], 1) * 32767).astype(np.int16).tobytes())
print("voice", round(n / sr, 2), "s + echo tail ->", round(len(o) / sr, 2), "s:", out)
