// Erase claimed songs that BLOCK a video (owner rule 18.11: «глуши музыку» → Erase song, speech kept). Pult browser via CDP.
// usage: node studio_erase.js cdp:9222 VIDEO_ID [--all]   (--all: also claims with «No impact»; default only «Blocking…»)
// prints: CLAIM <song> | <impact>  then  ERASED <song> / SKIP / NONE / CHECKS_PENDING / LOGIN_NEEDED
const { chromium } = require('playwright');
const [t, id, flag] = process.argv.slice(2); const ALL = flag === '--all';
(async () => {
  const b = await chromium.connectOverCDP(`http://127.0.0.1:${t.slice(4)}`);
  const p = b.contexts().flatMap(c => c.pages()).find(x => /youtube\.com|google\.com/.test(x.url())) || b.contexts()[0].pages()[0];
  p.setDefaultTimeout(60000); p.on('dialog', d => d.accept().catch(() => {}));
  const open = async () => { await p.goto(`https://studio.youtube.com/video/${id}/copyright`, { waitUntil: 'domcontentloaded' }); await p.waitForTimeout(8000); };
  await open();
  if (/accounts\.google\.com/.test(p.url()) || /Verify it/.test(await p.evaluate(() => document.body.innerText))) { console.log('LOGIN_NEEDED'); process.exit(3); }
  const body = await p.evaluate(() => document.body.innerText);
  if (/Waiting for checks|Checks in progress/i.test(body)) { console.log('CHECKS_PENDING'); process.exit(0); }
  if (/No copyrighted content/i.test(body)) { console.log('NONE'); process.exit(0); }
  // rows: title + impact text, button «Take action» on the same row
  const rows = await p.$$eval('ytcr-video-content-list-row, ytcr-video-content-row, [class*="content-row"]', rs => rs.map(r => r.innerText.replace(/\s+/g, ' ')));
  const n = await p.getByRole('button', { name: /Take action/i }).count();
  let erased = 0;
  for (let i = 0; i < n; i++) {
    await open();
    const btn = p.getByRole('button', { name: /Take action/i }).nth(i);
    const row = await btn.evaluate(el => { let x = el; for (let k = 0; k < 8 && x; k++) { x = x.parentElement; if (x && x.innerText && x.innerText.includes('Copyright')) return x.innerText.replace(/\s+/g, ' '); } return ''; });
    console.log('CLAIM', row.slice(0, 160));
    if (!ALL && !/Block/i.test(row)) { console.log('SKIP'); continue; }
    if (await btn.isDisabled().catch(() => false)) { console.log('SKIP disabled (edit in progress?)'); continue; }
    await btn.click(); await p.waitForTimeout(3000);
    const er = p.getByText('Erase song', { exact: true }).first();
    if (!(await er.count())) { console.log('SKIP no Erase song option'); await p.keyboard.press('Escape'); continue; }
    await er.click(); await p.waitForTimeout(2000);
    for (const name of [/^Continue$/i, /^Select$/i, /^Next$/i]) { const c = p.getByRole('button', { name }); if (await c.count() && await c.first().isVisible()) { await c.first().click(); await p.waitForTimeout(4000); break; } }
    await p.getByRole('button', { name: /^Save$/ }).last().click(); await p.waitForTimeout(3000);
    await p.getByText(/I acknowledge that these changes are permanent/).click(); await p.waitForTimeout(800);
    await p.getByRole('button', { name: /Confirm changes/ }).click(); await p.waitForTimeout(6000);
    console.log('ERASED', row.slice(0, 80)); erased++;
    break;                     // Studio locks editing while one edit is processed — the next claim on the next run
  }
  if (!erased) console.log('NOTHING_TO_ERASE');
  process.exit(0);
})();
