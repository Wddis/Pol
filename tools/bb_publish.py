"""Full BrightBean publish: upload video (+thumbnail) and post to YouTube.
usage: python3 bb_publish.py meta.json
meta.json: {"video": "path.mp4", "thumb": "path.jpg"|null, "title": "...", "caption": "...", "tags": [...],
            "privacy": "public", "category": "24", "when": "now" | "2026-11-12T12:00:00+03:00", "key": "unique-id"}"""
import json, sys, os, subprocess, urllib.request, concurrent.futures as cf
sys.path.insert(0, os.path.dirname(__file__)); from brightbean import call
m = json.load(open(sys.argv[1])); K = m["key"]
acc = [a for a in call("social_list_accounts", {})["data"] if a["platform"] == "youtube"][0]["id"]

def upload(path, mtype, w, h, dur=None, tag="v"):
    size = os.path.getsize(path)
    r = call("social_request_media_upload", {"mediaType": mtype, "sizeBytes": size, "idempotencyKey": f"{K}-{tag}-req"})
    mid, ps, n = r["mediaAssetId"], r["partSize"], r["partCount"]
    urls = {u["partNumber"]: u["url"] for u in r["urls"]}
    nxt = max(urls) + 1
    while nxt <= n:
        rr = call("social_sign_upload_parts", {"uploadId": mid, "fromPart": nxt, "count": min(16, n - nxt + 1), "idempotencyKey": f"{K}-{tag}-sign{nxt}"})
        for u in rr["urls"]: urls[u["partNumber"]] = u["url"]
        nxt = max(urls) + 1
    def put(p):
        with open(path, "rb") as f: f.seek((p - 1) * ps); data = f.read(ps)
        for k in range(5):
            try:
                res = urllib.request.urlopen(urllib.request.Request(urls[p], data=data, method="PUT"), timeout=600)
                return {"partNumber": p, "etag": res.headers["ETag"]}
            except Exception as e: print("retry", p, e, flush=True)
        raise SystemExit(f"part {p} failed")
    with cf.ThreadPoolExecutor(6) as ex: parts = sorted(ex.map(put, range(1, n + 1)), key=lambda x: x["partNumber"])
    fin = {"uploadId": mid, "parts": parts, "width": w, "height": h, "idempotencyKey": f"{K}-{tag}-fin"}
    if dur: fin["durationSec"] = dur
    call("social_finalize_media_upload", fin); print("uploaded", tag, mid, flush=True); return mid

probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "json", m["video"]]))
st = probe["streams"][0]; vid = upload(m["video"], "video", st["width"], st["height"], round(float(probe["format"]["duration"]), 2), "v")
yt = {"privacyStatus": m.get("privacy", "public"), "madeForKids": False, "containsSyntheticMedia": False, "categoryId": m.get("category", "24"), "tags": m.get("tags", [])}
if m.get("thumb"): yt["thumbnail"] = {"assetId": upload(m["thumb"], "image", 1280, 720, None, "t")}
when = m.get("when", "now")
delivery = {"mode": "now", "timezone": "Europe/Moscow"} if when == "now" else {"mode": "at", "at": when, "timezone": "Europe/Moscow"}
r = call("social_create_draft", {"accounts": [acc], "title": m["title"], "caption": m["caption"], "mediaAssetIds": [vid],
         "perAccount": {acc: {"options": {"youtube": yt}}}, "delivery": delivery, "idempotencyKey": f"{K}-post"})
post = r["post"] if isinstance(r, dict) and "post" in r else r
print("POST", post["id"] if isinstance(post, dict) else post, post.get("status") if isinstance(post, dict) else "")
