// List Content ID claims of a video (song, artist, impact) via the pult browser (CDP).
// usage: node studio_claims.js cdp:9222 VIDEO_ID   -> prints the Claims table; for each blocking claim: Take action → Erase song
// (owner decision 18.11: blocking music claims → «Erase song» = remove the song, keep speech; permanent, done in Studio).
const { chromium } = require('playwright');
const [t, id] = process.argv.slice(2);
(async () => {
  const b = await chromium.connectOverCDP(`http://127.0.0.1:${t.slice(4)}`);
  const p = b.contexts().flatMap(c => c.pages()).find(x => /studio\.youtube\.com/.test(x.url()));
  await p.goto(`https://studio.youtube.com/video/${id}/copyright`, { waitUntil: 'domcontentloaded' }); await p.waitForTimeout(8000);
  const s = await p.evaluate(() => document.body.innerText);
  const i = s.indexOf('Claim overview'); console.log(s.slice(i, i + 1500).replace(/\n{2,}/g, '\n'));
  process.exit(0);
})();
