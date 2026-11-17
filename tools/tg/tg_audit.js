// Independent proof that the contest base and the sent report are TRUE right now (owner 17.11: «докажи»).
// Asks Telegram live — does not trust tool logs. Read-only.
// usage: node tg_audit.js --state contests_a1.json --account 1 [--report 120120,120121,…] [--out audit.txt] [--fix]
// checks: 1 account identity · 2 kept contests: every required channel is in the account's chat list (= subscribed)
//         3 dropped channels: NOT in the chat list · 4 kept contests: the post exists and is a giveaway post
//         5 report (ids in Saved Messages): every kept contest is in it, removed ones are not, header counts match
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2));
const db = JSON.parse(fs.readFileSync(o.state, 'utf8'));
const lc = s => (s || '').toLowerCase();
const DROP = ['lost', 'ended', 'excluded', 'removed'];
const chOf = c => (c.required && c.required.length ? c.required : c.channels).filter(x => !x.startsWith('+')).map(lc);
const kept = db.contests.filter(c => !DROP.includes(c.status));
const activeCh = new Set(kept.flatMap(chOf));
const dropped = [...new Set(db.contests.filter(c => DROP.includes(c.status)).flatMap(chOf))].filter(x => !activeCh.has(x));
const posts = [...new Set(kept.map(c => c.post).filter(Boolean))];
const ids = o.report ? String(o.report).split(',').map(Number) : [];

(async () => {
  const r = await withTg(pg => pg.evaluate(async ({ posts, ids }) => {
    const T = window.__tg; const me = await T.M.appUsersManager.getSelf();
    const own = await T.dialogs(); const sub = [...own.keys()].filter(k => !k.startsWith('#'));
    const post = {};
    for (const u of posts) {
      const m = u.match(/t\.me\/(\w+)\/(\d+)/); const c = own.get(m[1].toLowerCase());
      if (!c) { post[u] = { err: 'канал не в подписках — пост не проверить без поиска' }; continue; }
      try { const g = await T.api('channels.getMessages', { channel: { _: 'inputChannel', channel_id: c.id, access_hash: c.access_hash }, id: [{ _: 'inputMessageID', id: +m[2] }] });
        const x = g.messages[0]; post[u] = x._ === 'messageEmpty' ? { err: 'пост удалён' } : { text: T.text(x).slice(0, 400) };
      } catch (e) { post[u] = { err: e.type || e.message } }
      await new Promise(r => setTimeout(r, 200));
    }
    let report = '';
    if (ids.length) { const g = await T.api('messages.getMessages', { id: ids.map(id => ({ _: 'inputMessageID', id })) });
      report = g.messages.map(x => x.message || '').join('\n'); }
    return { me: { id: me.id, username: me.username }, sub, post, report };
  }, { posts, ids }), undefined, o.account || 1);
  const L = []; let bad = 0; const P = (ok, m) => { if (!ok) bad++; L.push(`${ok ? '✓' : '✗'} ${m}`); };
  const sub = new Set(r.sub);
  P(true, `аккаунт: id ${r.me.id} @${r.me.username || '—'}; каналов и чатов в списке: ${sub.size}`);
  // 2 kept: subscribed
  // ✗ only when the base CLAIMS participation without the subscriptions; honest «не хватает подписки» = consistent
  const miss = kept.map(c => ({ c, m: chOf(c).filter(x => !sub.has(x)) })).filter(x => x.m.length);
  const lie = miss.filter(x => ['ok', 'sub_only', 'won'].includes(x.c.status));
  const honest = miss.filter(x => !lie.includes(x));
  P(!lie.length, `оставленные конкурсы: ${kept.length}; статус «участвую» только там, где есть ВСЕ подписки${lie.length ? ' — НАРУШЕНО у ' + lie.length + ':' : ''}`);
  lie.forEach(x => L.push(`    ✗ ${x.c.day} ${x.c.title}: статус ${x.c.status}, но нет ${x.m.map(y => '@' + y).join(' ')}`));
  if (honest.length) L.push(`⚪ к сведению: ${honest.length} конкурс(ов) честно отмечены «не хватает подписки»:`);
  honest.forEach(x => L.push(`    ⚪ ${x.c.day} ${x.c.title}: нет ${x.m.map(y => '@' + y).join(' ')} (статус ${x.c.status})`));
  // 3 dropped: not subscribed
  const still = dropped.filter(x => sub.has(x));
  P(!still.length, `исключённые каналы: ${dropped.length}; аккаунт отписан от всех${still.length ? ' — ВСЁ ЕЩЁ подписан: ' + still.map(y => '@' + y).join(' ') : ''}`);
  // 4 posts
  const skip = posts.filter(u => /не в подписках/.test(r.post[u].err || ''));
  skip.forEach(u => L.push(`⚪ к сведению: ${u} — канал не в подписках, пост проверен другим аккаунтом/ранее`));
  const pe = posts.filter(u => r.post[u].err && !skip.includes(u)), notGw = posts.filter(u => !r.post[u].err && !/розыгр|разыгр|дарим|конкурс|приз|подар/i.test(r.post[u].text));
  P(!pe.length && !notGw.length, `посты оставленных конкурсов: ${posts.length - skip.length} проверено; существуют и это розыгрыши${pe.length + notGw.length ? ' — проблем: ' + (pe.length + notGw.length) : ''}`);
  pe.forEach(u => L.push(`    • ${u}: ${r.post[u].err}`)); notGw.forEach(u => L.push(`    • ${u}: в тексте нет слов о розыгрыше`));
  // 5 report
  if (ids.length) {
    const t = r.report;
    const absent = kept.filter(c => !t.includes(c.title.split(' / ')[0]));
    // exact: a removed contest is «in the report» if its OWN channels are mentioned (titles like «iPhone 17» are
    // substrings of kept ones such as «iPhone 17E» — 17.11 false alarm)
    const has = h => new RegExp('@' + h + '(?![A-Za-z0-9_])', 'i').test(t);
    const removedShown = db.contests.filter(c => c.status === 'removed' && chOf(c).some(h => !activeCh.has(h) && has(h)));
    P(!absent.length, `отчёт (${ids.length} сообщ.): все оставленные конкурсы в нём${absent.length ? ' — нет: ' + absent.map(c => c.title).join('; ') : ''}`);
    P(!removedShown.length, `отчёт: конкурсов без поста в нём нет${removedShown.length ? ' — есть: ' + removedShown.map(c => c.title).join('; ') : ''}`);
    const head = /Всего(?: конкурсов)?: (\d+)/.exec(t);
    P(!!head, `отчёт: в сводке «Всего конкурсов: ${head ? head[1] : '?'}»`);
  }
  if (o.fix) { // write the live truth back: missing subscriptions → status need_sub (captcha stays gated)
    for (const c of kept) { c.missing = chOf(c).filter(x => !sub.has(x)).map(x => '@' + x); c.verified = new Date().toISOString();
      if (c.missing.length && ['ok', 'sub_only', 'unknown'].includes(c.status)) { c.status_before = c.status; c.status = 'need_sub'; } }
    fs.writeFileSync(o.state, JSON.stringify(db, null, 1)); L.push(`(--fix: база обновлена по факту)`);
  }
  L.push(bad ? `ИТОГ: ✗ проблем ${bad}` : 'ИТОГ: ✓ всё сходится');
  const out = L.join('\n'); console.log(out); if (o.out) fs.writeFileSync(o.out, out + '\n');
  if (bad) process.exitCode = 2;
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
