// Personal chat: send lines in the owner's manner (one short message per line, human-like pauses) and watch for
// the reply. ONLY on the owner's command; the main account by default.
// usage: node tg_dm.js send  <user_id|@username> <file.txt> [--yes] [--account 1]   — each non-empty line = one message
//        node tg_dm.js watch <user_id|@username> [--minutes 60] [--quiet 60] [--account 1]
//        node tg_dm.js tail  <user_id|@username> [--n 20] [--account 1]          — last messages (does NOT mark read)
// watch: waits for NEW incoming messages after the last one, then waits --quiet seconds for the rest of the burst
//        and prints them; exits 0 with the reply, 3 on timeout. Reading via API does not mark the chat as read.
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2));
const [cmd, ref, file] = o._;
const acc = o.account || 1;
const peerOf = async (pg, ref) => pg.evaluate(async ref => {
  const T = window.__tg; let u;
  if (/^\d+$/.test(String(ref))) u = await T.M.appUsersManager.getUser(+ref); else u = (await T.resolve(ref)).ent;
  if (!u || !u.access_hash) throw new Error('пользователь не найден: ' + ref);
  window.__dmPeer = { _: 'inputPeerUser', user_id: u.id, access_hash: u.access_hash };
  return [u.first_name, u.last_name].filter(Boolean).join(' ') + (u.username ? ' @' + u.username : '');
}, String(ref));
const hist = (pg, n) => pg.evaluate(async n => { const T = window.__tg;
  const h = await T.api('messages.getHistory', { peer: window.__dmPeer, limit: n, offset_id: 0, add_offset: 0, max_id: 0, min_id: 0, hash: 0 });
  return h.messages.map(m => ({ id: m.id, out: !!(m.pFlags && m.pFlags.out), date: m.date, text: T.text(m) || (m.media ? '[' + m.media._.replace('messageMedia', '') + ']' : '') })); }, n);
const t = d => new Date(d * 1000 + 3 * 3600e3).toISOString().slice(11, 16);

(async () => {
  if (!['send', 'watch', 'tail'].includes(cmd) || !ref) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 9).join('\n')); return; }
  await withTg(async pg => {
    const who = await peerOf(pg, ref);
    if (cmd === 'tail') { (await hist(pg, +(o.n || 20))).reverse().forEach(m => console.log(`${t(m.date)} ${m.out ? 'Я' : '→'} ${m.text}`)); return; }
    if (cmd === 'send') {
      const lines = fs.readFileSync(file, 'utf8').split('\n').map(s => s.trim()).filter(Boolean);
      console.log(`→ ${who}: ${lines.length} сообщ.`); lines.forEach(l => console.log('   ' + l));
      if (!o.yes) { console.log('DRY RUN. Отправка только по команде владельца: --yes'); return; }
      const ids = await pg.evaluate(async lines => { const T = window.__tg; const ids = [];
        for (const l of lines) {
          await new Promise(r => setTimeout(r, 900 + Math.min(2500, l.length * 120) + Math.random() * 700)); // «печатает»
          const r = await T.api('messages.sendMessage', { peer: window.__dmPeer, message: l, random_id: String(Math.floor(Math.random() * 2 ** 52)) });
          ids.push((r.updates || []).map(u => u.id || (u.message && u.message.id)).find(Boolean) || r.id);
        } return ids; }, lines);
      console.log(`отправлено: ${ids.length} (id ${ids[0]}…${ids[ids.length - 1]})`); return;
    }
    // watch
    const last = (await hist(pg, 1))[0]; const since = last ? last.id : 0;
    const until = Date.now() + (+o.minutes || 60) * 60e3; console.log(`жду ответ от ${who} (после id ${since})…`);
    let got = [];
    while (Date.now() < until) {
      await pg.waitForTimeout(20000);
      const h = (await hist(pg, 50)).filter(m => m.id > since && !m.out);
      if (h.length) { got = h; break; }
    }
    if (!got.length) { console.log('ответа нет'); process.exitCode = 3; return; }
    let quietEnd = Date.now() + (+o.quiet || 60) * 1000; // collect the burst: he writes many short messages
    while (Date.now() < quietEnd) { await pg.waitForTimeout(10000);
      const h = (await hist(pg, 80)).filter(m => m.id > since); if (h.length > got.length) { got = h; quietEnd = Date.now() + (+o.quiet || 60) * 1000; } }
    console.log('ОТВЕТ:'); got.sort((a, b) => a.id - b.id).forEach(m => console.log(`${t(m.date)} ${m.out ? 'Я' : '→'} ${m.text}`));
  }, undefined, acc);
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
