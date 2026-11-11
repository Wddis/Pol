// Remote "control panel" for a cloud Chromium: the channel owner sees the live page and clicks/types
// into it themselves (e.g. to pass Google's "Подтверждение личности"). Claude never types credentials.
// usage: node remote_browser.js cookies.txt [port=8765] [cdp_port=9222] [start_url]
// - open http://localhost:<port>/?t=<token> (RB_TOKEN) — exposed to the owner's phone via a tunnel;
// - upload scripts attach to the same logged-in browser: chromium.connectOverCDP('http://127.0.0.1:<cdp_port>').
const fs = require('fs'); const http = require('http'); const { spawn } = require('child_process');
const { chromium } = require('playwright');
const [ck, PORT = '8765', CDP = '9222', START = 'https://studio.youtube.com/'] = process.argv.slice(2);
const TOKEN = process.env.RB_TOKEN || '';
const cookies = [];
for (let l of fs.readFileSync(ck, 'utf8').split('\n')) {
  let h = false; if (l.startsWith('#HttpOnly_')) { h = true; l = l.slice(10); } else if (l.startsWith('#') || !l.trim()) continue;
  const f = l.split('\t'); if (f.length < 7) continue;
  const c = { name: f[5], value: f[6].trim(), domain: f[0], path: f[2], secure: f[3] === 'TRUE', httpOnly: h };
  if (+f[4] > 0) c.expires = +f[4]; cookies.push(c);
}
const W = 1400, H = 1000;
const PAGE = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Remote browser</title><style>
body{margin:0;background:#111;color:#eee;font:14px system-ui}#bar{display:flex;gap:6px;padding:6px;flex-wrap:wrap;position:sticky;top:0;background:#222;z-index:2}
#bar input{flex:1;min-width:140px;padding:9px;font-size:16px}button{padding:9px 11px;font-size:15px}
#wrap{overflow:auto}#s{width:100%;max-width:${W}px;display:block;touch-action:manipulation}#st{padding:4px 8px;color:#9ab;font-size:12px;word-break:break-all}
.p{background:#2b6;color:#000;font-weight:600}
</style></head><body><div id="bar">
<input id="tx" placeholder="пароль / код сюда" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false">
<button class="p" onclick="send('fill',{text:tx.value});tx.value=''">Вставить в поле</button>
<button onclick="send('type',{text:tx.value});tx.value=''">Печатать</button>
<button onclick="send('key',{key:'Enter'})">Enter</button>
<button onclick="send('key',{key:'Backspace'})">⌫</button>
<button onclick="send('key',{key:'Tab'})">Tab</button>
<button onclick="send('scroll',{dy:-500})">▲</button>
<button onclick="send('scroll',{dy:500})">▼</button>
<button onclick="send('back',{})">Назад</button>
</div><div id="st"></div><div id="wrap"><img id="s"></div><script>
const T=new URLSearchParams(location.search).get('t')||'';const s=document.getElementById('s'),st=document.getElementById('st'),tx=document.getElementById('tx');
function send(a,b){return fetch('/'+a+'?t='+T,{method:'POST',body:JSON.stringify(b||{})}).then(x=>x.text()).then(m=>{if(m)st.textContent=m;setTimeout(r,250)}).catch(()=>{})}
s.onclick=e=>{const r2=s.getBoundingClientRect();send('click',{x:(e.clientX-r2.left)*${W}/r2.width,y:(e.clientY-r2.top)*${H}/r2.height})};
let busy=false;function r(){if(busy)return;busy=true;const i=new Image();i.onload=()=>{s.src=i.src;busy=false};i.onerror=()=>busy=false;i.src='/shot?t='+T+'&'+Date.now()}
setInterval(r,1200);r();
</script></body></html>`;
// Google's sign-in silently rejects automated browsers: after "Далее" the password field is cleared,
// the button greys out and nothing happens. The old launch was a giveaway on every signal —
// headless shell (sec-ch-ua "HeadlessChrome"), navigator.webdriver=true, and a Windows/Chrome 141 UA
// that contradicted the real Linux/Chrome 153 client hints. Now: a real (headed) Chromium on a virtual
// display, no automation flags, and the browser's own UA — so every signal agrees.
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function startDisplay() {
  if (process.env.DISPLAY || process.env.RB_HEADLESS === '1') return null;
  const bin = ['/usr/bin/Xvfb', '/usr/local/bin/Xvfb'].find(f => fs.existsSync(f));
  if (!bin) return null;
  // a previous pult killed with -9 can't clean up its Xvfb — do it now
  const PIDF = '/tmp/remote_browser_xvfb.pid';
  try { const [pid, d] = fs.readFileSync(PIDF, 'utf8').split(' '); process.kill(+pid, 'SIGKILL');
    for (const f of [`/tmp/.X${d}-lock`, `/tmp/.X11-unix/X${d}`]) fs.rmSync(f, { force: true }); } catch (e) {}
  for (let n = 90; n < 110; n++) {
    if (fs.existsSync(`/tmp/.X11-unix/X${n}`) || fs.existsSync(`/tmp/.X${n}-lock`)) continue;
    const x = spawn(bin, [`:${n}`, '-screen', '0', `${W}x${H + 120}x24`, '-nolisten', 'tcp'], { stdio: 'ignore' });
    for (let i = 0; i < 30 && x.exitCode === null && !fs.existsSync(`/tmp/.X11-unix/X${n}`); i++) await sleep(100);
    if (x.exitCode === null) { process.env.DISPLAY = `:${n}`; fs.writeFileSync(PIDF, `${x.pid} ${n}`); return x; }
  }
  return null;
}
(async () => {
  const xvfb = await startDisplay();
  const headed = !!process.env.DISPLAY && process.env.RB_HEADLESS !== '1';
  const b = await chromium.launch({
    headless: !headed,
    channel: headed ? undefined : 'chromium', // new headless = full Chromium, not the headless shell
    ignoreDefaultArgs: ['--enable-automation'],
    args: [`--remote-debugging-port=${CDP}`, '--disable-blink-features=AutomationControlled', '--lang=ru-RU',
      `--window-size=${W},${H + 120}`],
  });
  const kill = () => { try { xvfb && xvfb.kill('SIGKILL'); } catch (e) {} };
  process.on('exit', kill); for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { kill(); process.exit(0); });
  // Keep the browser's real version/platform; only drop the "Headless" word if we had to run headless.
  const ua0 = await (async () => { const t = await b.newPage(); const v = await t.evaluate(() => navigator.userAgent); await t.close(); return v; })();
  const ctx = await b.newContext({ viewport: { width: W, height: H }, locale: 'ru-RU', timezoneId: 'Europe/Moscow',
    userAgent: ua0.replace('HeadlessChrome/', 'Chrome/') });
  await ctx.addCookies(cookies);
  let p = await ctx.newPage(); await p.goto(START, { waitUntil: 'domcontentloaded' }).catch(() => {});
  ctx.on('page', np => { p = np; });
  // When a Google sign-in/challenge tab is open, pin to it (both screenshot and clicks),
  // so the two tabs can't swap under the owner between /shot and /click.
  // The newest such tab wins; it is brought to front so it has focus (Google checks it) and renders.
  let shown = null;
  const cur = async () => {
    const ps = ctx.pages();
    const g = ps.filter(x => /accounts\.google\.com|gds\.google\.com/.test(x.url()));
    const pg = g[g.length - 1] || ps[ps.length - 1] || p;
    if (pg !== shown) { shown = pg; await pg.bringToFront().catch(() => {}); }
    return pg;
  };
  // Fill the field the owner means: Google's password box first, then other credential/code inputs.
  // Looks in every frame, takes the FIRST visible editable match (Google keeps hidden decoy inputs),
  // clears and types with real key events, then reads the value back so the owner sees the truth.
  const SELS = ['input[name="Passwd"]', 'input[type="password"]', 'input[autocomplete="one-time-code"]', 'input[type="tel"]',
    'input[type="email"]', 'input[type="text"]', 'input:not([type])'];
  const fillField = async (pg, text) => {
    for (const sel of SELS) for (const fr of pg.frames()) {
      const loc = fr.locator(sel); const n = await loc.count().catch(() => 0);
      for (let i = 0; i < n; i++) {
        const el = loc.nth(i);
        if (!(await el.isVisible().catch(() => false)) || !(await el.isEditable().catch(() => false))) continue;
        await el.scrollIntoViewIfNeeded({ timeout: 2000 }).catch(() => {});
        await el.click({ timeout: 3000 }).catch(() => el.focus().catch(() => {}));
        await pg.keyboard.press('Control+A'); await pg.keyboard.press('Backspace');
        await pg.keyboard.type(text, { delay: 60 });
        const got = (await el.inputValue().catch(() => '')).length;
        return got === text.length
          ? `введено в ${sel} (${got} симв.) — теперь тапните «Далее»`
          : `ВНИМАНИЕ: в поле ${sel} ${got} симв. из ${text.length} — попробуйте ещё раз`;
      }
    }
    return 'поле ввода не найдено — сначала тапните по полю на картинке, потом «Печатать»';
  };
  http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    if (TOKEN && u.searchParams.get('t') !== TOKEN) { res.writeHead(403); return res.end('forbidden'); }
    try {
      if (u.pathname === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(PAGE); }
      if (u.pathname === '/shot') { const img = await (await cur()).screenshot({ type: 'jpeg', quality: 55 }); res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' }); return res.end(img); }
      let body = ''; for await (const c of req) body += c; const a = body ? JSON.parse(body) : {};
      const pg = await cur(); let msg = '';
      if (u.pathname === '/click') await pg.mouse.click(a.x, a.y);
      else if (u.pathname === '/fill') msg = await fillField(pg, a.text || '');
      else if (u.pathname === '/type') await pg.keyboard.type(a.text || '', { delay: 25 });
      else if (u.pathname === '/key') await pg.keyboard.press(a.key);
      else if (u.pathname === '/scroll') await pg.mouse.wheel(0, a.dy || 0);
      else if (u.pathname === '/back') await pg.goBack().catch(() => {});
      res.writeHead(200); res.end(msg);
    } catch (e) { res.writeHead(200); res.end(String(e.message || e).slice(0, 160)); }
  }).listen(+PORT, '0.0.0.0', () => console.log(`${headed ? 'HEADED on ' + process.env.DISPLAY : 'HEADLESS (no Xvfb)'}  UA ${ua0.replace('HeadlessChrome/', 'Chrome/')}\nREMOTE http://localhost:${PORT}/${TOKEN ? '?t=' + TOKEN : ''}  CDP http://127.0.0.1:${CDP}`));
})();
