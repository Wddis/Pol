// Read the "Notices" box (18+ / limited ads / copyright) of published videos in YouTube Studio via the pult (CDP).
// usage: node studio_notices.js cdp:9222 ID [ID ...]   -> one line per video: ID | title | notices
const { chromium } = require('playwright');
const [target, ...ids] = process.argv.slice(2); const CDP = target.slice(4);
(async () => {
  const b = await chromium.connectOverCDP(`http://127.0.0.1:${CDP}`);
  const p = b.contexts().flatMap(c => c.pages()).find(x => /studio\.youtube\.com/.test(x.url()));
  if (!p) throw new Error('NO_STUDIO_TAB');
  for (const id of ids) {
    try {
      await p.goto(`https://studio.youtube.com/video/${id}/edit`, { waitUntil: 'domcontentloaded', timeout: 90000 });
      const t = p.locator('#title-textarea #textbox').first(); await t.waitFor({ state: 'visible', timeout: 90000 });
      await p.waitForTimeout(3000);
      const title = (await t.innerText()).trim();
      const notices = await p.evaluate(() => {
        const box = [...document.querySelectorAll('ytcp-video-metadata-notices, #notices, ytcp-video-info')].map(e => e.innerText).join(' | ');
        return box.replace(/\s+/g, ' ').trim();
      });
      const m = notices.match(/(Notices|Уведомления)(.*?)(Visibility|Доступ|Video link|Ссылка|$)/);
      console.log(`${id} | ${title.slice(0, 60)} | ${m ? m[2].trim() : (notices.slice(0, 160) || 'нет уведомлений')}`);
    } catch (e) { console.log(`${id} | FAIL ${String(e.message).slice(0, 120)}`); }
  }
  process.exit(0);
})();
