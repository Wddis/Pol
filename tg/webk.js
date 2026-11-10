// Open Telegram Web K with the existing session and screenshot given posts
process.env.GLOBAL_AGENT_HTTPS_PROXY = process.env.HTTPS_PROXY;
const fs = require("fs");
const { StringSession } = require("telegram/sessions");
const { chromium } = require("playwright");
(async () => {
  const s = new StringSession(fs.readFileSync(__dirname + "/session.txt", "utf8"));
  const dc = s.dcId;
  await s.load();
  const key = Buffer.from(s.authKey.getKey()).toString("hex");
  console.log("dc", dc, "keylen", key.length);
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY }, args: ["--ignore-certificate-errors"] });
  const ctx = await b.newContext({ viewport: { width: 900, height: 1300 }, locale: "ru-RU" });
  await ctx.addInitScript(({ dc, key }) => {
    if (localStorage.getItem("user_auth")) return;
    localStorage.setItem("dc", String(dc));
    localStorage.setItem(`dc${dc}_auth_key`, JSON.stringify(key));
    localStorage.setItem("auth_key_fingerprint", JSON.stringify(key.slice(0, 8)));
    localStorage.setItem("user_auth", JSON.stringify({ dcID: dc, date: Math.floor(Date.now() / 1000), id: 6866284629 }));
  }, { dc, key });
  const p = await ctx.newPage();
  for (const [ch, id] of process.argv.slice(2).map(x => x.split("/"))) {
    await p.goto(`https://web.telegram.org/k/#?tgaddr=${encodeURIComponent(`tg://resolve?domain=${ch}&post=${id}`)}`, { waitUntil: "domcontentloaded", timeout: 60000 });
    await p.waitForTimeout(15000);
    await p.screenshot({ path: __dirname + `/webk_${ch}_${id}.png` });
    const hrefs = await p.$$eval(".reply-markup a, .reply-markup-button, a.reply-markup-button", as => as.map(a => (a.innerText || "").trim() + " -> " + (a.href || a.getAttribute("href") || a.dataset.url || "")));
    console.log("BUTTONS", JSON.stringify(hrefs));
    const anchors = await p.$$eval(".bubble a", as => [...new Set(as.map(a => a.href).filter(h => /t\.me|tg:/.test(h)))]);
    console.log("ANCHORS", JSON.stringify(anchors.slice(-25)));
    if (process.env.CLICK) {
      const btn = p.getByText(new RegExp("^" + process.env.CLICK + " \\(\\d+\\)$")).last();
      console.log("btn count", await btn.count(), "outer:", (await btn.evaluate(e => e.outerHTML).catch(() => "")).slice(0, 600));
      await btn.click().catch(e => console.log("click err", e.message));
      await p.waitForTimeout(5000);
      console.log("POPUP:", (await p.innerText(".popup").catch(() => "")).slice(0, 600));
      const ok = p.locator(".popup button", { hasText: /Launch|Open|Запустить|Открыть|Confirm|OK/i }).last();
      if (await ok.count()) { await ok.click().catch(e => console.log("ok err", e.message)); await p.waitForTimeout(12000); }
      const srcs = await p.$$eval("iframe", fs => fs.map(f => f.src));
      for (const u of srcs) { const m = decodeURIComponent(u).match(/tgWebAppStartParam=([^&#]+)/); console.log("IFRAME", u.split("#")[0].slice(0, 80), "start:", m && m[1]); }
      await p.screenshot({ path: __dirname + "/webk_click.png" });
    }
    console.log("==", ch, id, "\n", (await p.innerText(".bubbles").catch(() => "")).slice(-2500));
  }
  await b.close();
})();
