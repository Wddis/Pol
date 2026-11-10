// Builds one catalog message per streamer and rewrites the "📚" block of the pinned post (id 4)
// into a short list of links to those catalogs.  Run after every new batch:
//   node tg.js run build_catalogs.js
// Data comes from the styled captions in the channel, catalog message ids live in catalogs.json.
const fs = require("fs");
const { HTMLParser } = require("telegram/extensions/html");
const CH = "kinovibetwitch", PIN = 4, REG = "catalogs.json";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function withFlood(fn) {
  for (let attempt = 0; ; attempt++) {
    try { return await fn(); } catch (e) {
      if (e.errorMessage === "MESSAGE_NOT_MODIFIED") return null;
      const wait = e.seconds || (/FLOOD/.test(e.errorMessage || e.message || "") ? 60 : 0);
      if (!wait || attempt > 8) throw e;
      console.log(`flood wait ${wait}s`);
      await sleep((wait + 2) * 1000);
    }
  }
}
// Franchise hashtags -> section titles (add new franchises here).
const SERIES = {
  человекпаук: "🕷 Человек-паук", какприручитьдракона: "🐉 Как приручить дракона",
  монстрынаканикулах: "🏨 Монстры на каникулах", тачки: "🏁 Тачки", достатьножи: "🔪 Достать ножи",
  великийуравнитель: "🔨 Великий уравнитель", богатыри: "🐴 Три богатыря",
  пиратыкарибскогоморя: "🏴‍☠️ Пираты Карибского моря", пила: "🩸 Пила", домспаранормальнымиявлениями: "👻 Дом с паранормальными явлениями", властелинколец: "💍 Властелин колец",
};
const KINDS = [["сериал", "📺 Сериалы"], ["мультфильм", "🎨 Мультфильмы и аниме"], ["аниме", "🎨 Мультфильмы и аниме"], ["фильм", "🎬 Фильмы"]];
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const plural = (n) => (n % 10 === 1 && n % 100 !== 11 ? "запись" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20) ? "записи" : "записей");

module.exports = async (client, Api) => {
  const ch = await client.getEntity(CH);
  const reg = fs.existsSync(REG) ? JSON.parse(fs.readFileSync(REG, "utf8")) : {};
  const all = [];
  let off = 0;
  for (;;) { const ms = await client.getMessages(ch, { limit: 100, offsetId: off }); if (!ms.length) break; all.push(...ms); off = ms[ms.length - 1].id; }

  // collect styled posts
  const streamers = {};
  for (const m of all) {
    const t = m.message || "";
    if (!t.includes("🎙 Стример:")) continue;
    const link = (m.entities || []).find((e) => e.url && e.url.includes("twitch.tv/"));
    const head = t.split("\n")[0].match(/^\S+\s+(.+?) \((\d{4})\)/);
    if (!link || !head) { console.log("skip unparsed post", m.id); continue; }
    const login = link.url.split("/").pop().toLowerCase();
    const display = t.slice(link.offset, link.offset + link.length);
    const tags = (t.trim().split("\n").pop().match(/#[\wа-яё]+/gi) || []).map((x) => x.slice(1).toLowerCase());
    const imdb = (t.match(/IMDb ([\d.]+)/) || [])[1] || "—";
    const s = (streamers[login] = streamers[login] || { login, display, posts: [] });
    s.posts.push({ id: m.id, title: head[1], year: Number(head[2]), imdb, tags });
  }

  const pinUrl = `https://t.me/${CH}/${PIN}`;
  const order = Object.values(streamers).sort((a, b) => b.posts.length - a.posts.length || a.display.localeCompare(b.display));
  for (const s of order) {
    const groups = new Map();
    for (const p of s.posts) {
      const ser = p.tags.find((x) => SERIES[x]);
      const kind = ser ? SERIES[ser] : (KINDS.find(([k]) => p.tags.includes(k)) || KINDS[3])[1];
      if (!groups.has(kind)) groups.set(kind, []);
      groups.get(kind).push(p);
    }
    // franchises first (in SERIES order), then films / cartoons / series
    const sectionOrder = [...Object.values(SERIES), "🎬 Фильмы", "🎨 Мультфильмы и аниме", "📺 Сериалы"];
    const blocks = [...groups.entries()].sort((a, b) => sectionOrder.indexOf(a[0]) - sectionOrder.indexOf(b[0])).map(([name, ps]) =>
      `<b>${name}</b>\n` + ps.sort((a, b) => a.year - b.year || a.id - b.id)
        .map((p) => `• <a href="https://t.me/${CH}/${p.id}">${esc(p.title)}</a> (${p.year}) · ⭐ ${p.imdb}`).join("\n"));
    const html = [
      `🎙 <b>${esc(s.display)}</b> — каталог записей`,
      "",
      `📺 Twitch: <a href="https://www.twitch.tv/${s.login}">twitch.tv/${s.login}</a>`,
      `🔎 Все записи по тегу: #${s.login}`,
      `🎞 В канале: ${s.posts.length} ${plural(s.posts.length)}`,
      "",
      blocks.join("\n\n"),
      "",
      `Нажмите на название — откроется запись. ⭐ — рейтинг IMDb.`,
      `⬅️ <a href="${pinUrl}">Все стримеры</a>`,
    ].join("\n");
    if (process.env.DRY) { console.log("=====", s.display, html.length, "chars\n" + html); continue; }
    if (reg[s.login]) {
      await withFlood(() => client.editMessage(ch, { message: reg[s.login], text: html, parseMode: "html", linkPreview: false }));
      console.log("catalog updated:", s.display, reg[s.login], s.posts.length);
    } else {
      const sent = await withFlood(() => client.sendMessage(ch, { message: html, parseMode: "html", linkPreview: false, silent: true }));
      reg[s.login] = sent.id;
      fs.writeFileSync(REG, JSON.stringify(reg, null, 1));
      console.log("catalog created:", s.display, sent.id, s.posts.length);
      await sleep(15000);
    }
    await sleep(2000);
  }
  if (process.env.DRY) return;
  fs.writeFileSync(REG, JSON.stringify(reg, null, 1));

  // pinned: replace the streamer block with links to catalogs
  const pin = all.find((m) => m.id === PIN);
  const lines = HTMLParser.unparse(pin.message, pin.entities).split("\n");
  const start = lines.findIndex((l) => l.includes("📚"));
  const end = lines.findIndex((l, i) => i > start && l.includes("Как найти нужное"));
  const total = order.reduce((n, s) => n + s.posts.length, 0);
  const block = [
    `<b>📚 Каталоги стримеров</b>`,
    `Нажмите на ник — откроется список всех записей стримера по сериям и годам.`,
    "",
    ...order.map((s) => `🎙 <a href="https://t.me/${CH}/${reg[s.login]}"><b>${esc(s.display)}</b></a> — ${s.posts.length} ${plural(s.posts.length)}`),
    "",
    `Всего в канале: ${total} ${plural(total)}`,
    "",
  ];
  lines.splice(start, end - start, ...block);
  await withFlood(() => client.editMessage(ch, { message: PIN, text: lines.join("\n"), parseMode: "html", linkPreview: false }));
  const check = (await client.getMessages(ch, { ids: [PIN, ...Object.values(reg)] }));
  for (const m of check) console.log(m.id, "len:", m.message.length, "links:", (m.entities || []).filter((e) => e.url).length);
};
