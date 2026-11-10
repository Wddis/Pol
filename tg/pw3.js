const { chromium } = require("playwright");
const fs = require("fs");
(async () => {
  const url = fs.readFileSync(__dirname + "/webapp_url.txt", "utf8").trim();
  const tag = process.argv[2] || "x";
  const b = await chromium.launch({ proxy: { server: process.env.HTTPS_PROXY }, args: ["--ignore-certificate-errors"] });
  const p = await b.newPage({ viewport: { width: 390, height: 844 }, userAgent: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36 Telegram-Android/12.1.0" });
  p.on("response", async r => { const m = r.request().method(); if (m !== "GET" && m !== "OPTIONS") { let t = ""; try { t = (await r.text()).slice(0, 250); } catch {} console.log("RES", m, r.status(), r.url().replace(/\?.*/, "").slice(0, 90), t.replace(/\s+/g, " ")); } });
  await p.goto(url, { waitUntil: "networkidle", timeout: 60000 }).catch(e => console.log("goto", e.message));
  await p.waitForTimeout(5000);
  console.log("TEXT1:", (await p.innerText("body").catch(() => "")).replace(/\s+/g, " ").slice(0, 3000));
  if (process.argv[3] === "click") {
    const btn = p.getByText(/^(Участв|Принять участие|Мне повез|Хочу|Участвую)/i).first();
    if (await btn.count()) { console.log("clicking:", await btn.innerText()); await btn.click().catch(e => console.log("click err", e.message)); await p.waitForTimeout(6000);
      console.log("TEXT2:", (await p.innerText("body").catch(() => "")).replace(/\s+/g, " ").slice(0, 3000)); }
    else console.log("no participate button found");
  }
  await p.screenshot({ path: __dirname + `/shot_${tag}.png` });
  await b.close();
})();
