// Shared helper: call Telegram's own MTProto API inside the logged-in Telegram Web tab of the pult (CDP).
// Fast (server-side search/history, ~100 messages per 0.5 s), nothing is written to disk.
const { chromium } = require('playwright');

// runs in the page: install window.__tg once per tab (bump V when this code changes)
const V = 3;
function install(V) {
  if (window.__tg && window.__tg.v === V) return;
  const M = rootScope.managers, raw = (m, p) => M.apiManager.invokeApi(m, p);
  // WRITE GUARD (17.11: a folder meant for account 2 was written into account 1): any call that changes something
  // first checks that this tab is still the account the tool asked for (window.__tg.expect), else refuses.
  const READ = /^(messages\.(get|search|check)|channels\.get|users\.get|contacts\.resolve|help\.|updates\.|account\.get)/;
  const api = async (m, p) => {
    if (!READ.test(m)) {
      const want = window.__tg && window.__tg.expect; const me = await M.appUsersManager.getSelf();
      if (!want || !me || String(me.id) !== String(want)) throw new Error(`СТОП: запись ${m} не в тот аккаунт (вкладка ${me && me.id}, нужен ${want})`);
    }
    return raw(m, p);
  };
  const inputOf = c => c._ === 'channel' ? { _: 'inputPeerChannel', channel_id: c.id, access_hash: c.access_hash }
    : c._ === 'chat' ? { _: 'inputPeerChat', chat_id: c.id }
    : { _: 'inputPeerUser', user_id: c.id, access_hash: c.access_hash };
  const uname = e => e.username || (e.usernames || []).map(u => u.username).find(Boolean) || '';
  const cache = {};
  // ref: @username | t.me/username | -100… / -id (channel) | id (user)
  async function resolve(ref) {
    ref = String(ref).trim().replace(/^https?:\/\/t\.me\//, '').replace(/^@/, '').replace(/\/.*$/, '');
    if (cache[ref]) return cache[ref];
    let ent;
    if (/^-?\d+$/.test(ref)) {
      const n = +ref, id = Math.abs(n) > 1e12 ? Math.abs(n) - 1e12 : Math.abs(n);
      ent = n < 0 ? await M.appChatsManager.getChat(id) : await M.appUsersManager.getUser(id);
      if (!ent || !ent.id) throw new Error('нет в кеше: ' + ref + ' — укажите @username');
    } else {
      const r = await api('contacts.resolveUsername', { username: ref });
      ent = (r.chats && r.chats[0]) || (r.users && r.users[0]);
    }
    const o = { ent, peer: inputOf(ent), peerId: ent._ === 'user' ? ent.id : -ent.id,
      title: ent.title || [ent.first_name, ent.last_name].filter(Boolean).join(' '), username: uname(ent) };
    return (cache[ref] = o);
  }
  // text of a message incl. new "rich" posts (blocks of RichText)
  function text(m) {
    if (m.message) return m.message;
    if (!m.rich_message) return '';
    const out = []; const walk = x => {
      if (!x || typeof x !== 'object') return;
      if (Array.isArray(x)) return x.forEach(walk);
      if (typeof x.text === 'string') out.push(x.text);
      for (const k in x) if (k !== 'text' || typeof x[k] !== 'string') walk(x[k]);
    }; walk(m.rich_message.blocks); return out.join(' ').replace(/\s+/g, ' ').trim();
  }
  function slim(m, uname, chId) {
    const media = m.media ? m.media._.replace('messageMedia', '').toLowerCase() : (m.rich_message ? 'rich' : '');
    const doc = m.media && m.media.document; const isVideo = doc && (doc.attributes || []).some(a => a._ === 'documentAttributeVideo');
    const t = text(m);
    const links = [...new Set([...(t.match(/https?:\/\/\S+/g) || []),
      ...(m.entities || []).filter(e => e.url).map(e => e.url)])];
    return { id: m.id, date: m.date, views: m.views || 0, fwd: m.forwards || 0,
      react: (m.reactions && m.reactions.results || []).reduce((s, r) => s + (r.count || 0), 0),
      replies: m.replies ? m.replies.replies : 0, media: isVideo ? 'video' : media, grouped: m.grouped_id || '',
      text: t, links, url: uname ? `https://t.me/${uname}/${m.id}` : `https://t.me/c/${chId}/${m.id}` };
  }
  window.__tg = { v: V, api, resolve, text, slim, M, uname, inputOf };
}

// Accounts: 1 = Sahalinov (MAIN, default), 2 = second account — only when the owner asks (--account 2).
// Telegram Web keeps both logged in; the tab shows one at a time (`/k/` = 1, `/k/?account=2` = 2).
const TG = 'https://web.telegram.org/k/';
const tabAccount = pg => +(new URL(pg.url()).searchParams.get('account') || 1);
async function ensureAccount(pg, n = 1) {
  n = +n || 1;
  if (tabAccount(pg) !== n) await pg.goto(n > 1 ? `${TG}?account=${n}` : TG, { waitUntil: 'domcontentloaded' });
  await pg.waitForFunction(() => window.rootScope && rootScope.managers, null, { timeout: 30000 });
  const me = await pg.evaluate(async () => { const u = await rootScope.managers.appUsersManager.getSelf();
    return u ? { id: u.id, name: [u.first_name, u.last_name].filter(Boolean).join(' '), username: u.username || '' } : null; });
  if (!me) throw new Error(`аккаунт ${n} не вошёл`);
  console.error(`[аккаунт ${n}: ${me.name} @${me.username || '—'} id ${me.id}]`);
  return me;
}
// switch the pult back to the main account (call after work on account 2)
async function backToMain(pg) { if (tabAccount(pg) !== 1) await pg.goto(TG, { waitUntil: 'domcontentloaded' }).catch(() => {}); }

// One tab = one worker. Two tools in the same tab broke each other (account switch mid-job, white screen),
// so every tool takes a lock; a second one waits (TG_WAIT=minutes, default 0 = refuse) or says who holds it.
const fs = require('fs');
const LOCK = process.env.TG_LOCK || '/tmp/tg_pult.lock';
function lockInfo() { try { const l = JSON.parse(fs.readFileSync(LOCK, 'utf8')); process.kill(l.pid, 0); return l; } catch (e) { return null; } }
async function lock() {
  const until = Date.now() + (+process.env.TG_WAIT || 0) * 60e3;
  for (;;) {
    const l = lockInfo();
    if (!l || l.pid === process.pid) break;
    if (Date.now() >= until) throw new Error(`пульт занят: pid ${l.pid} «${l.cmd}» с ${l.since} — дождитесь (TG_WAIT=30 node …) или проверьте tg_doctor.js`);
    await new Promise(r => setTimeout(r, 5000));
  }
  fs.writeFileSync(LOCK, JSON.stringify({ pid: process.pid, cmd: process.argv.slice(1).map(a => a.split('/').pop()).join(' ').slice(0, 120), since: new Date().toISOString().slice(11, 19) + 'Z' }));
  const free = () => { try { if ((lockInfo() || {}).pid === process.pid) fs.unlinkSync(LOCK); } catch (e) {} };
  process.on('exit', free); return free;
}

let chain = Promise.resolve(); // in-process: one withTg at a time, the next starts only after the previous fully ended
function withTg(fn, cdp, account) { const run = chain.then(() => withTgOnce(fn, cdp, account)); chain = run.catch(() => {}); return run; }
async function withTgOnce(fn, cdp = 'http://127.0.0.1:9222', account = 1) {
  const unlock = await lock();
  const b = await chromium.connectOverCDP(cdp);
  let pg;
  try {
    pg = b.contexts()[0].pages().find(p => /web\.telegram\.org/.test(p.url()));
    if (!pg) throw new Error('нет вкладки web.telegram.org в пульте — поднимите пульт (docs/telegram.md)');
    const me = await ensureAccount(pg, account);
    await pg.evaluate(install, V);
    await pg.evaluate(id => { window.__tg.expect = id; }, String(me.id));
    return await fn(pg);
  } finally { if (pg && +account !== 1) await backToMain(pg); await b.close().catch(() => {}); unlock(); } // detaches only; the pult keeps running
}

// argv helper: --key value / --flag
function opts(argv) {
  const o = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) { const k = a.slice(2); const v = argv[i + 1]; if (v === undefined || v.startsWith('--')) o[k] = true; else { o[k] = v; i++; } }
    else o._.push(a);
  }
  return o;
}
module.exports = { withTg, opts, ensureAccount, backToMain, tabAccount, lock, lockInfo };
