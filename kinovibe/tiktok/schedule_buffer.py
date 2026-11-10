"""Schedule TikTok posts in Buffer from a JSON plan.

Usage: python3 schedule_buffer.py plan.json
plan.json: [{"url": "https://…/clip.mp4", "hook": "Зубарев смотрит «Пленницы»: «…» 😳",
             "tags": "#зубарев #zubarefff …", "due_msk": "2026-11-10 22:45"}]
Text = hook + "Полная запись в био 🍿" + tags (see TIKTOK_STYLE.md). Times are Moscow time (UTC+3).
Uses ../buffer.sh (key injected by the environment proxy).
"""
import datetime as dt, json, os, subprocess, sys

CH = "6af35ed662b70b94b0e6430d"  # TikTok kinovibetwitch
BUF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "buffer.sh")
Q = """mutation($i: CreatePostInput!){ createPost(input:$i){ __typename
  ... on PostActionSuccess { post { id status dueAt assets { mimeType ... on VideoAsset { video { durationMs } } } } }
  ... on InvalidInputError { message } ... on UnexpectedError { message } ... on LimitReachedError { message }
  ... on UnauthorizedError { message } ... on RestProxyError { message } ... on NotFoundError { message } } }"""

for p in json.load(open(sys.argv[1], encoding="utf-8")):
    due = (dt.datetime.strptime(p["due_msk"], "%Y-%m-%d %H:%M") - dt.timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:00Z")
    text = f'{p["hook"]}\nПолная запись в био 🍿\n\n{p["tags"]}'
    inp = {"channelId": CH, "text": text, "schedulingType": "automatic", "mode": "customScheduled", "dueAt": due,
           "assets": [{"video": {"url": p["url"], "metadata": {"title": p["hook"]}}}],
           "metadata": {"tiktok": {"title": p["hook"]}}, "source": "kinovibe-claude"}
    out = json.loads(subprocess.run([BUF, Q, json.dumps({"i": inp})], capture_output=True, text=True).stdout)
    r = out["data"]["createPost"]
    print(p["due_msk"], r["__typename"], (r.get("post") or {}).get("id"), r.get("message", ""))
