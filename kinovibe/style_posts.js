// Restyle channel posts from a JSON spec (default new.json), add them to the pinned navigator
// and append them to posts.json (for covers).  Run:  SPEC=new.json node tg.js run style_posts.js
//
// Spec item: {
//   "ids": [72,73,74], "emoji": "🧬", "title": "…", "year": 2006,
//   "login": "zubarefff", "display": "zubarefff",
//   "date": "24.02.2024"?, "event": "Киногонка · сезон 5 · день 5"?, "eventTag": "#киногонка — сезон 5, день 5"?,
//   "genres": ["фантастика","боевик"], "film": "1 ч 44 мин", "rec": "3 части (45 + 43 + 41 мин)",
//   "start": "1:06:50"?, "imdb": "6.6", "kp": "7.3", "desc": "…", "tags": "#фильм", "age": "18+"?
// }
const fs = require("fs");
const { HTMLParser } = require("telegram/extensions/html");
const NAV_PIN = 4;

function caption(p) {
  const lines = [
    `${p.emoji} <b>${p.title}</b> (${p.year})${p.age ? " · " + p.age : ""}`,
    "",
    `🎙 Стример: <a href="https://www.twitch.tv/${p.login}"><b>${p.display}</b></a> #${p.login.toLowerCase()}`,
  ];
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
      await client.editMessage(ch, { message: p.ids[0], text: caption(p), parseMode: "html", linkPreview: false });
      console.log("caption:", p.ids[0], p.title);
    } catch (e) {
      if (e.errorMessage !== "MESSAGE_NOT_MODIFIED") throw e;
      console.log("caption already ok:", p.ids[0], p.title);
    }
  }
  // navigator
  const pin = (await client.getMessages(ch, { ids: [NAV_PIN] }))[0];
  let lines = HTMLParser.unparse(pin.message, pin.entities).split("\n");
  for (const p of spec) {
    const link = `• <a href="https://t.me/kinovibetwitch/${p.ids[0]}">${p.title} (${p.year})</a>`;
    if (lines.some((l) => l.includes(`kinovibetwitch/${p.ids[0]}"`))) continue;
    const head = lines.findIndex((l) => l.includes(`twitch.tv/${p.login}"`));
    if (head >= 0) {
      let end = head + 1;
      while (end < lines.length && lines[end].startsWith("•")) end++;
      lines.splice(end, 0, link);
    } else {
      const nav = lines.findIndex((l) => l.includes("Как найти нужное"));
      lines.splice(nav - 1, 0, "", `🎙 <a href="https://www.twitch.tv/${p.login}"><b>${p.display}</b></a> #${p.login.toLowerCase()}`, link);
    }
  }
  await client.editMessage(ch, { message: NAV_PIN, text: lines.join("\n"), parseMode: "html", linkPreview: false });
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
  // verify
  const pm = (await client.getMessages(ch, { ids: [NAV_PIN] }))[0];
  const live = new Set((await client.getMessages(ch, { limit: 200 })).map((m) => m.id));
  const urls = pm.entities.filter((e) => e.url && e.url.includes("t.me/")).map((e) => Number(e.url.split("/").pop()));
  console.log("navigator links:", urls.length, "broken:", urls.filter((u) => !live.has(u)), "pinned length:", pm.message.length);
};
module.exports.caption = caption;
