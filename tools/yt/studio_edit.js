// Edit title / description of ALREADY PUBLISHED videos in YouTube Studio (the pult browser, CDP), no re-upload.
// usage: node studio_edit.js cdp:9222 edits.json
// edits.json: [{"id": "VIDEO_ID", "title": "...", "description": "..."(optional), "thumb": "/path/cover.jpg"(optional), "shot": "/path/x.png"(optional)}]
// Only the given fields change. Prints EDITED <id> / FAIL <id> <reason>.
const fs = require('fs'); const { chromium } = require('playwright');
const [target, ef] = process.argv.slice(2);
const CDP = target.startsWith('cdp:') ? target.slice(4) : null;
if (!CDP) { console.error('use cdp:<port> (pult browser)'); process.exit(2); }
const edits = JSON.parse(fs.readFileSync(ef, 'utf8'));
async function setFiles(p, loc, file) {   // CDP file upload into the pult browser (same as studio_upload.js)
  const h = await loc.elementHandle(); await h.evaluate(el => { window.__upTarget = el; });
  const s = await p.context().newCDPSession(p);
  const { result } = await s.send('Runtime.evaluate', { expression: 'window.__upTarget' });
  await s.send('DOM.setFileInputFiles', { files: [require('path').resolve(file)], objectId: result.objectId }); await s.detach().catch(() => {});
}
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
(async () => {
  const b = await chromium.connectOverCDP(`http://127.0.0.1:${CDP}`);
  const p = b.contexts().flatMap(c => c.pages()).find(x => /studio\.youtube\.com/.test(x.url()));
  if (!p) throw new Error('NO_STUDIO_TAB in the pult browser');
  p.setDefaultTimeout(60000);
  p.on('dialog', d => d.accept().catch(() => {}));
  let fails = 0;
  for (const e of edits) {
    try {
      await p.goto(`https://studio.youtube.com/video/${e.id}/edit`, { waitUntil: 'domcontentloaded', timeout: 90000 });
      const title = p.locator('#title-textarea #textbox').first();
      await title.waitFor({ state: 'visible', timeout: 90000 });
      await p.waitForTimeout(1500);
      if (e.title) {
        if (e.title.length > 100) throw new Error('TITLE_TOO_LONG ' + e.title.length);
        await title.click(); await p.keyboard.press('Control+A'); await p.keyboard.press('Delete');
        await title.type(e.title, { delay: 2 });
      }
      if (e.description != null) {
        const d = p.locator('#description-textarea #textbox').first();
        await d.click(); await p.keyboard.press('Control+A'); await p.keyboard.press('Delete'); await d.fill(e.description);
      }
      if (e.thumb) {
        const ti = p.locator('#file-loader, ytcp-thumbnails-compact-editor-uploader input[type="file"], input[type="file"][accept*="image"]').first();
        await setFiles(p, ti, e.thumb); await p.waitForTimeout(5000);
      }
      const save = p.locator('ytcp-button#save, #save').first();
      await save.click();
      // saved = the save button becomes disabled again
      await p.waitForFunction(() => { const s = document.querySelector('ytcp-button#save, #save'); return s && (s.hasAttribute('disabled') || s.getAttribute('aria-disabled') === 'true'); }, null, { timeout: 60000 });
      await p.waitForTimeout(1500);
      const now = (await title.innerText()).trim();
      if (e.shot) await p.screenshot({ path: e.shot });
      if (e.title && now !== e.title.trim()) throw new Error('TITLE_MISMATCH «' + now + '»');
      log('EDITED', e.id, '«' + now + '»');
    } catch (err) { fails++; log('FAIL', e.id, String(err.message || err).slice(0, 200)); }
  }
  // do NOT b.close() on a CDP connection (may close the pult's browser) — just exit, like studio_upload.js
  process.exit(fails ? 1 : 0);
})();
