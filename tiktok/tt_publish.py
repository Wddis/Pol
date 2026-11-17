"""Upload TikTok cuts to BrightBean and schedule them on the TikTok channel, one every INTERVAL minutes.
usage: python3 tt_publish.py OUT_DIR CAPTIONS.json FIRST LAST START_ISO [INTERVAL_MIN | "15,25,35,60,..."]
env PREFIX (file prefix, default tt_), KEYTAG (idempotency suffix)
Uses tools/brightbean.py of branch ccr-afb88262-upss12 as is (read-only, $TOOLS)."""
import json, sys, os, subprocess, urllib.request, datetime as dt, concurrent.futures as cf
sys.path.insert(0, os.environ.get("TOOLS", "/home/user/pol-tools/tools")); from brightbean import call
out, capf, a, b, start = sys.argv[1:6]; iv = sys.argv[6] if len(sys.argv) > 6 else "10"
gaps = [int(x) for x in iv.split(",")]; PRE = os.environ.get("PREFIX", "tt_"); KT = os.environ.get("KEYTAG", "")
caps = json.load(open(capf)); t0 = dt.datetime.fromisoformat(start)
acc = [x for x in call("social_list_accounts", {})["data"] if x["platform"] == "tiktok"][0]["id"]
def upload(path, K):
    size = os.path.getsize(path)
    r = call("social_request_media_upload", {"mediaType": "video", "sizeBytes": size, "idempotencyKey": f"{K}-req"})
    mid, ps, n = r["mediaAssetId"], r["partSize"], r["partCount"]
    urls = {u["partNumber"]: u["url"] for u in r["urls"]}
    while max(urls) < n:
        nxt = max(urls) + 1
        rr = call("social_sign_upload_parts", {"uploadId": mid, "fromPart": nxt, "count": min(16, n - nxt + 1), "idempotencyKey": f"{K}-sign{nxt}"})
        for u in rr["urls"]: urls[u["partNumber"]] = u["url"]
    def put(p):
        with open(path, "rb") as f: f.seek((p - 1) * ps); data = f.read(ps)
        for k in range(5):
            try: return {"partNumber": p, "etag": urllib.request.urlopen(urllib.request.Request(urls[p], data=data, method="PUT"), timeout=600).headers["ETag"]}
            except Exception as e: print("retry", p, e, flush=True)
        raise SystemExit(f"part {p} failed")
    with cf.ThreadPoolExecutor(6) as ex: parts = sorted(ex.map(put, range(1, n + 1)), key=lambda x: x["partNumber"])
    d = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path]))
    call("social_finalize_media_upload", {"uploadId": mid, "parts": parts, "width": 1080, "height": 1920, "durationSec": round(d, 2), "idempotencyKey": f"{K}-fin"})
    return mid
for i, n in enumerate(range(int(a), int(b) + 1)):
    nn = f"{n:02d}"; K = f"tt2929609163-{nn}{KT}"
    off = gaps[0] * i if len(gaps) == 1 else sum(gaps[:i]) if i <= len(gaps) else sum(gaps) + gaps[-1] * (i - len(gaps))
    when = (t0 + dt.timedelta(minutes=off)).isoformat()
    mid = upload(f"{out}/{PRE}{nn}.mp4", K)
    r = call("social_create_draft", {"accounts": [acc], "caption": caps.get(nn, caps.get("*")), "mediaAssetIds": [mid],
        "perAccount": {acc: {"options": {"tiktok": {"privacyLevel": "PUBLIC_TO_EVERYONE", "allowComment": True, "allowDuet": True,
            "allowStitch": True, "disclose": False, "aiGenerated": False, "autoAddMusic": False}}}},
        "delivery": {"mode": "at", "at": when, "timezone": "Europe/Moscow"}, "idempotencyKey": f"{K}-post"})
    p = r.get("post", r) if isinstance(r, dict) else r
    print(nn, when, p.get("id") if isinstance(p, dict) else p, p.get("status") if isinstance(p, dict) else "", flush=True)
