"""Upload to YouTube via the internal Studio API (youtube-up), session token via Playwright.
No API key, no quota. Needs cookies.txt (Netscape) of an account with access to the channel.
usage: <venv>/bin/python yt_upload.py cookies.txt meta.json
meta.json: {"video","title","description","tags":[],"thumb","privacy":"PUBLIC|UNLISTED|PRIVATE","scheduled":null|"ISO","category":24}"""
import json, os, subprocess, sys
from http.cookiejar import Cookie
from youtube_up import YTUploaderSession, Metadata
HERE = os.path.dirname(os.path.abspath(__file__))

class PWSession(YTUploaderSession):
    def _get_session_token(self):
        env = dict(os.environ, NODE_PATH=subprocess.check_output(["npm", "root", "-g"], text=True).strip())
        out = subprocess.run(["node", os.path.join(HERE, "session_token.js"), self._cookies.filename],
                             capture_output=True, text=True, env=env, timeout=240).stdout.strip().splitlines()[-1]
        r = json.loads(out)
        if "sessionToken" not in r: raise SystemExit(f"session token failed: {r}")
        self._session_token = r["sessionToken"]
        self._cookies.set_cookie(Cookie(None, "SESSION_TOKEN", self._session_token, None, False, "", False, False,
                                        "", False, False, None, False, None, None, {}))
        self._cookies.save()

if __name__ == "__main__":
    ck, m = sys.argv[1], json.load(open(sys.argv[2]))
    s = PWSession.from_cookies_txt(ck)
    md = dict(title=m["title"], description=m.get("description", ""), tags=tuple(m.get("tags", [])),
              privacy=m.get("privacy", "PUBLIC"), made_for_kids=False, category=m.get("category", "ENTERTAINMENT"))
    if m.get("thumb"): md["thumbnail"] = m["thumb"]
    if m.get("scheduled"):
        import datetime; md["scheduled_upload"] = datetime.datetime.fromisoformat(m["scheduled"])
    vid = s.upload(m["video"], Metadata(**md))
    print("VIDEO_ID", vid, f"https://youtu.be/{vid}")
