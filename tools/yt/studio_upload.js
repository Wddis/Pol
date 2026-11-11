// Upload a video through the YouTube Studio web UI (Playwright), logged in with cookies.txt.
// No API, no quota. usage: node studio_upload.js cookies.txt meta.json
// meta: {channel?, video, title, description, thumb?, tags?[], visibility: PUBLIC|UNLISTED|PRIVATE, shot_dir?}
const fs = require('fs'); const { chromium } = require('playwright');
const [ck, metaPath] = process.argv.slice(2); const m = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
const shot = m.shot_dir || '.';
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
  const p = await ctx.newPage(); p.setDefaultTimeout(60000);
  try {
    // m.channel: target channel id (needed for an editor account, otherwise /upload opens its own channel)
    const upUrl = m.channel ? `https://studio.youtube.com/channel/${m.channel}/videos/upload?d=ud` : 'https://www.youtube.com/upload';
    await p.goto(upUrl, { waitUntil: 'domcontentloaded', timeout: 90000 });
    await p.waitForURL(/studio\.youtube\.com\/channel/, { timeout: 90000 });
    if (m.channel && !p.url().includes(m.channel)) throw new Error('WRONG_CHANNEL ' + p.url());
    log('studio', p.url());
    const fileInput = p.locator('input[type="file"]').first();
    await fileInput.waitFor({ state: 'attached' });
    await fileInput.setInputFiles(m.video); log('file selected');
    // title
    const title = p.locator('#title-textarea #textbox, ytcp-social-suggestions-textbox#title-textarea #textbox').first();
    await title.waitFor({ state: 'visible', timeout: 120000 });
    await title.click(); await p.keyboard.press('Control+A'); await p.keyboard.press('Delete');
    await title.type(m.title, { delay: 2 }); log('title set');
    const desc = p.locator('#description-textarea #textbox, ytcp-social-suggestions-textbox#description-textarea #textbox').first();
    await desc.click(); await desc.fill(m.description || ''); log('description set');
    if (m.thumb) {
      const ti = p.locator('#file-loader, ytcp-thumbnails-compact-editor-uploader input[type="file"], input[type="file"][accept*="image"]').first();
      await ti.setInputFiles(m.thumb); log('thumbnail set'); await p.waitForTimeout(3000);
    }
    await p.locator('tp-yt-paper-radio-button[name="VIDEO_MADE_FOR_KIDS_NOT_MFK"]').click(); log('not for kids');
    if (m.tags && m.tags.length) {
      try {
        await p.locator('#toggle-button').click(); await p.waitForTimeout(1500);
        const ti = p.locator('#tags-container input#text-input, ytcp-form-input-container#tags-container input').first();
        await ti.click(); await ti.fill(m.tags.join(',') + ','); log('tags set');
      } catch (e) { log('tags skipped', e.message.slice(0, 80)); }
    }
    await p.screenshot({ path: `${shot}/step1.png` });
    for (let i = 0; i < 3; i++) { await p.locator('#next-button').click(); await p.waitForTimeout(1500); }
    await p.locator(`tp-yt-paper-radio-button[name="${m.visibility || 'PUBLIC'}"]`).click(); log('visibility', m.visibility || 'PUBLIC');
    // wait for upload to finish (progress label / done button enabled)
    const done = p.locator('#done-button');
    const t0 = Date.now();
    while (true) {
      const prog = await p.locator('ytcp-video-upload-progress .progress-label, .ytcp-video-upload-progress').first().innerText().catch(() => '');
      if (prog) log('progress:', prog.replace(/\s+/g, ' ').slice(0, 120));
      if (/Загрузка завершена|Обработка|Проверка|завершена|Upload complete|Processing|Checks/i.test(prog) && !(/Загрузка \d|Uploading \d/i.test(prog))) break;
      if (Date.now() - t0 > 3600e3) throw new Error('upload timeout');
      await p.waitForTimeout(15000);
    }
    await p.screenshot({ path: `${shot}/step2.png` });
    const link = await p.locator('a.style-scope.ytcp-video-info, ytcp-video-info a').first().getAttribute('href').catch(() => null);
    await done.click(); log('published click');
    await p.waitForTimeout(8000); await p.screenshot({ path: `${shot}/step3.png` });
    console.log('VIDEO_LINK', link);
  } catch (e) {
    console.log('ERROR', e.message.slice(0, 400)); await p.screenshot({ path: `${shot}/error.png` }).catch(() => {});
    process.exitCode = 1;
  }
  await b.close();
})();
