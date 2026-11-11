"""Upload a video (+thumbnail) to YouTube through the channel's own YouTube Data API project.
No browser, no identity checks; quota 10k units/day (upload 1600, thumbnail 50).

usage: python3 yt_api_upload.py <secrets_dir> meta.json
secrets_dir holds (NEVER commit them):
  yt_client.json  {"client_id": "...", "client_secret": "..."}
  yt_token.json   token response from the device flow (must contain refresh_token)
meta.json: {video, thumb?, title, description, tags[], privacy: public|unlisted|private,
            category: "24", publishAt?: ISO-8601 UTC (needs privacy=private)}
Get a token (one time): POST https://oauth2.googleapis.com/device/code with client_id and
scope=https://www.googleapis.com/auth/youtube, owner enters user_code at google.com/device,
then poll https://oauth2.googleapis.com/token (grant_type=urn:ietf:params:oauth:grant-type:device_code).
"""
import json, os, sys, time, urllib.request, urllib.parse, urllib.error

sd, meta_path = sys.argv[1], sys.argv[2]
m = json.load(open(meta_path))
c = json.load(open(os.path.join(sd, "yt_client.json")))
tok = json.load(open(os.path.join(sd, "yt_token.json")))


def access_token():
    data = urllib.parse.urlencode({"client_id": c["client_id"], "client_secret": c["client_secret"],
                                   "refresh_token": tok["refresh_token"], "grant_type": "refresh_token"}).encode()
    return json.load(urllib.request.urlopen("https://oauth2.googleapis.com/token", data, timeout=60))["access_token"]


AT = access_token()
H = lambda extra=None: {"Authorization": f"Bearer {AT}", **(extra or {})}

status = {"privacyStatus": m.get("privacy", "public"), "selfDeclaredMadeForKids": False,
          "containsSyntheticMedia": False}
if m.get("publishAt"):
    status["privacyStatus"] = "private"; status["publishAt"] = m["publishAt"]
body = {"snippet": {"title": m["title"], "description": m.get("description", ""), "tags": m.get("tags", []),
                    "categoryId": m.get("category", "24"), "defaultLanguage": "ru", "defaultAudioLanguage": "ru"},
        "status": status}
size = os.path.getsize(m["video"])
req = urllib.request.Request(
    "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status",
    data=json.dumps(body).encode(), method="POST",
    headers=H({"Content-Type": "application/json; charset=UTF-8", "X-Upload-Content-Type": "video/mp4",
               "X-Upload-Content-Length": str(size)}))
try:
    url = urllib.request.urlopen(req, timeout=120).headers["Location"]
except urllib.error.HTTPError as e:
    raise SystemExit(f"init error {e.code}: {e.read().decode()[:800]}")

CH = 32 * 1024 * 1024
pos, vid = 0, None
with open(m["video"], "rb") as f:
    while vid is None:
        f.seek(pos); chunk = f.read(CH); end = pos + len(chunk) - 1
        r = urllib.request.Request(url, data=chunk, method="PUT",
                                   headers=H({"Content-Length": str(len(chunk)), "Content-Range": f"bytes {pos}-{end}/{size}"}))
        for k in range(6):
            try:
                res = urllib.request.urlopen(r, timeout=600)
                vid = json.load(res)["id"]; break
            except urllib.error.HTTPError as e:
                if e.code == 308:
                    rng = e.headers.get("Range"); pos = int(rng.split("-")[1]) + 1 if rng else 0; break
                if e.code == 401: AT = access_token(); r.headers["Authorization"] = f"Bearer {AT}"; continue
                if e.code >= 500: time.sleep(2 ** k); continue
                raise SystemExit(f"upload error {e.code}: {e.read().decode()[:500]}")
            except Exception as e:
                print("retry", e, flush=True); time.sleep(2 ** k)
        else:
            raise SystemExit("upload failed")
        print(f"uploaded {min(pos, size) * 100 // size}%" if vid is None else "uploaded 100%", flush=True)
print("VIDEO_ID", vid, flush=True)

if m.get("thumb"):
    data = open(m["thumb"], "rb").read()
    ct = "image/png" if m["thumb"].lower().endswith(".png") else "image/jpeg"
    r = urllib.request.Request(f"https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId={vid}",
                               data=data, method="POST", headers=H({"Content-Type": ct}))
    try:
        urllib.request.urlopen(r, timeout=120); print("THUMB_OK", flush=True)
    except urllib.error.HTTPError as e:
        print("THUMB_ERROR", e.code, e.read().decode()[:300], flush=True)
print("LINK https://youtu.be/" + vid)
