// Streamer catalog as ONE Telegraph page (replaces per-streamer catalog messages in the feed).
//   STAGE=collect node tg.js run catalog_page.js  -> catalog_data.json (streamers, posts, sections) from styled captions
//   python3 telegraph_page.py                      -> creates/edits the Telegraph page, writes page.json {url, path}
//   STAGE=pin node tg.js run catalog_page.js       -> pinned post (id 4): "📚 Каталог" block with links to the page;
//                                                     deletes old catalog messages listed in catalogs.json (then empties it)
const fs = require("fs");
const { HTMLParser } = require("telegram/extensions/html");
const CH = "kinovibetwitch", PIN = 4;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function withFlood(fn) {
  for (let a = 0; ; a++) {
    try { return await fn(); } catch (e) {
      if (e.errorMessage === "MESSAGE_NOT_MODIFIED") return null;
      const wait = e.seconds || (/FLOOD/.test(e.errorMessage || e.message || "") ? 60 : 0);
      if (!wait || a > 8) throw e;
      console.log(`flood wait ${wait}s`); await sleep((wait + 2) * 1000);
    }
  }
}
const SERIES = {
  человекпаук: "🕷 Человек-паук", какприручитьдракона: "🐉 Как приручить дракона", монстрынаканикулах: "🏨 Монстры на каникулах",
  тачки: "🏁 Тачки", достатьножи: "🔪 Достать ножи", великийуравнитель: "🔨 Великий уравнитель", богатыри: "🐴 Три богатыря",
  пиратыкарибскогоморя: "🏴‍☠️ Пираты Карибского моря", пила: "🩸 Пила", домспаранормальнымиявлениями: "👻 Дом с паранормальными явлениями",
  властелинколец: "💍 Властелин колец",
};
const KINDS = [["сериал", "📺 Сериалы"], ["мультфильм", "🎨 Мультфильмы и аниме"], ["аниме", "🎨 Мультфильмы и аниме"], ["фильм", "🎬 Фильмы"]];
const ORDER = [...Object.values(SERIES), "🎬 Фильмы", "🎨 Мультфильмы и аниме", "📺 Сериалы"];
const plural = (n) => (n % 10 === 1 && n % 100 !== 11 ? "запись" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20) ? "записи" : "записей");
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

async function collect(client) {
  const ch = await client.getEntity(CH);
  const all = []; let off = 0;
  for (;;) { const ms = await client.getMessages(ch, { limit: 100, offsetId: off }); if (!ms.length) break; all.push(...ms); off = ms[ms.length - 1].id; }
  const st = {};
  for (const m of all) {
    const t = m.message || "";
    if (!t.includes("🎙 Стример:")) continue;
    const link = (m.entities || []).find((e) => e.url && e.url.includes("twitch.tv/"));
    const head = t.split("\n")[0].match(/^\S+\s+(.+?) \((\d{4})\)/);
    if (!link || !head) continue;
    const login = link.url.split("/").pop().toLowerCase();
    const display = t.slice(link.offset, link.offset + link.length);
    const tags = (t.trim().split("\n").pop().match(/#[\wа-яё]+/gi) || []).map((x) => x.slice(1).toLowerCase());
    const imdb = (t.match(/IMDb ([\d.]+)/) || [])[1] || "—";
    const ser = tags.find((x) => SERIES[x]);
    const section = ser ? SERIES[ser] : (KINDS.find(([k]) => tags.includes(k)) || KINDS[3])[1];
    (st[login] = st[login] || { login, display, posts: [] }).posts.push({ id: m.id, title: head[1], year: Number(head[2]), imdb, section });
  }
  const streamers = Object.values(st).sort((a, b) => b.posts.length - a.posts.length || a.display.localeCompare(b.display));
  for (const s of streamers) {
    const groups = {};
    for (const p of s.posts) (groups[p.section] = groups[p.section] || []).push(p);
    s.sections = Object.entries(groups).sort((a, b) => ORDER.indexOf(a[0]) - ORDER.indexOf(b[0]))
      .map(([name, ps]) => ({ name, posts: ps.sort((a, b) => a.year - b.year || a.id - b.id) }));
    delete s.posts; s.count = s.sections.reduce((n, x) => n + x.posts.length, 0);
  }
  fs.writeFileSync("catalog_data.json", JSON.stringify({ channel: CH, streamers }, null, 1));
  console.log("collected", streamers.length, "streamers,", streamers.reduce((n, s) => n + s.count, 0), "posts");
}

async function pin(client) {
  const ch = await client.getEntity(CH);
  const data = JSON.parse(fs.readFileSync("catalog_data.json", "utf8"));
  const page = JSON.parse(fs.readFileSync("page.json", "utf8"));
  const pm = (await client.getMessages(ch, { ids: [PIN] }))[0];
  const lines = HTMLParser.unparse(pm.message, pm.entities).split("\n");
  const start = lines.findIndex((l) => l.includes("📚"));
  const end = lines.findIndex((l, i) => i > start && l.includes("Как найти нужное"));
  const total = data.streamers.reduce((n, s) => n + s.count, 0);
  const block = [
    `<b>📚 Каталог записей</b>`,
    `Все фильмы по стримерам и сериям — на одной странице: <a href="${page.url}">открыть каталог</a>`,
    "",
    ...data.streamers.map((s) => `🎙 <a href="${page.url}#${encodeURIComponent(s.anchor || s.display)}"><b>${esc(s.display)}</b></a> — ${s.count} ${plural(s.count)}`),
    "",
    `Всего в канале: ${total} ${plural(total)}`,
    "",
  ];
  lines.splice(start, end - start, ...block);
  await withFlood(() => client.editMessage(ch, { message: PIN, text: lines.join("\n"), parseMode: "html", linkPreview: false }));
  console.log("pinned updated, length", lines.join("\n").length);
  if (fs.existsSync("catalogs.json")) {
    const old = Object.values(JSON.parse(fs.readFileSync("catalogs.json", "utf8")));
    if (old.length) { await withFlood(() => client.deleteMessages(ch, old, { revoke: true })); console.log("deleted old catalog posts:", old.join(",")); }
    fs.writeFileSync("catalogs.json", "{}\n");
  }
}

module.exports = async (client) => (process.env.STAGE === "pin" ? pin(client) : collect(client));
