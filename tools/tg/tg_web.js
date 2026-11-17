// Telegram Web (K) helper over the pult's CDP: the owner logs in through remote_browser.js (QR / phone),
// this script then works in the same logged-in tab.
// usage: node tg_web.js [cdp:9222] <command> [...]
//   me                         — which account the command works in
//   chats [N=30] [--folder X]  — chat list: peer id, title, unread, last message
//   read  <peer> [N=30]        — last N messages of a chat (peer = id | @username | title as in `chats`)
//   search <text>              — global search box (chats, contacts, messages) — titles + peer ids
//   send  <peer> <text> --yes  — send a message ONLY by the owner's command (without --yes: dry run)
//   shot  [out.png]            — screenshot of the Telegram tab
//   session-save <file>        — dump login (localStorage) to a file — SCRATCHPAD ONLY, it is a full login
//   session-restore <file>     — load it into the pult after a container restart (no QR again)
// --account 2: work in the second account (ONLY when the owner asks); default = account 1 Sahalinov (main)
// Notes: opening a chat marks it read for the owner (as in a normal client). Never log/commit session files.
const fs = require('fs');
const { chromium } = require('playwright');
const { ensureAccount, backToMain, lock } = require('./tg_api');
let args = process.argv.slice(2);
let CDP = 'http://127.0.0.1:9222';
if (args[0] && /^cdp:/.test(args[0])) { CDP = 'http://127.0.0.1:' + args.shift().slice(4); }
const yes = args.includes('--yes'); args = args.filter(a => a !== '--yes');
// --account 2 = second account, only when the owner asks; default 1 = Sahalinov (main)
let ACCOUNT = 1; { const i = args.indexOf('--account'); if (i >= 0) { ACCOUNT = +args[i + 1] || 1; args.splice(i, 2); } }
const [cmd, ...rest] = args;
const TG = 'https://web.telegram.org/k/';
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function tgPage(b) {
  const ctx = b.contexts()[0];
  let pg = ctx.pages().find(p => /web\.telegram\.org/.test(p.url()));
  if (!pg) { pg = await ctx.newPage(); await pg.goto(TG, { waitUntil: 'domcontentloaded' }); }
  return pg;
}
async function waitList(pg) {
  await home(pg);
  await pg.waitForSelector('.chatlist-chat[data-peer-id]', { timeout: 30000 }).catch(() => {
    throw new Error('список чатов не появился — не вошли? (сделайте `shot` и проверьте)');
  });
}
// back to the plain chat list: leave search mode, close the "tips" popup (Escape would open search instead)
async function home(pg) {
  for (let i = 0; i < 3; i++) {
    const back = pg.locator('.is-search-active .sidebar-back-button.is-visible').first();
    if (!(await back.count()) || !(await back.isVisible().catch(() => false))) break;
    await back.click().catch(() => {}); await sleep(500);
  }
  // the "Appearance / tips" overlay stays over the chat area and swallows pointer events
  const closed = await pg.evaluate(() => {
    const t = document.querySelector('.themes-container');
    const btn = [...document.querySelectorAll('button.btn-icon.close')].find(b => b.getBoundingClientRect().width && !b.closest('.chat-input, .sidebar'));
    if (t && btn) { btn.click(); return true; } return false;
  }).catch(() => false);
  if (closed) await sleep(500);
}

async function listChats(pg, n, folder) {
  if (folder) {
    const tab = pg.locator('.menu-horizontal-div-item, .tabs-tab', { hasText: folder }).first();
    if (await tab.count()) { await tab.click(); await sleep(800); } else console.error(`папка «${folder}» не найдена`);
  }
  const seen = new Map();
  for (let i = 0; i < 40 && seen.size < n; i++) {
    const rows = await pg.evaluate(() => [...document.querySelectorAll('.chatlist-chat[data-peer-id]')]
      .filter(a => a.offsetParent).map(a => ({
        peer: a.dataset.peerId,
        title: a.querySelector('.row-title .peer-title, .peer-title')?.textContent.trim() || '',
        unread: (a.querySelector('.dialog-subtitle-badge-unread, .badge-unread, .unread')?.textContent || '').trim(),
        pinned: a.classList.contains('is-pinned'),
        last: (a.querySelector('.row-subtitle, .dialog-subtitle')?.innerText || '').replace(/\s+/g, ' ').trim(),
        time: (a.querySelector('.message-time, .row-title-row .time')?.textContent || '').trim(),
      })));
    for (const r of rows) if (!seen.has(r.peer)) seen.set(r.peer, r);
    const moved = await pg.evaluate(() => {
      const el = document.querySelector('.chatlist-chat[data-peer-id]')?.closest('.scrollable');
      if (!el) return false; const t = el.scrollTop; el.scrollTop += el.clientHeight * 0.9; return el.scrollTop !== t;
    });
    if (!moved) break; await sleep(500);
  }
  return [...seen.values()].slice(0, n);
}

async function openPeer(pg, peer) {
  const p = String(peer);
  const byId = /^-?\d+$/.test(p), byUser = p.startsWith('@');
  let row = byId ? pg.locator(`.chatlist-chat[data-peer-id="${p}"]`).first() : null;
  if (!byId && !byUser) { // title: search the loaded list, then the search box
    const rows = await listChats(pg, 400);
    const hit = rows.find(r => r.title === p) || rows.find(r => r.title.toLowerCase().includes(p.toLowerCase()));
    if (!hit) throw new Error(`чат «${p}» не найден в списке — укажите id или @username`);
    row = pg.locator(`.chatlist-chat[data-peer-id="${hit.peer}"]`).first();
  }
  if (row && await row.count()) await row.dispatchEvent('mousedown').then(() => row.dispatchEvent('click'));
  else await pg.evaluate(h => { location.hash = h; }, '#' + (byUser ? p : p));
  await pg.waitForSelector('.bubbles .bubble[data-mid], .chat-input .input-message-input', { timeout: 20000 })
    .catch(() => { throw new Error(`чат ${p} не открылся`); });
  await sleep(1200);
  return (await pg.locator('.chat-info .peer-title').first().textContent().catch(() => '')) || p;
}

async function readMsgs(pg, n) {
  for (let i = 0; i < 20; i++) { // scroll up until N bubbles are loaded
    const c = await pg.locator('.bubble[data-mid]').count(); if (c >= n) break;
    await pg.evaluate(() => { const s = document.querySelector('.bubbles .scrollable'); if (s) s.scrollTop = 0; });
    await sleep(700);
  }
  return pg.evaluate(n => [...document.querySelectorAll('.bubble[data-mid]')].slice(-n).map(b => {
    const m = b.querySelector('.message'); let text = '';
    if (m) { const c = m.cloneNode(true); c.querySelectorAll('.time, .reactions, .bubble-tail').forEach(x => x.remove()); text = c.innerText.trim(); }
    const media = [...b.classList].filter(c => /^(photo|video|is-round|voice|sticker|document|poll|audio)/.test(c)).join(',');
    const ts = +b.dataset.timestamp;
    return { mid: b.dataset.mid, out: b.classList.contains('is-out'),
      from: b.querySelector('.name .peer-title, .colored-name')?.textContent.trim() || '',
      date: ts ? new Date(ts * 1000).toISOString().replace('T', ' ').slice(0, 16) : '',
      media, text };
  }), n);
}

(async () => {
  if (!cmd) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 13).join('\n')); return; }
  const unlock = await lock();
  const b = await chromium.connectOverCDP(CDP);
  const pg = await tgPage(b);
  try {
    const me = cmd.startsWith('session-') ? null : await ensureAccount(pg, ACCOUNT);
    if (cmd === 'session-restore') {
      const data = JSON.parse(fs.readFileSync(rest[0], 'utf8'));
      if (!/web\.telegram\.org/.test(pg.url())) await pg.goto(TG, { waitUntil: 'domcontentloaded' });
      await pg.evaluate(d => { localStorage.clear(); for (const [k, v] of Object.entries(d)) localStorage.setItem(k, v); }, data);
      await pg.goto(TG, { waitUntil: 'domcontentloaded' }); await pg.reload({ waitUntil: 'domcontentloaded' });
      await waitList(pg); console.log('сессия восстановлена'); return;
    }
    if (cmd === 'shot') { await pg.screenshot({ path: rest[0] || 'tg_shot.png' }); console.log(rest[0] || 'tg_shot.png'); return; }
    await waitList(pg);
    if (cmd === 'session-save') {
      const d = await pg.evaluate(() => Object.fromEntries(Object.entries(localStorage)));
      fs.writeFileSync(rest[0], JSON.stringify(d), { mode: 0o600 }); console.log(`сохранено ${Object.keys(d).length} ключей → ${rest[0]} (НЕ в репо)`);
    } else if (cmd === 'me') {
      console.log(`аккаунт ${ACCOUNT}: ${me.name} @${me.username || '—'} id ${me.id}`);
    } else if (cmd === 'chats') {
      const fi = rest.indexOf('--folder'); const folder = fi >= 0 ? rest[fi + 1] : null;
      const n = +(rest.find(x => /^\d+$/.test(x)) || 30);
      for (const r of await listChats(pg, n, folder))
        console.log(`${r.peer}\t${r.pinned ? '📌' : ''}${r.unread ? '[' + r.unread + '] ' : ''}${r.title}\t${r.time}\t${r.last.slice(0, 90)}`);
    } else if (cmd === 'read') {
      const title = await openPeer(pg, rest[0]);
      console.log(`== ${title}`);
      for (const m of await readMsgs(pg, +(rest[1] || 30)))
        console.log(`${m.date} ${m.out ? '→ я' : (m.from || '←')}${m.media ? ' [' + m.media + ']' : ''}: ${m.text.replace(/\n/g, ' ⏎ ')}`);
    } else if (cmd === 'search') {
      const box = pg.locator('.input-search input, input.input-search-input').first();
      await box.click(); await box.fill(''); await pg.keyboard.type(rest.join(' '), { delay: 30 }); await sleep(2500);
      const r = await pg.evaluate(() => [...document.querySelectorAll('.search-super, #search-container, .sidebar-search')]
        .flatMap(c => [...c.querySelectorAll('[data-peer-id].chatlist-chat, a[data-peer-id]')])
        .filter(a => a.offsetParent).map(a => `${a.dataset.peerId}\t${(a.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 120)}`));
      console.log([...new Set(r)].slice(0, 40).join('\n') || 'ничего не найдено');
      await home(pg);
    } else if (cmd === 'send') {
      const [peer, ...t] = rest; const text = t.join(' ');
      if (!peer || !text) throw new Error('send <peer> <text> --yes');
      const title = await openPeer(pg, peer);
      if (!yes) { console.log(`DRY RUN — в «${title}» ушло бы: ${text}\n(отправка только по команде владельца: добавьте --yes)`); return; }
      const before = await pg.locator('.bubble.is-out[data-mid]').count();
      const inp = pg.locator('.chat-input .input-message-input[contenteditable="true"]').first();
      await inp.click(); await pg.keyboard.insertText(text); await sleep(300); await pg.keyboard.press('Enter');
      for (let i = 0; i < 20; i++) { await sleep(500); if (await pg.locator('.bubble.is-out[data-mid]').count() > before) break; }
      const last = (await readMsgs(pg, 1))[0];
      console.log(last && last.out && last.text.includes(text.slice(0, 20)) ? `отправлено в «${title}»` : 'ВНИМАНИЕ: не вижу отправленного сообщения — проверьте shot');
    } else throw new Error('неизвестная команда ' + cmd);
  } catch (e) { console.error('ОШИБКА:', e.message); process.exitCode = 1; }
  finally { if (ACCOUNT !== 1) await backToMain(pg); await b.close().catch(() => {}); unlock(); } // only detaches from CDP, the pult browser keeps running
})();
