// Save the cookies of the running pult browser (after the owner passed "Подтверждение личности")
// to a Netscape cookies.txt, so the next uploads/pult runs start from the verified session.
// usage: node export_cookies.js out_cookies.txt [cdp_port=9222]
// Output contains live session secrets: keep it in the session scratchpad only, NEVER in the repo.
const fs = require('fs'); const { chromium } = require('playwright');
const [out, CDP = '9222'] = process.argv.slice(2);
(async () => {
  const b = await chromium.connectOverCDP(`http://127.0.0.1:${CDP}`);
    // the pult uses its own context (newContext), not the default one — take the one with Studio open
  const ctx = b.contexts().find(c => c.pages().some(p => /youtube\.com|google\.com/.test(p.url()))) || b.contexts()[0];
  // ctx.cookies() over a foreign CDP connection can come back empty — ask the page's own CDP session
  let cs = await ctx.cookies();
  if (!cs.length) {
    const pg = ctx.pages().find(p => /youtube\.com|google\.com/.test(p.url())) || ctx.pages()[0];
    const s = await ctx.newCDPSession(pg);
    cs = (await s.send('Network.getAllCookies')).cookies;
  }
  const lines = ['# Netscape HTTP Cookie File', ''];
  for (const c of cs) {
    const dom = c.domain; const sub = dom.startsWith('.') ? 'TRUE' : 'FALSE';
    lines.push(`${c.httpOnly ? '#HttpOnly_' : ''}${dom}\t${sub}\t${c.path}\t${c.secure ? 'TRUE' : 'FALSE'}\t${c.expires > 0 ? Math.floor(c.expires) : 0}\t${c.name}\t${c.value}`);
  }
  fs.writeFileSync(out, lines.join('\n') + '\n', { mode: 0o600 });
  console.log(`saved ${cs.length} cookies -> ${out}`); process.exit(0);
})();
