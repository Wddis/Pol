// Remote "control panel" for a cloud Chromium: the channel owner sees the live page and clicks/types
// into it themselves (e.g. to pass Google's "Подтверждение личности"). Claude never types credentials.
// usage: node remote_browser.js cookies.txt [port=8765] [cdp_port=9222] [start_url]
// - open http://localhost:<port>/?t=<token> (RB_TOKEN) — exposed to the owner's phone via a tunnel;
// - upload scripts attach to the same logged-in browser: chromium.connectOverCDP('http://127.0.0.1:<cdp_port>').
const fs = require('fs'); const http = require('http');
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
<button class="p" onclick="send('fill',{text:tx.value})">Вставить в поле</button>
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
(async () => {
  const b = await chromium.launch({ args: [`--remote-debugging-port=${CDP}`] });
  const ctx = await b.newContext({ viewport: { width: W, height: H }, locale: 'ru-RU',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36' });
  await ctx.addCookies(cookies);
  let p = await ctx.newPage(); await p.goto(START, { waitUntil: 'domcontentloaded' }).catch(() => {});
  ctx.on('page', np => { p = np; });
  // When a Google sign-in/challenge tab is open, pin to it (both screenshot and clicks),
  // so the two tabs can't swap under the owner between /shot and /click.
  const cur = () => { const ps = ctx.pages(); return ps.find(x => /accounts\.google\.com|gds\.google\.com/.test(x.url())) || ps[ps.length - 1] || p; };
  // Fill the field the owner means: prefer a visible password field, else the focused/last visible text-like input.
  const fillField = async (pg, text) => {
    const sels = ['input[type="password"]', 'input[type="tel"]', 'input[type="email"]', 'input[autocomplete="one-time-code"]', 'input[type="text"]:not([readonly])'];
    for (const sel of sels) {
      const loc = pg.locator(sel + ':visible');
      const n = await loc.count();
      if (n) {
        const el = loc.nth(n - 1);
        await el.click({ timeout: 3000 }).catch(() => {});
        // clear, then type with REAL key events — Google forms reject programmatically-set values
        await el.fill('', { timeout: 3000 }).catch(() => {});
        await pg.keyboard.type(text, { delay: 45 });
        return 'введено в ' + sel + ' (' + text.length + ' симв.)';
      }
    }
    await pg.keyboard.type(text, { delay: 45 }); return 'поле не найдено — напечатано в фокус';
  };
  http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    if (TOKEN && u.searchParams.get('t') !== TOKEN) { res.writeHead(403); return res.end('forbidden'); }
    try {
      if (u.pathname === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(PAGE); }
      if (u.pathname === '/shot') { const img = await cur().screenshot({ type: 'jpeg', quality: 55 }); res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' }); return res.end(img); }
      let body = ''; for await (const c of req) body += c; const a = body ? JSON.parse(body) : {};
      const pg = cur(); let msg = '';
      if (u.pathname === '/click') await pg.mouse.click(a.x, a.y);
      else if (u.pathname === '/fill') msg = await fillField(pg, a.text || '');
      else if (u.pathname === '/type') await pg.keyboard.type(a.text || '', { delay: 25 });
      else if (u.pathname === '/key') await pg.keyboard.press(a.key);
      else if (u.pathname === '/scroll') await pg.mouse.wheel(0, a.dy || 0);
      else if (u.pathname === '/back') await pg.goBack().catch(() => {});
      res.writeHead(200); res.end(msg);
    } catch (e) { res.writeHead(200); res.end(String(e.message || e).slice(0, 160)); }
  }).listen(+PORT, '0.0.0.0', () => console.log(`REMOTE http://localhost:${PORT}/${TOKEN ? '?t=' + TOKEN : ''}  CDP http://127.0.0.1:${CDP}`));
})();
