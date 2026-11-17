// Send a prepared text (report) as one or several messages — ONLY on the owner's command.
// usage: node tg_post.js <peer> <file.txt> [--yes] [--account 2]
//   peer: me (Saved Messages of the chosen account) | @username | id
//   file: messages separated by a line "=====" (each must be < 4096 chars). @mentions and links become
//         entities (clickable), like messages typed in the app. Without --yes: preview + sizes only.
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2)); // --account 2 = второй аккаунт, только по просьбе владельца
const [peerRef, file] = o._;

// [текст](https://…) → clickable text (messageEntityTextUrl); returns plain text + those entities
function links(src) {
  const e = []; let out = '', last = 0;
  for (const m of src.matchAll(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g)) {
    out += src.slice(last, m.index); e.push({ _: 'messageEntityTextUrl', offset: out.length, length: m[1].length, url: m[2] });
    out += m[1]; last = m.index + m[0].length;
  }
  return { text: out + src.slice(last), e };
}
function entities(t, extra = []) { // UTF-16 offsets — JS string indices are UTF-16 already
  const e = [...extra];
  for (const m of t.matchAll(/(^|[^\w@])@([A-Za-z][A-Za-z0-9_]{3,31})/g)) {
    const off = m.index + m[1].length; e.push({ _: 'messageEntityMention', offset: off, length: m[2].length + 1 });
  }
  for (const m of t.matchAll(/https?:\/\/[^\s]+/g)) if (!extra.some(x => m.index >= x.offset && m.index < x.offset + x.length)) e.push({ _: 'messageEntityUrl', offset: m.index, length: m[0].length });
  return e.sort((a, b) => a.offset - b.offset);
}

(async () => {
  if (!peerRef || !file) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 6).join('\n')); return; }
  const parsed = fs.readFileSync(file, 'utf8').split(/\n=====\n/).map(s => s.trim()).filter(Boolean).map(links);
  const msgs = parsed.map(p => p.text), ents = parsed.map(p => entities(p.text, p.e));
  const big = msgs.findIndex(m => m.length > 4096);
  if (big >= 0) throw new Error(`сообщение ${big + 1} длиннее 4096 символов (${msgs[big].length})`);
  await withTg(async pg => {
    const res = await pg.evaluate(async ({ peerRef, msgs, ents, yes }) => {
      const T = window.__tg;
      let peer, title;
      if (peerRef === 'me') { const u = await T.M.appUsersManager.getSelf(); peer = { _: 'inputPeerSelf' }; title = 'Избранное (' + u.first_name + ')'; }
      else { const c = await T.resolve(peerRef); peer = c.peer; title = c.title; }
      if (!yes) return { title, sent: [] };
      const sent = [];
      for (let i = 0; i < msgs.length; i++) {
        const rnd = BigInt.asIntN(64, BigInt(Math.floor(Math.random() * 2 ** 52)) * 4096n + BigInt(i)).toString();
        const r = await T.api('messages.sendMessage', { peer, message: msgs[i], entities: ents[i], random_id: rnd, pFlags: { no_webpage: true } });
        const id = (r.updates || []).map(u => u.id || (u.message && u.message.id)).find(Boolean) || r.id;
        sent.push(id || '?'); await new Promise(r => setTimeout(r, 700));
      }
      return { title, sent };
    }, { peerRef, msgs, ents, yes: !!o.yes });
    msgs.forEach((m, i) => console.log(`#${i + 1} ${m.length} симв., ${ents[i].length} ссылок: ${m.split('\n')[0].slice(0, 70)}`));
    console.log(o.yes ? `отправлено в «${res.title}»: ${res.sent.length} сообщ. (id ${res.sent.join(', ')})`
      : `DRY RUN → «${res.title}». Отправка только по команде владельца: добавьте --yes`);
  }, undefined, o.account || 1);
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
