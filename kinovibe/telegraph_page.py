"""Creates or updates the KinoVibe catalog page on Telegraph from catalog_data.json.

The Telegraph access token is a credential: it is kept in ~/.kinovibe_telegraph.json (or $TELEGRAPH_TOKEN),
never in the (public) repo. If the token is lost, the script creates a new account + page; then run
`STAGE=pin node tg.js run catalog_page.js` so the pinned post points to the new URL.
Writes page.json {url, path} and adds an "anchor" per streamer to catalog_data.json.
"""
import json, os, re, urllib.parse, urllib.request

API = "https://api.telegra.ph/"
TOKEN_FILE = os.path.expanduser("~/.kinovibe_telegraph.json")
TITLE = "КиноВайб — каталог записей"


def call(method, **params):
    data = urllib.parse.urlencode({k: (json.dumps(v, ensure_ascii=False) if isinstance(v, (list, dict)) else v) for k, v in params.items()}).encode()
    r = json.load(urllib.request.urlopen(urllib.request.Request(API + method, data=data), timeout=60))
    if not r.get("ok"):
        raise SystemExit(f"Telegraph {method} error: {r}")
    return r["result"]


def anchor(text):
    # Telegraph header ids: spaces -> '-', other characters kept
    return re.sub(r"[\s_]+", "-", text.strip())


def main():
    d = json.load(open("catalog_data.json", encoding="utf-8"))
    ch = d["channel"]
    st = {}
    if os.path.exists(TOKEN_FILE):
        st = json.load(open(TOKEN_FILE))
    token = os.environ.get("TELEGRAPH_TOKEN") or st.get("token")
    if not token:
        acc = call("createAccount", short_name="KinoVibe", author_name="КиноВайб", author_url=f"https://t.me/{ch}")
        token = acc["access_token"]; st = {"token": token}
        json.dump(st, open(TOKEN_FILE, "w")); os.chmod(TOKEN_FILE, 0o600)
    total = sum(s["count"] for s in d["streamers"])
    nodes = [
        {"tag": "p", "children": ["Все записи канала ", {"tag": "a", "attrs": {"href": f"https://t.me/{ch}"}, "children": [f"@{ch}"]},
                                  f" — по стримерам, сериям и годам. Всего: {total}. Нажмите на фильм, чтобы открыть запись в Telegram. ⭐ — рейтинг IMDb."]},
        {"tag": "p", "children": [{"tag": "strong", "children": ["Стримеры: "]}] + sum(
            ([{"tag": "a", "attrs": {"href": "#" + anchor(s["display"])}, "children": [s["display"]]}, f" ({s['count']})" + (" · " if i < len(d["streamers"]) - 1 else "")]
             for i, s in enumerate(d["streamers"])), [])},
    ]
    for s in d["streamers"]:
        s["anchor"] = anchor(s["display"])
        nodes.append({"tag": "h3", "children": [s["display"]]})
        nodes.append({"tag": "p", "children": [
            "🎙 Twitch: ", {"tag": "a", "attrs": {"href": f"https://www.twitch.tv/{s['login']}"}, "children": [f"twitch.tv/{s['login']}"]},
            f" · тег в канале: #{s['login']} · записей: {s['count']}"]})
        for sec in s["sections"]:
            nodes.append({"tag": "h4", "children": [sec["name"]]})
            nodes.append({"tag": "ul", "children": [
                {"tag": "li", "children": [{"tag": "a", "attrs": {"href": f"https://t.me/{ch}/{p['id']}"}, "children": [p["title"]]},
                                           f" ({p['year']}) · ⭐ {p['imdb']}"]} for p in sec["posts"]]})
    nodes.append({"tag": "p", "children": [{"tag": "a", "attrs": {"href": f"https://t.me/{ch}/4"}, "children": ["⬅️ Вернуться в канал"]}]})
    if st.get("path"):
        page = call("editPage", access_token=token, path=st["path"], title=TITLE, author_name="КиноВайб",
                    author_url=f"https://t.me/{ch}", content=nodes)
    else:
        page = call("createPage", access_token=token, title=TITLE, author_name="КиноВайб", author_url=f"https://t.me/{ch}", content=nodes)
        st["path"] = page["path"]; json.dump(st, open(TOKEN_FILE, "w"))
    json.dump({"url": page["url"], "path": page["path"]}, open("page.json", "w"))
    json.dump(d, open("catalog_data.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("page:", page["url"], "streamers:", len(d["streamers"]), "posts:", total)


if __name__ == "__main__":
    main()
