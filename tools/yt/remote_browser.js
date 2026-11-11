// Remote "control panel" for a cloud Chromium: the channel owner sees the live page and clicks/types
// into it themselves (e.g. to pass Google's "Подтверждение личности"). Claude never types credentials.
// usage: node remote_browser.js cookies.txt [port=8765] [cdp_port=9222] [start_url]
// - open http://localhost:<port>/?t=<token> (token is printed on start) via the app's port preview;
// - upload scripts can attach to the same logged-in browser: chromium.connectOverCDP('http://127.0.0.1:<cdp_port>').
const fs = require('fs'); const http = require('http'); 
const { chromium } = require('playwright');
const [ck, PORT = '8765', CDP = '9222', START = 'https://studio.youtube.com/'] = process.argv.slice(2);
const TOKEN = process.env.RB_TOKEN || ''; // optional: require ?t=<token>
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
body{margin:0;background:#111;color:#eee;font:14px system-ui}#bar{display:flex;gap:6px;padding:6px;flex-wrap:wrap;position:sticky;top:0;background:#222}
#bar input{flex:1;min-width:140px;padding:8px;font-size:16px}button{padding:8px 10px;font-size:15px}
#wrap{overflow:auto}#s{width:100%;max-width:${W}px;display:block;touch-action:manipulation}#st{padding:4px 8px;color:#aaa}
</style></head><body><div id="bar"><input id="tx" placeholder="текст → Отправить (пароль/код)" autocomplete="off">
<button onclick="send('type',{text:tx.value});tx.value=''">Отправить</button><button onclick="send('key',{key:'Enter'})">Enter</button>
<button onclick="send('key',{key:'Backspace'})">⌫</button><button onclick="send('key',{key:'Tab'})">Tab</button>
<button onclick="send('scroll',{dy:-500})">▲</button><button onclick="send('scroll',{dy:500})">▼</button>
<button onclick="send('back',{})">Назад</button></div><div id="st"></div><div id="wrap"><img id="s"></div><script>
const T=new URLSearchParams(location.search).get('t')||'';const s=document.getElementById('s'),st=document.getElementById('st');
function send(a,b){return fetch('/'+a+'?t='+T,{method:'POST',body:JSON.stringify(b)}).then(()=>setTimeout(r,250))}
s.onclick=e=>{const k=${W}/s.clientWidth;send('click',{x:e.offsetX*k,y:e.offsetY*k})};
let busy=false;function r(){if(busy)return;busy=true;const i=new Image();i.onload=()=>{s.src=i.src;busy=false};i.onerror=()=>busy=false;i.src='/shot?t='+T+'&'+Date.now();
fetch('/url?t='+T).then(x=>x.text()).then(u=>st.textContent=u)}setInterval(r,1200);r();
</script></body></html>`;
(async () => {
  const b = await chromium.launch({ args: [`--remote-debugging-port=${CDP}`] });
  const ctx = await b.newContext({ viewport: { width: W, height: H }, locale: 'ru-RU',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36' });
  await ctx.addCookies(cookies);
  let p = await ctx.newPage(); await p.goto(START, { waitUntil: 'domcontentloaded' }).catch(() => {});
  ctx.on('page', np => { p = np; }); // follow newly opened tabs/popups
  const cur = () => { const ps = ctx.pages(); return ps[ps.length - 1] || p; };
  http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    if (TOKEN && u.searchParams.get('t') !== TOKEN) { res.writeHead(403); return res.end('forbidden'); }
    try {
      if (u.pathname === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(PAGE); }
      if (u.pathname === '/shot') { const img = await cur().screenshot({ type: 'jpeg', quality: 60 }); res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' }); return res.end(img); }
      if (u.pathname === '/url') { res.writeHead(200); return res.end(cur().url()); }
      let body = ''; for await (const c of req) body += c; const a = body ? JSON.parse(body) : {};
      const pg = cur();
      if (u.pathname === '/click') await pg.mouse.click(a.x, a.y);
      else if (u.pathname === '/type') await pg.keyboard.type(a.text || '', { delay: 30 });
      else if (u.pathname === '/key') await pg.keyboard.press(a.key);
      else if (u.pathname === '/scroll') await pg.mouse.wheel(0, a.dy || 0);
      else if (u.pathname === '/back') await pg.goBack().catch(() => {});
      res.writeHead(204); res.end();
    } catch (e) { res.writeHead(500); res.end(String(e).slice(0, 200)); }
  }).listen(+PORT, '0.0.0.0', () => console.log(`REMOTE http://localhost:${PORT}/${TOKEN ? '?t=' + TOKEN : ''}  CDP http://127.0.0.1:${CDP}`));
})();
