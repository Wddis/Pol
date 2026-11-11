"""Download a Twitch VOD chat via Twitch's public GQL (offset paging, like TwitchDownloader).
usage: python3 twitch_chat.py <vod_id> <duration_sec> out.jsonl  -> {"t": sec, "u": user, "m": text} per line
Chat activity spikes = strongest reactions (see chat_peaks.py)."""
import json, sys, time, urllib.request
VID, DUR, OUT = sys.argv[1], int(sys.argv[2]), sys.argv[3]
H = {"Client-ID": "kimne78kx3ncx6brgo4mv6wki5h1ko", "Content-Type": "application/json"}
Q = 'query{video(id:"%s"){comments(contentOffsetSeconds:%d){edges{node{id contentOffsetSeconds commenter{displayName} message{fragments{text}}}}}}}'
def page(off):
    req = urllib.request.Request("https://gql.twitch.tv/gql", json.dumps({"query": Q % (VID, off)}).encode(), H)
    for k in range(6):
        try: return json.load(urllib.request.urlopen(req, timeout=30))["data"]["video"]["comments"]["edges"]
        except Exception: time.sleep(1 + k)
    return []
seen, off, f, n = set(), 0, open(OUT, "w"), 0
while off <= DUR:
    edges = page(off); last = off
    for e in edges:
        nd = e["node"]; last = max(last, nd["contentOffsetSeconds"])
        if nd["id"] in seen: continue
        seen.add(nd["id"]); n += 1
        f.write(json.dumps({"t": nd["contentOffsetSeconds"], "u": (nd["commenter"] or {}).get("displayName"),
                            "m": "".join(x["text"] for x in nd["message"]["fragments"])}, ensure_ascii=False) + "\n")
    off = last + 1 if last >= off else off + 30
    if n and n % 2000 < 60: print(f"{off}s {n} msgs", flush=True)
print("done", n, "messages ->", OUT)
