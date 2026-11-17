// Ephemeral channel analysis over the pult's Telegram Web: fetches straight from Telegram's API in memory,
// filters on the server where possible, prints ONLY matching posts in compact lines. Nothing saved to disk
// (unless --json FILE). Read-only: does not open chats, does not mark anything read, joins nothing.
// usage: node tg_channel.js <cmd> <channel[,channel2…]> [options]
//   info  <ch>                      — title, @, subscribers, total posts, description
//   scan  <ch> [filters]            — matching posts, one line each: date · views · fwd · react · url · text
//   stats <ch> [--days 30]          — posts/day, views (avg/median), ER, media mix, hours, top posts, domains, #tags
//   find  <text> [filters]          — global search across all channels/chats (server side)
// filters:
//   --q "слово"        server-side search (fast; Telegram's morphology) — use first, it cuts the volume
//   --re "regex"       client-side regex on text (case-insensitive), e.g. "розыгрыш|giveaway"
//   --not "regex"      drop posts matching it (ads, "erid", reposts…)
//   --days N | --since 2026-11-01 [--until 2026-11-15]
//   --limit N          max posts to fetch per channel (default 300; scan stops at the date limit anyway)
//   --min-views N  --media photo|video|document|rich|none|any  --links (only posts with links)
//   --top N --by views|fwd|react|replies|er   — best N instead of chronological
//   --chars N          text width (default 160; 0 = full text)  --json FILE  — dump the filtered set (scratchpad)
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2)); // --account 2 = второй аккаунт, только по просьбе владельца
const [cmd, target] = o._;
const DAY = 86400, now = Math.floor(Date.now() / 1000);
const since = o.since ? Date.parse(o.since) / 1000 : o.days ? now - o.days * DAY : 0;
const until = o.until ? Date.parse(o.until) / 1000 + DAY : 0;
const limit = +(o.limit || (cmd === 'stats' ? 1000 : 300));
const chars = o.chars === undefined ? 160 : +o.chars;

// in-page fetch: history or server search, paged, stops at `since`
async function fetchPosts(pg, ref, q) {
  return pg.evaluate(async ({ ref, q, since, until, limit }) => {
    const T = window.__tg; const ch = ref ? await T.resolve(ref) : null;
    const out = []; let offset_id = 0, offset_rate = 0, offset_peer = { _: 'inputPeerEmpty' };
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    while (out.length < limit) {
      let r;
      for (let a = 0; ; a++) {
        try {
          const base = { limit: Math.min(100, limit - out.length), offset_id, add_offset: 0, max_id: 0, min_id: 0, hash: 0 };
          r = !ch ? await T.api('messages.searchGlobal', { q, filter: { _: 'inputMessagesFilterEmpty' }, min_date: since, max_date: until,
                  offset_rate, offset_peer, offset_id, limit: base.limit })
            : q ? await T.api('messages.search', { ...base, peer: ch.peer, q, filter: { _: 'inputMessagesFilterEmpty' }, min_date: since, max_date: until })
            : await T.api('messages.getHistory', { ...base, peer: ch.peer, offset_date: until });
          break;
        } catch (e) {
          const w = /FLOOD_WAIT_(\d+)/.exec(e.type || e.message || ''); if (w && a < 3) { await sleep((+w[1] + 1) * 1000); continue; }
          throw new Error((e.type || e.message || String(e)) + (ch ? ' @ ' + ch.title : ''));
        }
      }
      const ms = (r.messages || []).filter(m => m._ === 'message');
      if (!ms.length) break;
      const chats = Object.fromEntries((r.chats || []).map(c => [c.id, c]));
      for (const m of ms) {
        if (since && m.date < since) return { ch: ch && { title: ch.title, username: ch.username, peerId: ch.peerId }, posts: out };
        const pid = m.peer_id && (m.peer_id.channel_id || m.peer_id.chat_id || m.peer_id.user_id);
        const c = ch ? ch.ent : chats[pid] || {};
        const s = T.slim(m, T.uname(c), c.id); s.src = c.title || ''; out.push(s);
      }
      const last = ms[ms.length - 1]; offset_id = last.id;
      if (!ch) { offset_rate = r.next_rate || last.date; const lc = chats[last.peer_id.channel_id || last.peer_id.chat_id];
        offset_peer = lc ? { _: 'inputPeerChannel', channel_id: lc.id, access_hash: lc.access_hash } : { _: 'inputPeerEmpty' }; }
      if (ms.length < 20 && !(r.count > out.length)) break;
      await sleep(250);
    }
    return { ch: ch && { title: ch.title, username: ch.username, peerId: ch.peerId }, posts: out };
  }, { ref, q, since, until, limit });
}

const reNorm = s => s && new RegExp(s, 'i');
function filter(posts) {
  const re = reNorm(o.re), not = reNorm(o.not);
  let p = posts.filter(x => (!until || x.date < until) && (!since || x.date >= since)
    && (!re || re.test(x.text)) && (!not || !not.test(x.text))
    && (!o['min-views'] || x.views >= +o['min-views']) && (!o.links || x.links.length)
    && (!o.media || o.media === 'any' || (o.media === 'none' ? !x.media : x.media === o.media)));
  // albums: keep one line per album (the one with text)
  const seen = new Set(); p = p.filter(x => !x.grouped || (seen.has(x.grouped) ? false : (seen.add(x.grouped), true)));
  if (o.top) { const by = o.by || 'views'; const k = x => by === 'er' ? (x.fwd + x.react + x.replies) / Math.max(1, x.views) : x[by];
    p = p.sort((a, b) => k(b) - k(a)).slice(0, +o.top); }
  return p;
}
const d = t => new Date(t * 1000 + 3 * 3600e3).toISOString().slice(0, 16).replace('T', ' '); // MSK
const k = n => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
const line = (x, multi) => `${d(x.date)} 👁${k(x.views)} ↗${x.fwd} ❤${x.react}${x.media ? ' [' + x.media + ']' : ''} ${x.url}${multi ? ' «' + x.src + '»' : ''}\n   ${
  (chars ? x.text.slice(0, chars) + (x.text.length > chars ? '…' : '') : x.text).replace(/\n+/g, ' ⏎ ') || '(без текста)'}`;
const med = a => { const s = [...a].sort((x, y) => x - y); return s.length ? s[s.length >> 1] : 0; };

(async () => {
  if (!cmd || (!target && cmd !== 'find')) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 21).join('\n')); return; }
  await withTg(async pg => {
    const t0 = Date.now(); const all = [];
    if (cmd === 'info') {
      for (const ref of target.split(',')) {
        const r = await pg.evaluate(async ref => { const T = window.__tg; const c = await T.resolve(ref);
          let full = {}; if (c.ent._ === 'channel') full = (await T.api('channels.getFullChannel', { channel: { _: 'inputChannel', channel_id: c.ent.id, access_hash: c.ent.access_hash } })).full_chat;
          const h = await T.api('messages.getHistory', { peer: c.peer, limit: 1, offset_id: 0, add_offset: 0, max_id: 0, min_id: 0, hash: 0 });
          return { title: c.title, username: c.username, peerId: c.peerId, subs: full.participants_count, posts: h.count, last: h.messages[0] && h.messages[0].date,
            about: full.about || '', joined: !c.ent.pFlags?.left, broadcast: !!c.ent.pFlags?.broadcast }; }, ref);
        console.log(`${r.title}  @${r.username || '—'}  id ${r.peerId}  ${r.broadcast ? 'канал' : 'чат'}${r.joined ? ', подписан' : ''}\n` +
          `  подписчиков ${r.subs ?? '?'} · постов ${r.posts} · последний ${r.last ? d(r.last) : '—'}\n  ${r.about.replace(/\n+/g, ' ⏎ ').slice(0, 300)}`);
      }
      return;
    }
    const refs = cmd === 'find' ? [null] : target.split(',');
    const q = cmd === 'find' ? o._.slice(1).join(' ') || o.q : o.q;
    for (const ref of refs) { const r = await fetchPosts(pg, ref, q); all.push(...r.posts.map(p => ({ ...p, src: p.src || (r.ch && r.ch.title) }))); }
    const multi = refs.length > 1 || cmd === 'find';
    const p = filter(all);
    if (o.json) fs.writeFileSync(o.json, JSON.stringify(p, null, 1));
    if (cmd === 'stats') {
      const views = p.map(x => x.views), days = Math.max(1, (Math.max(...p.map(x => x.date)) - Math.min(...p.map(x => x.date))) / DAY);
      const mix = {}; p.forEach(x => mix[x.media || 'text'] = (mix[x.media || 'text'] || 0) + 1);
      const hours = Array(24).fill(0); p.forEach(x => hours[new Date(x.date * 1000 + 3 * 3600e3).getUTCHours()]++);
      const dom = {}; p.forEach(x => x.links.forEach(l => { try { const h = new URL(l).hostname.replace(/^www\./, ''); dom[h] = (dom[h] || 0) + 1; } catch (e) {} }));
      const tags = {}; p.forEach(x => (x.text.match(/#[\wа-яё]+/gi) || []).forEach(t => tags[t.toLowerCase()] = (tags[t.toLowerCase()] || 0) + 1));
      const ads = p.filter(x => /erid|реклама/i.test(x.text)).length;
      const top = (o2, n) => Object.entries(o2).sort((a, b) => b[1] - a[1]).slice(0, n).map(([a, b]) => `${a} ${b}`).join(', ') || '—';
      const er = p.reduce((s, x) => s + x.fwd + x.react + x.replies, 0) / Math.max(1, views.reduce((a, b) => a + b, 0));
      console.log(`постов ${p.length} за ${days.toFixed(1)} дн (${(p.length / days).toFixed(1)}/день) · просмотры ср ${k(Math.round(views.reduce((a, b) => a + b, 0) / Math.max(1, p.length)))}, медиана ${k(med(views))} · ER ${(er * 100).toFixed(2)}% · реклама (erid) ${ads}`);
      console.log(`типы: ${top(mix, 8)}\nчасы МСК (пик): ${top(Object.fromEntries(hours.map((v, i) => [i + 'ч', v])), 5)}\nдомены: ${top(dom, 8)}\nтеги: ${top(tags, 10)}`);
      console.log('топ-5 по просмотрам:'); [...p].sort((a, b) => b.views - a.views).slice(0, 5).forEach(x => console.log(line(x, multi)));
    } else {
      p.forEach(x => console.log(line(x, multi)));
      console.log(`— ${p.length} из ${all.length} загруженных · ${((Date.now() - t0) / 1000).toFixed(1)} с`);
    }
  }, undefined, o.account || 1);
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
