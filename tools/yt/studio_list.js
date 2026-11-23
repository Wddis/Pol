// List the channel's videos from YouTube Studio (pult browser via CDP): one line per video «ID | duration | title».
// usage: node studio_list.js cdp:9222 [CHANNEL_ID] [--shorts]   (default tab: long videos; --shorts: the Shorts tab)
const { chromium } = require('playwright');
const [t, ch = 'UC4R6TINYFRWa_80Vewy068g', flag] = process.argv.slice(2);
(async () => {
  const b = await chromium.connectOverCDP(`http://127.0.0.1:${t.slice(4)}`);
  const p = b.contexts().flatMap(c => c.pages()).find(x => /youtube\.com|google\.com/.test(x.url())) || b.contexts()[0].pages()[0];
  p.on('dialog', d => d.accept().catch(() => {}));
  await p.goto(`https://studio.youtube.com/channel/${ch}/videos/${flag === '--shorts' ? 'short' : 'upload'}`, { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(9000);
  if (/accounts\.google\.com/.test(p.url())) { console.log('LOGIN_NEEDED'); process.exit(3); }
  const rows = await p.$$eval('ytcp-video-row', rs => rs.map(r => {
    const a = r.querySelector('a[href*="/video/"]'); const id = a ? (a.getAttribute('href').match(/video\/([A-Za-z0-9_-]{11})/) || [])[1] : '';
    const title = (r.querySelector('#video-title') || {}).innerText || '';
    const dur = (r.querySelector('.video-duration, ytcp-video-duration, #overlay-duration') || {}).innerText || '';
    return `${id} | ${dur.trim()} | ${title.trim().replace(/\s+/g, ' ')}`;
  }));
  console.log(rows.join('\n')); process.exit(0);
})();
