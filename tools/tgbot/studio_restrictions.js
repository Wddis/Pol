// Lists restrictions (18+, Content ID, ...) of the channel's videos and shorts from Studio (panel on CDP 9222).
// usage: node studio_restrictions.js CHANNEL_ID   — uses the existing Studio tab (a new tab may land on sign-in).
const { chromium } = require('playwright');
(async () => {
  const ch = process.argv[2]; const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
  const p = b.contexts().flatMap(c => c.pages()).find(x => /studio\.youtube\.com/.test(x.url()));
  if (!p) { console.log('Studio tab not found (session?)'); process.exit(1); }
  for (const [tab, name] of [['videos/upload', 'ВИДЕО'], ['videos/short', 'SHORTS']]) {
    await p.goto(`https://studio.youtube.com/channel/${ch}/${tab}`); await p.waitForTimeout(8000);
    const rows = await p.locator('ytcp-video-row').evaluateAll(rs => rs.map(r => {
      const t = (r.querySelector('#video-title') || {}).innerText || '';
      const all = r.innerText.replace(/\s+/g, ' ');
      const m = all.match(/(Доступно зрителям старше 18|Доступно з[^ ]*|Потенциальный доход ограничен|Потенциаль[^ ]*|Ограничения[^|]*|Заблокировано[^|]*)/);
      return (m ? '⚠ ' + m[0] : '✓') + ' — ' + t.slice(0, 70);
    }));
    console.log(`== ${name}`); console.log(rows.slice(0, 12).join('\n'));
  }
  await p.goto(`https://studio.youtube.com/channel/${ch}/videos/upload?d=ud`); process.exit(0);
})();
