// Open a web page in headless Chromium for the bot's agent: screenshot + visible text (like a human looking at it).
// usage: node browse.js URL out.png [--full] [--wait 3000] [--click "text"] [--type "selector=>text"]
// prints JSON {url, title, text (first 6000 chars), shot}
const { chromium } = require('playwright');
const a = process.argv.slice(2); const url = a[0], out = a[1];
const opt = k => { const i = a.indexOf(k); return i > 0 ? a[i + 1] : null; };
(async () => {
  const args = ['--disable-blink-features=AutomationControlled'];
  const launch = { args };
  if (process.env.HTTPS_PROXY) { launch.proxy = { server: process.env.HTTPS_PROXY }; }
  const b = await chromium.launch(launch);
  const ctx = await b.newContext({ viewport: { width: 1600, height: 900 }, locale: 'ru-RU', ignoreHTTPSErrors: !!process.env.HTTPS_PROXY,
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36' });
  const p = await ctx.newPage();
  let err = '';
  try {
    await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await p.waitForTimeout(+(opt('--wait') || 3500));
    // cookie / consent walls: accept if present (YouTube/Google «Accept all», «Принять все»)
    for (const t of [/Accept all/i, /Принять все/i, /Reject all/i, /Отклонить все/i, /I agree/i]) {
      const btn = p.getByRole('button', { name: t }).first();
      if (await btn.count() && await btn.isVisible().catch(() => false)) { await btn.click().catch(() => {}); await p.waitForTimeout(2500); break; }
    }
    if (opt('--click')) { await p.getByText(opt('--click')).first().click().catch(e => err += 'click: ' + e.message); await p.waitForTimeout(2500); }
    if (opt('--type')) { const [sel, txt] = opt('--type').split('=>'); await p.fill(sel, txt).catch(e => err += 'type: ' + e.message); await p.keyboard.press('Enter'); await p.waitForTimeout(3500); }
  } catch (e) { err += e.message.split('\n')[0]; }
  await p.screenshot({ path: out, fullPage: a.includes('--full') }).catch(e => err += ' shot: ' + e.message);
  const text = (await p.evaluate(() => document.body ? document.body.innerText : '').catch(() => '')).replace(/\n{3,}/g, '\n\n').slice(0, 6000);
  console.log(JSON.stringify({ url: p.url(), title: await p.title().catch(() => ''), text, shot: out, error: err }));
  await b.close(); process.exit(0);
})();
