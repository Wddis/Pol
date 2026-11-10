"""Schedule the same clip at the same time to TikTok AND YouTube Shorts via Buffer.

Usage: python3 schedule_buffer.py plan.json            # both services
       SERVICES=youtube python3 schedule_buffer.py plan.json   # only some services (tiktok,youtube)
plan.json: [{"url": "https://…/clip.mp4", "hook": "Даша смотрит «…»: «…» 😳",
             "tags": "#даша228плей #dasha228play …", "due_msk": "2026-11-11 01:30"}]
TikTok text:  hook + "Полная запись в био 🍿" + tags
YouTube:      title = hook (≤100 chars); description = hook + "Полная запись — в Telegram: t.me/kinovibetwitch 🍿" + "#shorts" + tags
Times are Moscow time (UTC+3). Uses ../buffer.sh (key injected by the environment proxy).
Prints one line per created post: due, service, result, Buffer post id.
"""
import datetime as dt, json, os, subprocess, sys

CHANNELS = {
    "tiktok": "6af35ed662b70b94b0e6430d",   # TikTok kinovibetwitch
    "youtube": "6af3934362b70b94b0e99c8d",  # YouTube «Фильмы со стримерами» (Shorts)
}
TG = "t.me/kinovibetwitch"
BUF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "buffer.sh")
Q = """mutation($i: CreatePostInput!){ createPost(input:$i){ __typename
  ... on PostActionSuccess { post { id status dueAt } }
  ... on InvalidInputError { message } ... on UnexpectedError { message } ... on LimitReachedError { message }
  ... on UnauthorizedError { message } ... on RestProxyError { message } ... on NotFoundError { message } } }"""


def post_input(service, p, due):
    if service == "tiktok":
        text = f'{p["hook"]}\nПолная запись в био 🍿\n\n{p["tags"]}'
        meta = {"tiktok": {"title": p["hook"]}}
    else:
        text = f'{p["hook"]}\nПолная запись — в Telegram: {TG} 🍿\n\n#shorts {p["tags"]}'
        meta = {"youtube": {"title": p["hook"][:100], "privacy": "public", "categoryId": "24", "madeForKids": False,
                            "notifySubscribers": True, "embeddable": True, "license": "youtube"}}
    return {"channelId": CHANNELS[service], "text": text, "schedulingType": "automatic", "mode": "customScheduled", "dueAt": due,
            "assets": [{"video": {"url": p["url"], "metadata": {"title": p["hook"][:100]}}}], "metadata": meta, "source": "kinovibe-claude"}


services = [s for s in os.environ.get("SERVICES", "tiktok,youtube").split(",") if s]
for p in json.load(open(sys.argv[1], encoding="utf-8")):
    due = (dt.datetime.strptime(p["due_msk"], "%Y-%m-%d %H:%M") - dt.timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:00Z")
    for s in services:
        out = json.loads(subprocess.run([BUF, Q, json.dumps({"i": post_input(s, p, due)})], capture_output=True, text=True).stdout)
        if "errors" in out:
            print(p["due_msk"], s, "ERROR", out["errors"][0]["message"][:200]); continue
        r = out["data"]["createPost"]
        print(p["due_msk"], s, r["__typename"], (r.get("post") or {}).get("id"), r.get("message", ""))
