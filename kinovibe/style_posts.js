// Restyle channel posts from a JSON spec (default new.json) and append them to posts.json (for covers).
// Catalog page + pinned post are rebuilt separately (catalog_page.js / telegraph_page.py).  Run:  SPEC=new.json node tg.js run style_posts.js
//
// Spec item: {
//   "ids": [72,73,74], "emoji": "🧬", "title": "…", "year": 2006,
//   "login": "zubarefff", "display": "zubarefff", "ft": [["fruktozka","Fruktozka"]]? (co-streamers),
//   "date": "24.02.2024"?, "event": "Киногонка · сезон 5 · день 5"?, "eventTag": "#киногонка — сезон 5, день 5"?,
//   "genres": ["фантастика","боевик"], "film": "1 ч 44 мин", "rec": "3 части (45 + 43 + 41 мин)",
//   "start": "1:06:50"?, "imdb": "6.6", "kp": "7.3", "desc": "…", "tags": "#фильм", "age": "18+"?
// }
const fs = require("fs");
const { HTMLParser } = require("telegram/extensions/html");
const NAV_PIN = 4;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Retries a call when Telegram answers FLOOD_WAIT / FLOOD (waits as long as asked).
async function withFlood(fn) {
  for (let attempt = 0; ; attempt++) {
    try { return await fn(); } catch (e) {
      const wait = e.seconds || (/FLOOD/.test(e.errorMessage || e.message || "") ? 60 : 0);
      if (!wait || attempt > 8) throw e;
      console.log(`flood wait ${wait}s`);
      await sleep((wait + 2) * 1000);
    }
  }
}

function caption(p) {
  const lines = [
    `${p.emoji} <b>${p.title}</b> (${p.year})${p.age ? " · " + p.age : ""}`,
    "",
    `🎙 Стример: <a href="https://www.twitch.tv/${p.login}"><b>${p.display}</b></a> #${p.login.toLowerCase()}`,
  ];
  if (p.ft && p.ft.length) lines.push(`🤝 Вместе с: ${p.ft.map(([l, d]) => `<a href="https://www.twitch.tv/${l}"><b>${d}</b></a>`).join(", ")}`);
  if (p.eventTag) lines.push(`🏁 Ивент: ${p.eventTag}`);
  if (p.date) lines.push(`📅 Стрим: ${p.date}`);
  lines.push(`🎭 Жанр: ${p.genres.map((g) => "#" + g).join(" ")}`);
  lines.push(`⏱ ${p.kind || "Фильм"}: ${p.film} · запись: ${p.rec}`);
  if (p.start) lines.push(`▶️ Начало фильма: ${p.start} (1-я часть)`);
  lines.push(`⭐ IMDb ${p.imdb} · Кинопоиск ${p.kp}`, "", `<blockquote>${p.desc}</blockquote>`, "",
    "💬 Делитесь впечатлениями в комментариях", "", p.tags || "#фильм");
  return lines.join("\n");
}
const toSec = (t) => t.split(":").map(Number).reduce((a, b) => a * 60 + b, 0);

module.exports = async (client, Api) => {
  const spec = JSON.parse(fs.readFileSync(process.env.SPEC || "new.json", "utf8"));
  const ch = await client.getEntity("kinovibetwitch");
  for (const p of spec) {
    try {
      await withFlood(() => client.editMessage(ch, { message: p.ids[0], text: caption(p), parseMode: "html", linkPreview: false }));
      await sleep(2000);
      console.log("caption:", p.ids[0], p.title);
    } catch (e) {
      if (e.errorMessage !== "MESSAGE_NOT_MODIFIED") throw e;
      console.log("caption already ok:", p.ids[0], p.title);
    }
  }
  // navigator: catalog page is rebuilt by catalog_page.js + telegraph_page.py (run after this script)
  // registry for covers
  const reg = JSON.parse(fs.readFileSync("posts.json", "utf8"));
  const have = new Set(reg.flatMap((x) => x.ids));
  for (const p of spec) {
    if (have.has(p.ids[0])) continue;
    const r = { ids: p.ids, title: p.title, meta: `${p.year} · ${p.genres.join(", ")}${p.age ? " · " + p.age : ""}`, streamer: p.display, imdb: p.imdb, kp: p.kp };
    if (p.event) r.event = p.event;
    if (p.start) r.start = toSec(p.start);
    reg.push(r);
  }
  fs.writeFileSync("posts.json", "[\n" + reg.map((x) => "  " + JSON.stringify(x)).join(",\n") + "\n]\n");
  console.log("done. Next: catalog_page.js collect, telegraph_page.py, catalog_page.js pin, make_covers.py, apply_covers.js");
};
module.exports.caption = caption;
