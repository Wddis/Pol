// Shared helper: call Telegram's own MTProto API inside the logged-in Telegram Web tab of the pult (CDP).
// Fast (server-side search/history, ~100 messages per 0.5 s), nothing is written to disk.
const { chromium } = require('playwright');

// runs in the page: install window.__tg once per tab (bump V when this code changes)
const V = 2;
function install(V) {
  if (window.__tg && window.__tg.v === V) return;
  const M = rootScope.managers, api = (m, p) => M.apiManager.invokeApi(m, p);
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

async function withTg(fn, cdp = 'http://127.0.0.1:9222') {
  const b = await chromium.connectOverCDP(cdp);
  try {
    const pg = b.contexts()[0].pages().find(p => /web\.telegram\.org/.test(p.url()));
    if (!pg) throw new Error('нет вкладки web.telegram.org в пульте — поднимите пульт (docs/telegram.md)');
    await pg.waitForFunction(() => window.rootScope && rootScope.managers, null, { timeout: 30000 });
    await pg.evaluate(install, V);
    return await fn(pg);
  } finally { await b.close().catch(() => {}); } // detaches only; the pult keeps running
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
module.exports = { withTg, opts };
