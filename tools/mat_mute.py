"""TEST TOOL (owner, 16.11.2026): «затухание» мата так, как это делает другой канал нарезок — посередине мата звук
на долю секунды пропадает, первый звук и окончание слышны («б… ть»). Видео не трогаем (копия), меняется только звук.

Как устроено у того нарезчика (замер 16.11 по https://youtu.be/liogJXBfE2s, 21 мат): внутри слова — ЖЁСТКИЙ мьют
(абсолютная цифровая тишина) 0,10–0,37 с, медиана ~0,22 с, начинается через ~0,03–0,2 с после начала слова, края
резкие (≤ 10 мс). Глушат не всё: «сука» и часть «блять» оставлены.

usage: python3 mat_mute.py IN.mp4 OUT.mp4 [--words words.json] [--model small] [--dry] [--report report.txt]
  1) расшифровка звука ПО СЛОВАМ (faster-whisper batched, word_timestamps; ~1 мин на 5 мин звука на 4 ядрах),
     слова кешируются в IN.words.json (повторный запуск — секунды);
  2) мат по корням (бля/хуй/пизд/еб-/ёб-, мудак, пидор, гандон; слова Whisper вида «б***ть» тоже); «сука» —
     только с --suka;
  3) мьют = СЕРЕДИНА слова, длина = 60 % слова (0,16–0,30 с), рампы 6 мс (без щелчков); первый звук и конец
     слова слышны;
  4) звук пересобирается (48 кГц AAC 192k), видео копируется. --dry — только список, без файла.
Отчёт: время, слово, контекст — я ПРОСЛУШИВАЮ/проверяю 3–5 мест перед отправкой владельцу.
"""
import argparse, json, os, re, subprocess, sys, wave
import numpy as np

CURSE = re.compile(r"""(?ix)
  \*                                             # Whisper сам пишет «б***ть»
 | ^бля($|т|д|ть|дь|ха(?!$)) | ^бляд | ^блят
 | ху[йеёяию] | хуе | хуё | нахуй | похуй | охуе | охуи | хуйн
 | п[иеё]зд | п[иеё]зн | п[иеё]сд
 | (^|за|на|по|вы|у|от|отъ|раз|разъ|рас|под|подъ|при|про|до|съ|об|объ|въ|из|изъ|пере|долбо|недо)[её]б(а|у|л|н|и|ё|е|ы|о|т|с|к|ш|$)
 | ^ёб | ^еб[аеуи]
 | муд[ао]к | мудил | пид[оа]р | гандон | гондон | залуп | шлюх
""")
SUKA = re.compile(r"(?i)^сук[аиуой]?$")

def norm(w): return re.sub(r"[^а-яёa-z*]", "", w.lower())

def transcribe(wav16, model):
    from faster_whisper import WhisperModel, BatchedInferencePipeline
    w = wave.open(wav16); a = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    m = BatchedInferencePipeline(WhisperModel(model, device="cpu", compute_type="int8"))
    segs, _ = m.transcribe(a, language="ru", batch_size=8, word_timestamps=True)
    return [(round(x.start, 3), round(x.end, 3), x.word.strip()) for s in segs for x in s.words]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("inp"); ap.add_argument("out")
    ap.add_argument("--words"); ap.add_argument("--model", default="small"); ap.add_argument("--dry", action="store_true")
    ap.add_argument("--suka", action="store_true"); ap.add_argument("--report")
    x = ap.parse_args()
    tmp = x.out + ".mm"; os.makedirs(tmp, exist_ok=True)
    wav48 = f"{tmp}/a48.wav"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", x.inp, "-vn", "-ac", "2", "-ar", "48000", wav48], check=True)
    cache = x.words or (x.inp + ".words.json")
    if os.path.exists(cache): W = [w[:3] for w in json.load(open(cache))]
    else:
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav48, "-ac", "1", "-ar", "16000", f"{tmp}/a16.wav"], check=True)
        W = transcribe(f"{tmp}/a16.wav", x.model); json.dump(W, open(cache, "w"), ensure_ascii=False)
    w = wave.open(wav48); sr = w.getframerate(); n = w.getnframes()
    a = np.frombuffer(w.readframes(n), np.int16).reshape(-1, 2).astype(np.float32)
    hits = []
    for i, (s, e, t) in enumerate(W):
        k = norm(t)
        if not k or not (CURSE.search(k) or (x.suka and SUKA.match(k))): continue
        dur = max(0.08, e - s)
        # mute the MIDDLE of the word (energy-based onset search was tested 16.11: with words running together it takes
        # the previous word's tail as the onset and mutes too early). Length = half the word, 0.12–0.30 s (reference:
        # median 0.22 s). The first sound and the ending stay audible («б… ть»).
        L = min(0.30, max(0.16, 0.6 * dur)); c = s + 0.5 * dur
        m0 = max(s + 0.03, c - L / 2); m1 = min(m0 + L, e + 0.03)
        ctx = " ".join(z[2] for z in W[max(0, i - 4):i + 3])
        hits.append((m0, m1, t, ctx))
    lines = [f"{int(m0 // 60)}:{m0 % 60:05.2f}  mute {int((m1 - m0) * 1000)} ms  «{t}»  | {c}" for m0, m1, t, c in hits]
    print(f"мат найден: {len(hits)}"); print("\n".join(lines))
    if x.report: open(x.report, "w").write("\n".join(lines) + "\n")
    if x.dry: return
    g = np.ones(len(a), np.float32); r = int(sr * 0.006)
    for m0, m1, _, _ in hits:
        i0, i1 = int(m0 * sr), int(m1 * sr)
        g[i0:i1] = 0
        g[max(0, i0 - r):i0] = np.minimum(g[max(0, i0 - r):i0], np.linspace(1, 0, i0 - max(0, i0 - r)))
        g[i1:i1 + r] = np.minimum(g[i1:i1 + r], np.linspace(0, 1, len(g[i1:i1 + r])))
    out = (a * g[:, None]).clip(-32768, 32767).astype(np.int16)
    wo = wave.open(f"{tmp}/muted.wav", "wb"); wo.setnchannels(2); wo.setsampwidth(2); wo.setframerate(sr); wo.writeframes(out.tobytes()); wo.close()
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", x.inp, "-i", f"{tmp}/muted.wav", "-map", "0:v", "-map", "1:a",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", x.out], check=True)
    subprocess.run(["rm", "-rf", tmp])
    print("done", x.out)

if __name__ == "__main__": main()
