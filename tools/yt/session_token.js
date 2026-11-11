// Get YouTube Studio sessionToken using cookies.txt (Netscape). Prints JSON {sessionToken}.
// usage: node session_token.js cookies.txt
const fs = require('fs'); const { chromium } = require('playwright');
const lines = fs.readFileSync(process.argv[2], 'utf8').split('\n');
const cookies = [];
for (let l of lines) {
  let httpOnly = false;
  if (l.startsWith('#HttpOnly_')) { httpOnly = true; l = l.slice(10); } else if (l.startsWith('#') || !l.trim()) continue;
  const f = l.split('\t'); if (f.length < 7) continue;
  const [domain, , path, secure, exp, name, value] = f;
  if (!/youtube\.com|google\.com/.test(domain)) continue;
  const c = { name, value: value.trim(), domain, path, secure: secure === 'TRUE', httpOnly };
  if (+exp > 0) c.expires = +exp;
  cookies.push(c);
}
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext();
  await ctx.addCookies(cookies);
  const p = await ctx.newPage();
  const tok = p.waitForResponse(r => r.url().includes('/youtubei/v1/ars/grst'), { timeout: 90000 });
  await p.goto('https://studio.youtube.com/', { timeout: 90000 });
  if (!p.url().includes('studio.youtube.com/channel')) {
    await p.goto('https://www.youtube.com/upload', { timeout: 90000 });
  }
  if (!p.url().includes('studio.youtube.com/channel')) { console.log(JSON.stringify({ error: 'not logged in', url: p.url() })); await b.close(); process.exit(2); }
  const r = await tok; const j = await r.json();
  console.log(JSON.stringify({ sessionToken: j.sessionToken, url: p.url() }));
  await b.close();
})().catch(e => { console.log(JSON.stringify({ error: e.message.slice(0, 300) })); process.exit(1); });
