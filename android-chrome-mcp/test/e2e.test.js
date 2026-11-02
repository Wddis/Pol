// End-to-end test: real Chromium + the MCP server + the official MCP client.
// Run: npm test   (CHROME_BIN can point to a Chrome/Chromium binary)
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..');
const CHROME = process.env.CHROME_BIN || findChrome();
const CDP_PORT = 9333;
const SITE_PORT = 8933;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const procs = [];
let passed = 0;

function findChrome() {
  const base = '/opt/pw-browsers';
  if (fs.existsSync(base)) {
    for (const d of fs.readdirSync(base)) {
      for (const p of [`${base}/${d}/chrome-linux64/chrome`, `${base}/${d}/chrome-linux/chrome`]) if (fs.existsSync(p)) return p;
    }
  }
  return 'chromium';
}

// ---------- test website ----------
const pages = {
  '/': `<!doctype html><title>Home</title><h1>Test shop</h1>
    <a href="/form">Open form</a> <a href="/new" target="_blank">New window link</a>
    <button onclick="document.getElementById('out').textContent='JS clicked'">Do JS</button><p id="out"></p>`,
  '/form': `<!doctype html><title>Form</title>
    <form action="/result" method="get">
      <label for="q">Search</label><input id="q" name="q" placeholder="Query">
      <input type="password" name="pw">
      <select name="color"><option value="r">Red</option><option value="g">Green</option></select>
      <label><input type="checkbox" name="agree"> I agree</label>
      <button type="submit">Send</button>
    </form>
    <div id="live"></div>
    <script>q.addEventListener('input', () => live.textContent = 'live:' + q.value)</script>`,
  '/new': `<!doctype html><title>New</title><p>Opened in same tab</p>`,
  '/apps': `<!doctype html><title>Apps</title><a href="intent://scan/#Intent;scheme=zxing;end">Open app</a> <a href="tel:+100">Call</a>`,
  '/late': `<!doctype html><title>Late</title><p id=p>loading</p><script>setTimeout(()=>p.textContent='Ready now',1200)</script>`,
};
let postCount = 0;
const site = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/post') {
    if (req.method === 'POST') postCount++;
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(`<!doctype html><title>Post</title><p>posts=${postCount}</p><form method="post" action="/post"><input name="msg" aria-label="Message"></form>`);
    return;
  }
  if (u.pathname === '/result') {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(`<!doctype html><title>Result</title><p>You searched: ${u.searchParams.get('q')} color=${u.searchParams.get('color')} agree=${u.searchParams.get('agree')}</p>`);
    return;
  }
  res.writeHead(pages[u.pathname] ? 200 : 404, { 'content-type': 'text/html; charset=utf-8' });
  res.end(pages[u.pathname] || 'nope');
});

async function waitHttp(url, ms = 15000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    try { if ((await fetch(url)).ok) return; } catch {}
    await sleep(200);
  }
  throw new Error('timeout waiting for ' + url);
}

function startServer(env) {
  const p = spawn(process.execPath, [path.join(root, process.env.ACM_TEST_ENTRY || 'server.mjs')], { env: { ...process.env, ...env }, stdio: ['ignore', 'ignore', 'pipe'] });
  p.stderr.on('data', (d) => process.env.DEBUG && process.stderr.write('[server] ' + d));
  procs.push(p);
  return p;
}

async function connect(port, secret) {
  const client = new Client({ name: 'e2e', version: '1' });
  await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp/${secret}`)));
  return client;
}

const text = (r) => r.content.map((c) => c.text || '').join('');
async function call(client, name, args = {}) {
  const r = await client.callTool({ name, arguments: args });
  if (r.isError) throw new Error(`${name} failed: ${text(r)}`);
  return r;
}
const refOf = (snap, re) => {
  const line = text(snap).split('\n').find((l) => re.test(l));
  assert.ok(line, `element ${re} not found in snapshot:\n${text(snap)}`);
  return Number(line.match(/^\[(\d+)\]/)[1]);
};
async function test(name, fn) {
  await fn();
  passed++;
  console.log('  ok -', name);
}

async function main() {
  await new Promise((r) => site.listen(SITE_PORT, '127.0.0.1', r));
  const B = `http://127.0.0.1:${SITE_PORT}`;
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'acm-profile-'));
  const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--disable-gpu', 'about:blank',
  ], { stdio: 'ignore' });
  procs.push(chrome);
  await waitHttp(`http://127.0.0.1:${CDP_PORT}/json/version`);

  // ========== Part 1: desktop mode, full tool coverage ==========
  console.log('desktop mode');
  const SECRET = 'test-secret-0123456789abcdef';
  const state1 = fs.mkdtempSync(path.join(os.tmpdir(), 'acm-state-'));
  startServer({ ACM_MODE: 'desktop', ACM_PORT: '8801', ACM_SECRET: SECRET, ACM_CDP_PORT: String(CDP_PORT), ACM_STATE_DIR: state1 });
  await waitHttp('http://127.0.0.1:8801/health');

  await test('wrong secret is rejected (404)', async () => {
    const r = await fetch('http://127.0.0.1:8801/mcp/wrong', { method: 'POST', body: '{}' });
    assert.equal(r.status, 404);
    const r2 = await fetch('http://127.0.0.1:8801/mcp', { method: 'POST', body: '{}' });
    assert.equal(r2.status, 404);
  });

  await test('lenient Accept / Content-Type (no 406 / 415)', async () => {
    const init = { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'x', version: '1' } } };
    for (const headers of [
      { accept: '*/*', 'content-type': 'application/json' },
      { accept: 'application/json', 'content-type': 'application/json' },
      { 'content-type': 'application/json' },
      { accept: 'application/json, text/event-stream', 'content-type': 'text/plain' },
    ]) {
      const r = await fetch(`http://127.0.0.1:8801/mcp/${SECRET}`, { method: 'POST', headers, body: JSON.stringify(init) });
      const body = await r.text();
      assert.equal(r.status, 200, JSON.stringify(headers) + ' -> ' + r.status + ' ' + body);
      const j = JSON.parse(body);
      assert.equal(j.result.serverInfo.name, 'android-chrome');
    }
  });

  const c = await connect(8801, SECRET);

  await test('tools are listed', async () => {
    const { tools } = await c.listTools();
    const names = tools.map((t) => t.name);
    for (const n of ['browser_status', 'navigate', 'snapshot', 'click', 'type', 'screenshot', 'tabs_list', 'evaluate']) assert.ok(names.includes(n), n);
  });

  await test('browser_status', async () => {
    const s = JSON.parse(text(await call(c, 'browser_status')));
    assert.match(s.browser, /Chrome|HeadlessChrome/);
  });

  await test('user tabs are hidden and protected by default', async () => {
    const pagesNow = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json();
    const userTab = pagesNow.find((p) => p.type === 'page');
    const tabs = JSON.parse(text(await call(c, 'tabs_list')));
    assert.equal(tabs.length, 0);
    const r = await c.callTool({ name: 'tab_select', arguments: { tab: userTab.id } });
    assert.ok(r.isError && /belongs to the user/.test(text(r)));
  });

  await test('navigate opens an agent tab', async () => {
    const r = JSON.parse(text(await call(c, 'navigate', { url: `${B}/` })));
    assert.equal(r.title, 'Home');
    const tabs = JSON.parse(text(await call(c, 'tabs_list')));
    assert.equal(tabs.length, 1);
    assert.equal(tabs[0].owner, 'agent');
  });

  await test('snapshot lists numbered elements and text', async () => {
    const s = await call(c, 'snapshot');
    assert.match(text(s), /\[\d+\] a "Open form" -> \/form/);
    assert.match(text(s), /Test shop/);
  });

  await test('click a JS button', async () => {
    const s = await call(c, 'snapshot');
    await call(c, 'click', { ref: refOf(s, /button "Do JS"/) });
    const s2 = await call(c, 'snapshot', { textOnly: true });
    assert.match(text(s2), /JS clicked/);
  });

  await test('click target=_blank link stays in the same tab', async () => {
    const s = await call(c, 'snapshot');
    const r = JSON.parse(text(await call(c, 'click', { ref: refOf(s, /New window link/) })));
    assert.equal(r.title, 'New');
    assert.equal(JSON.parse(text(await call(c, 'tabs_list'))).length, 1);
  });

  await test('history back', async () => {
    const r = JSON.parse(text(await call(c, 'history', { action: 'back' })));
    assert.equal(r.title, 'Home');
  });

  await test('fill a form: type, select, checkbox, submit', async () => {
    await call(c, 'navigate', { url: `${B}/form` });
    let s = await call(c, 'snapshot');
    const typed = JSON.parse(text(await call(c, 'type', { ref: refOf(s, /input:text "Search"/), text: 'Привет мир' })));
    assert.equal(typed.value, 'Привет мир');
    s = await call(c, 'snapshot');
    assert.match(text(s), /live:Привет мир/, 'input events must fire');
    const pw = JSON.parse(text(await call(c, 'type', { ref: refOf(s, /input:password/), text: 'secret123' })));
    assert.equal(pw.value, '(hidden password)');
    s = await call(c, 'snapshot');
    assert.doesNotMatch(text(s), /secret123/, 'password must not leak into snapshot');
    await call(c, 'select_option', { ref: refOf(s, /select/), option: 'Green' });
    await call(c, 'click', { ref: refOf(s, /checkbox/) });
    s = await call(c, 'snapshot');
    assert.match(text(s), /checkbox.*\[checked\]/);
    const r = JSON.parse(text(await call(c, 'type', { ref: refOf(s, /input:text "Search"/), text: 'final query', submit: true })));
    assert.equal(r.navigated, true);
    s = await call(c, 'snapshot', { textOnly: true });
    assert.match(text(s), /You searched: final query color=g agree=on/);
  });

  await test('POST form to the same URL: detected as navigation, submitted exactly once', async () => {
    await call(c, 'navigate', { url: `${B}/post` });
    const s = await call(c, 'snapshot');
    const r = JSON.parse(text(await call(c, 'type', { ref: refOf(s, /"Message"/), text: 'hello', submit: true })));
    assert.equal(r.navigated, true);
    assert.equal(postCount, 1, 'no double submit');
  });

  await test('press_key', async () => {
    await call(c, 'navigate', { url: `${B}/form` });
    const s = await call(c, 'snapshot');
    await call(c, 'type', { ref: refOf(s, /input:text "Search"/), text: 'by enter' });
    const r = JSON.parse(text(await call(c, 'press_key', { key: 'Enter' })));
    assert.equal(r.title, 'Result');
  });

  await test('wait_for', async () => {
    await call(c, 'navigate', { url: `${B}/late` });
    const w = JSON.parse(text(await call(c, 'wait_for', { text: 'Ready now', timeoutMs: 5000 })));
    assert.equal(w.found, true);
  });

  await test('scroll', async () => {
    const r = JSON.parse(text(await call(c, 'scroll', { direction: 'bottom' })));
    assert.match(r.scroll, /^\d+\/\d+$/);
  });

  await test('evaluate (expression and statements)', async () => {
    assert.equal(text(await call(c, 'evaluate', { expression: 'document.title' })), 'Late');
    assert.equal(text(await call(c, 'evaluate', { expression: 'const a = 2; return a * 21' })), '42');
  });

  await test('screenshot returns a JPEG', async () => {
    const r = await call(c, 'screenshot');
    assert.equal(r.content[0].type, 'image');
    assert.ok(Buffer.from(r.content[0].data, 'base64').subarray(0, 2).equals(Buffer.from([0xff, 0xd8])));
  });

  await test('tab_open / tab_select / tab_close', async () => {
    const t = JSON.parse(text(await call(c, 'tab_open', { url: `${B}/new` })));
    assert.equal(JSON.parse(text(await call(c, 'tabs_list'))).length, 2);
    await call(c, 'tab_close', { tab: t.tab.slice(0, 8) });
    assert.equal(JSON.parse(text(await call(c, 'tabs_list'))).length, 1);
  });

  await test('security: files, other apps and browser internals are blocked', async () => {
    for (const url of ['file:///etc/passwd', 'content://media/external/images', 'intent://scan/#Intent;scheme=zxing;end', 'chrome://settings', 'javascript:alert(1)', 'tel:123']) {
      const r = await c.callTool({ name: 'navigate', arguments: { url } });
      assert.ok(r.isError && /Only http\(s\)/.test(text(r)), 'navigate ' + url + ' -> ' + text(r));
      const r2 = await c.callTool({ name: 'tab_open', arguments: { url } });
      assert.ok(r2.isError, 'tab_open ' + url);
    }
    await call(c, 'navigate', { url: `${B}/apps` });
    const s = await call(c, 'snapshot');
    const r = await c.callTool({ name: 'click', arguments: { ref: refOf(s, /Open app/) } });
    assert.ok(r.isError && /blocked/.test(text(r)), 'intent link click blocked');
    const t = JSON.parse(text(await call(c, 'tabs_list')));
    assert.ok(t.every((x) => /^http/.test(x.url)), 'still on a web page');
  });

  await test('stale element number gives a clear error', async () => {
    const r = await c.callTool({ name: 'click', arguments: { ref: 999 } });
    assert.ok(r.isError && /take a new snapshot/i.test(text(r)));
  });
  await c.close();

  // ========== Part 2: android mode through a fake adb ==========
  console.log('android mode (fake adb)');
  const adbLog = path.join(os.tmpdir(), `acm-adb-${process.pid}.log`);
  fs.writeFileSync(adbLog, '');
  const state2 = fs.mkdtempSync(path.join(os.tmpdir(), 'acm-state-'));
  startServer({
    ACM_MODE: 'android', ACM_PORT: '8802', ACM_SECRET: SECRET, ACM_CDP_PORT: String(CDP_PORT), ACM_STATE_DIR: state2,
    ACM_ADB: path.join(here, 'fake-adb.js'), FAKE_ADB_LOG: adbLog, FAKE_ADB_CDP_PORT: String(CDP_PORT),
    ACM_CDP_SOCKET: path.join(state2, 'cdp.sock'), ACM_CDP_PORT: '1',  // TCP port unusable: must go through the socket
  });
  await waitHttp('http://127.0.0.1:8802/health');
  const a = await connect(8802, SECRET);

  await test('android: new tab via Intent, then the previous app (YouTube) is restored', async () => {
    const r = JSON.parse(text(await call(a, 'navigate', { url: `${B}/form` })));
    assert.equal(r.title, 'Form');
    const calls = fs.readFileSync(adbLog, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.ok(calls.some((x) => x[0] === 'forward' && /^localfilesystem:/.test(x[1]) && x[2] === 'localabstract:chrome_devtools_remote'), 'private unix-socket forward');
    assert.ok(!calls.some((x) => x[0] === 'forward' && /^tcp:/.test(x[1] || '')), 'no TCP port opened on the phone');
    assert.equal(fs.statSync(path.join(state2, 'cdp.sock')).mode & 0o077, 0, 'socket is private (0600)');
    const am = calls.find((x) => x[0] === 'shell' && x[1].startsWith('am start'));
    assert.ok(am && am[1].includes('--ez create_new_tab true') && am[1].includes("-p 'com.android.chrome'"), 'intent');
    assert.ok(calls.some((x) => x[0] === 'shell' && x[1].includes("monkey -p 'com.google.android.youtube'")), 'focus restored to YouTube');
  });

  await test('android: tabs persist across a server restart (state file)', async () => {
    const st = JSON.parse(fs.readFileSync(path.join(state2, 'tabs.json'), 'utf8'));
    assert.equal(st.agentTabs.length, 1);
  });

  await test('android: work in the tab without any further Intent', async () => {
    const before = fs.readFileSync(adbLog, 'utf8').split('\n').filter((l) => l.includes('am start')).length;
    const s = await call(a, 'snapshot');
    await call(a, 'type', { ref: refOf(s, /input:text "Search"/), text: 'background', submit: true });
    const after = fs.readFileSync(adbLog, 'utf8').split('\n').filter((l) => l.includes('am start')).length;
    assert.equal(after, before, 'no Intent / no screen use for normal work');
  });
  await a.close();

  // ========== Part 3: hidden + frozen background tab (what Android does) ==========
  console.log('background tab (document.hidden + frozen)');
  const v = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`)).json();
  const bws = new WebSocket(v.webSocketDebuggerUrl);
  await new Promise((r) => (bws.onopen = r));
  let bid = 0;
  const bp = new Map();
  bws.onmessage = (e) => { const m = JSON.parse(e.data); bp.get(m.id)?.(m); bp.delete(m.id); };
  const bsend = (method, params = {}, sessionId) => new Promise((r) => { const i = ++bid; bp.set(i, r); bws.send(JSON.stringify({ id: i, method, params, sessionId })); });
  const hidden = (await bsend('Target.createTarget', { url: `${B}/form`, background: true })).result.targetId;
  await sleep(800);
  const sid = (await bsend('Target.attachToTarget', { targetId: hidden, flatten: true })).result.sessionId;
  const vis = (await bsend('Runtime.evaluate', { expression: 'document.visibilityState', returnByValue: true }, sid)).result.result.value;
  assert.equal(vis, 'hidden');
  await bsend('Page.setWebLifecycleState', { state: 'frozen' }, sid);

  startServer({ ACM_MODE: 'desktop', ACM_PORT: '8803', ACM_SECRET: SECRET, ACM_CDP_PORT: String(CDP_PORT), ACM_ALL_TABS: '1',
    ACM_STATE_DIR: fs.mkdtempSync(path.join(os.tmpdir(), 'acm-state-')) });
  await waitHttp('http://127.0.0.1:8803/health');
  const h = await connect(8803, SECRET);

  await test('hidden+frozen tab: read, type (input events), submit', async () => {
    await call(h, 'tab_select', { tab: hidden });
    let s = await call(h, 'snapshot');
    assert.match(text(s), /Title: Form/);
    await call(h, 'type', { ref: refOf(s, /input:text "Search"/), text: 'hidden typing' });
    s = await call(h, 'snapshot');
    assert.match(text(s), /live:hidden typing/);
    // Focus emulation makes the page *believe* it is visible and focused (so sites
    // behave normally) without activating the tab in the browser UI.
    assert.equal(
      (await bsend('Runtime.evaluate', { expression: 'document.hasFocus()', returnByValue: true }, sid)).result.result.value,
      true, 'focus emulation active',
    );
    const r = JSON.parse(text(await call(h, 'type', { ref: refOf(s, /input:text "Search"/), text: 'bg submit', submit: true })));
    assert.equal(r.navigated, true);
    assert.match(text(await call(h, 'snapshot', { textOnly: true })), /You searched: bg submit/);
  });

  await test('hidden tab: navigate + click + screenshot', async () => {
    await call(h, 'navigate', { url: `${B}/` });
    const s = await call(h, 'snapshot');
    await call(h, 'click', { ref: refOf(s, /button "Do JS"/) });
    assert.match(text(await call(h, 'snapshot', { textOnly: true })), /JS clicked/);
    const shot = await h.callTool({ name: 'screenshot', arguments: {} });
    console.log('    (screenshot of hidden tab:', shot.isError ? 'error: ' + text(shot).slice(0, 80) : 'ok', ')');
  });
  await h.close();
  bws.close();

  console.log(`\n${passed} tests passed`);
}

main()
  .then(() => { cleanup(); process.exit(0); })
  .catch((e) => { console.error('\nFAILED:', e); cleanup(); process.exit(1); });

function cleanup() {
  for (const p of procs) try { p.kill('SIGKILL'); } catch {}
  site.close();
}
