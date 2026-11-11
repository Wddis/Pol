// Finish publishing an existing Studio draft by title (Playwright + cookies.txt).
// usage: node studio_publish_draft.js cookies.txt "title substring" PUBLIC shot_dir
const fs = require('fs'); const { chromium } = require('playwright');
const [ck, titleSub, vis = 'PUBLIC', shot = '.'] = process.argv.slice(2);
const cookies = [];
for (let l of fs.readFileSync(ck, 'utf8').split('\n')) {
  let h = false; if (l.startsWith('#HttpOnly_')) { h = true; l = l.slice(10); } else if (l.startsWith('#') || !l.trim()) continue;
  const f = l.split('\t'); if (f.length < 7) continue;
  const c = { name: f[5], value: f[6].trim(), domain: f[0], path: f[2], secure: f[3] === 'TRUE', httpOnly: h };
  if (+f[4] > 0) c.expires = +f[4]; cookies.push(c);
}
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 }, locale: 'ru-RU', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36' });
  await ctx.addCookies(cookies);
  const p = await ctx.newPage(); p.setDefaultTimeout(45000);
  const reauth = async () => (await p.getByText('Подтверждение личности').count()) > 0;
  try {
    await p.goto('https://studio.youtube.com/', { waitUntil: 'domcontentloaded', timeout: 90000 });
    await p.waitForURL(/studio\.youtube\.com\/channel\/([^/]+)/, { timeout: 90000 });
    const ch = p.url().match(/channel\/([^/?]+)/)[1];
    await p.goto(`https://studio.youtube.com/channel/${ch}/videos/upload`, { waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(6000);
    const row = p.locator('ytcp-video-row').filter({ hasText: titleSub }).first();
    await row.waitFor();
    log('draft row found');
    const editBtn = row.getByText(/Изменить черновик|Edit draft/i).first();
    await editBtn.click(); log('opened draft');
    await p.waitForTimeout(4000);
    if (await reauth()) throw new Error('REAUTH_REQUIRED');
    const nmfk = p.locator('tp-yt-paper-radio-button[name="VIDEO_MADE_FOR_KIDS_NOT_MFK"]');
    if (await nmfk.count()) {
      const checked = await nmfk.getAttribute('aria-checked');
      if (checked !== 'true') { await nmfk.click(); log('set not for kids'); } else log('not-for-kids already set');
    }
    await p.waitForTimeout(1500); if (await reauth()) throw new Error('REAUTH_REQUIRED');
    for (let i = 0; i < 3; i++) { await p.locator('#next-button').click(); await p.waitForTimeout(2000); if (await reauth()) throw new Error('REAUTH_REQUIRED'); }
    await p.locator(`tp-yt-paper-radio-button[name="${vis}"]`).click(); log('visibility', vis);
    await p.screenshot({ path: `${shot}/pub1.png` });
    await p.locator('#done-button').click(); log('publish clicked');
    await p.waitForTimeout(8000);
    if (await reauth()) throw new Error('REAUTH_REQUIRED');
    await p.screenshot({ path: `${shot}/pub2.png` });
    const link = await p.locator('a[href*="youtu.be"], a[href*="youtube.com/watch"]').first().getAttribute('href').catch(() => null);
    console.log('RESULT_LINK', link);
  } catch (e) { console.log('ERROR', e.message.slice(0, 300)); await p.screenshot({ path: `${shot}/pub_error.png` }).catch(() => {}); process.exitCode = 1; }
  await b.close();
})();
