"""List donation readings inside an event with the streamer's reply → candidates to cut (empty) or keep (joke).
Speeds up the manual rule «пустые донаты — вон, с шуткой — оставить» (docs/montage-style.md): the human only confirms.
usage: python3 donations.py transcript.txt FROM TO   →  lines «CUT? a–b  «donation…» → reply…»  and a ready cuts list"""
import sys, re, os
sys.path.insert(0, os.path.dirname(__file__)); import auto_events as AE
tr, A, B = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
L = AE.load(tr, A, B)
START = re.compile(r"(?i)(привет,?\s*(тош|тох|антон|тошка|тоха|доха|духа|тухо|тедвах)|мой первый донат|первый донат|"
                   r"передай,?\s*(пожалуйста,?\s*)?привет|день рождени|\bдр\b|поздрав|задонат|донатик|хотел спросить|вопрос)")
JOKE = re.compile(r"(?i)(ахах|хаха|ха-ха|ору|иди (отсюда|нахуй|на хуй)|ебать|пиздец|блять,? (ну|да)|лол)")
TOPIC = None
out = []; i = 0
while i < len(L):
    s, e, t = L[i]
    if START.search(t):
        j = i
        while j + 1 < len(L) and L[j + 1][0] - L[j][1] < 2.5 and L[j + 1][0] - s < 40: j += 1
        blk = L[i:j + 1]; txt = " / ".join(x for _, _, x in blk)
        joke = len(JOKE.findall(txt))
        out.append((blk[0][0], blk[-1][1], joke, txt))
        i = j + 1; continue
    i += 1
def mm(t): t = int(t); return f"{t // 60}:{t % 60:02d}"
for a, b, joke, txt in out:
    print(f"{'KEEP (шутка)' if joke >= 2 else 'CUT?        '} {mm(a)}–{mm(b)} ({a:.1f}-{b:.1f})  {txt[:230]}")
print("cuts =", [(round(a - 0.3, 1), round(b + 0.3, 1)) for a, b, j, _ in out if j < 2])
