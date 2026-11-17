// Contest base per account: one JSON instead of hand-glued scan/join/enter logs; report in the 06:28 style;
// results check and unsubscribe after the draw. State file lives in the scratchpad (has account data).
// usage: node tg_contests.js <cmd> --state contests_a2.json [...]
//   import  --list list.txt [--scan scan.json] [--enter res.json,res2.json] [--join join.log,join2.log] [--times 06:28.txt]
//           list = report text with lines «• приз: @каналы» under «🗓 ДД.ММ» (the 14:20 format) or «ДД.ММ [ЧЧ:ММ] — приз · …»
//   report  [--title "МОИ РОЗЫГРЫШИ"] [--out report.txt]  — 06:28 style, messages split by ===== (send: tg_post.js)
//   due     [--days 0]                                    — contests whose draw date has come (or comes in N days)
//   results [--days 0] [--account 2]                      — look for the draw post; won / lost / unknown (pult, read-only)
//   leave   [--account 2] [--yes]                         — unsubscribe from channels of LOST contests, except channels
//                                                           still needed by other active contests. Dry run without --yes.
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2));
const [cmd] = o._;
if (!o.state) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 13).join('\n')); process.exit(0); }
const YEAR = new Date().getUTCFullYear();
let db = fs.existsSync(o.state) ? JSON.parse(fs.readFileSync(o.state, 'utf8')) : { contests: [] };
const save = () => fs.writeFileSync(o.state, JSON.stringify(db, null, 1), { mode: 0o600 });
const lc = s => (s || '').toLowerCase();
const dayTs = d => { const m = /^(\d\d)\.(\d\d)/.exec(d || ''); return m ? Date.UTC(YEAR, +m[2] - 1, +m[1]) / 1000 : 0; };
const today = () => Math.floor(Date.now() / 86400e3) * 86400;
const RANK = { won: 9, ok: 8, sub_only: 7, ended: 6, lost: 6, captcha: 5, need_sub: 4, manual: 3, bot_reply: 2, unknown: 1, no_post: 0, '': -1 };
const ICON = { ok: '✓', sub_only: '✓', won: '🏆', lost: '✗', ended: '⏹', captcha: '🧩', need_sub: '➕', manual: '✍️', bot_reply: '❔', unknown: '❔', no_post: '🔍' };

function importAll() {
  const byKey = new Map(db.contests.map(c => [c.key, c]));
  if (o.list) {
    let day = 'без даты';
    for (let l of fs.readFileSync(o.list, 'utf8').split('\n')) {
      l = l.trim();
      if (l.startsWith('🗓')) { day = l.slice(2).trim(); continue; }
      let m = /^•\s*(.+?):\s*((?:@\w+\s*)+|\(.*\))\s*$/.exec(l);
      if (!m) continue;
      const users = (m[2].match(/@(\w+)/g) || []).map(x => x.slice(1));
      const key = `${day}|${users.map(lc).join(',') || lc(m[1])}`;
      if ([...byKey.values()].some(c => c.day === day && users.length && c.channels.map(lc).join() === users.map(lc).join())) continue; // duplicate line
      if (!byKey.has(key)) byKey.set(key, { key, day, time: '', title: m[1].trim(), channels: users, extra: [], organizer: users[0] || '', post: '', bot: '', kind: '', status: users.length ? '' : 'no_post', note: users.length ? '' : 'канал без ссылки' });
    }
  }
  db.contests = [...byKey.values()];
  const byCh = ch => db.contests.filter(c => c.channels.map(lc).includes(lc(ch)));
  if (o.times) for (const l of fs.readFileSync(o.times, 'utf8').split('\n')) {
    const m = /(\d\d\.\d\d) (\d\d:\d\d) — .*?@(\w+)/.exec(l); if (m) for (const c of byCh(m[3])) if (c.day === m[1]) c.time = m[2];
  }
  if (o.scan) for (const r of JSON.parse(fs.readFileSync(o.scan, 'utf8'))) {
    const p = r.posts && r.posts[0];
    for (const c of byCh(r.username || r.ref)) {
      if (!p) { if (!c.post && !c.status) { c.status = 'no_post'; c.note = 'поста о розыгрыше за 30 дней нет'; } continue; }
      if (c.post) continue;
      c.post = p.url; const b = p.buttons.find(b => /t\.me\//.test(b.url));
      c.bot = b ? (b.url.match(/t(?:elegram)?\.me\/([A-Za-z0-9_]+)/) || [])[1] || '' : '';
      c.kind = p.native ? 'native' : b ? 'app' : 'text';
      if (c.kind === 'text') { c.status = /коммент|репост|лайк|сторис|отмет|напиши|поделись|расскаж|ответь/i.test(p.text) ? 'manual' : 'sub_only';
        c.note = c.status === 'manual' ? 'нужно действие по условиям поста (комментарий/репост и т. п.)' : 'участие = подписка'; }
      if (c.kind === 'native') { c.status = 'sub_only'; c.note = 'розыгрыш Telegram — участие = подписка'; }
    }
  }
  if (o.join) for (const f of String(o.join).split(',')) for (const l of fs.readFileSync(f, 'utf8').split('\n')) {
    const m = /^([✓=✗📨]).*?@(\w+)/u.exec(l); if (!m) continue;
    for (const c of byCh(m[2])) { c.joined = c.joined || {}; c.joined[lc(m[2])] = m[1] === '✓' || m[1] === '='; }
  }
  if (o.enter) for (const f of String(o.enter).split(',')) for (const r of JSON.parse(fs.readFileSync(f, 'utf8'))) {
    let st = r.status;
    if (st === 'bot_reply' || st === 'unknown') st = /участник|registered|участвуете/i.test(r.note) ? 'ok' : /человечн|captcha|капч/i.test(r.note) ? 'captcha' : /not subscribed|не подписан|подпишит|необходимо подписаться|не соблюдены/i.test(r.note) ? 'need_sub' : st;
    for (const c of byCh(r.ch)) if ((RANK[st] ?? 0) >= (RANK[c.status] ?? -1) || c.status === 'need_sub' || c.status === 'captcha') { c.status = st; c.note = (r.note || '').slice(0, 160); }
  }
  // sub_only is real only when every channel is joined
  for (const c of db.contests) if (c.status === 'sub_only' && c.joined && c.channels.some(ch => c.joined[lc(ch)] === false)) c.status = 'need_sub';
  save(); const cnt = {}; db.contests.forEach(c => cnt[c.status || '—'] = (cnt[c.status || '—'] || 0) + 1);
  console.log(`конкурсов ${db.contests.length}: ` + Object.entries(cnt).map(([k, v]) => `${ICON[k] || ''}${k} ${v}`).join(', '));
}

function report() {
  const cs = db.contests, n = s => cs.filter(c => [].concat(s).includes(c.status)).length;
  const line = c => `${c.day && /^\d/.test(c.day) ? c.day + (c.time ? ' ' + c.time : '') + ' — ' : ''}${c.title}${c.organizer ? ' · @' + c.organizer : ''}`;
  const sec = (h, st) => { const x = cs.filter(c => [].concat(st).includes(c.status)); return x.length ? `${h} (${x.length}):\n` + x.map(c => '• ' + line(c) + (c.status === 'manual' ? ' — ' + c.note : '')).join('\n') : ''; };
  const act = n(['captcha', 'need_sub', 'manual', 'unknown', 'bot_reply', 'no_post']);
  const head = [`🎁 ${o.title || 'МОИ РОЗЫГРЫШИ'} — обновлено ${new Date().toISOString().slice(8, 10)}.${new Date().toISOString().slice(5, 7)}`, '━━━━━━━━━━━━━━━',
    `📊 Участвуем: ${n(['ok', 'sub_only', 'won'])} из ${cs.length}`, `⚠️ Нужно ваше действие: ${act}`,
    `🏆 Выигрыши: ${n('won') ? cs.filter(c => c.status === 'won').map(c => c.title).join(', ') : 'пока нет'}`, `⏹ Уже завершены: ${n(['ended', 'lost'])}`, '━━━━━━━━━━━━━━━', '',
    '⚠️ НУЖНО ВАШЕ ДЕЙСТВИЕ (с телефона)',
    sec('Капча «Проверка на человечность» — открыть пост и нажать «Участвовать»', 'captcha'),
    sec('Не хватает подписки (каналы-партнёры / приватные) — проверить условия', 'need_sub'),
    sec('Условия поста: комментарий, репост и т. п.', 'manual'),
    sec('Проверить вручную (бот не подтвердил / пост не найден)', ['unknown', 'bot_reply', 'no_post']),
    '', 'Список розыгрышей — в следующем сообщении 👇'].filter(x => x !== '').join('\n').replace('С ТЕЛЕФОНА)\n', 'С ТЕЛЕФОНА)\n');
  const weeks = [['17–23 НОЯБРЯ', d => /\.11/.test(d) && +d.slice(0, 2) <= 23], ['24–30 НОЯБРЯ', d => /\.11/.test(d) && +d.slice(0, 2) >= 24],
    ['ДЕКАБРЬ', d => /\.12/.test(d)], ['БЕЗ ДАТЫ', d => !/^\d\d\.\d\d/.test(d)]];
  const blocks = weeks.map(([h, f]) => { const x = cs.filter(c => f(c.day)); return x.length ? h + '\n' + x.map(c => `${ICON[c.status] || '❔'} ${line(c)}`).join('\n') : ''; }).filter(Boolean);
  const msgs = [head]; let cur = `📅 КОНКУРСЫ (${cs.length}) · ✓ участвуем · 🧩 капча · ➕ подписка · ✍️ условия · ❔ проверить · ⏹ завершён\n`;
  for (const bl of blocks) { if (cur.length + bl.length > 3900) { msgs.push(cur.trim() + '\n\nПродолжение — в следующем сообщении 👇'); cur = ''; } cur += '\n' + bl + '\n'; }
  msgs.push(cur.trim());
  const txt = msgs.join('\n=====\n');
  if (o.out) { fs.writeFileSync(o.out, txt); console.log(`${o.out}: ${msgs.length} сообщ. (${msgs.map(m => m.length).join(', ')} симв.)`); } else console.log(txt);
}

function dueList() { const lim = today() + (+o.days || 0) * 86400; return db.contests.filter(c => dayTs(c.day) && dayTs(c.day) <= lim && !['won', 'lost', 'ended'].includes(c.status)); }

(async () => {
  if (cmd === 'import') return importAll();
  if (cmd === 'report') return report();
  if (cmd === 'due') { const d = dueList(); d.forEach(c => console.log(`${c.day} ${c.time} ${c.title} · @${c.organizer} [${c.status}]`)); console.log(`— ${d.length}`); return; }
  if (cmd === 'results') {
    const due = dueList().filter(c => c.organizer);
    await withTg(async pg => {
      for (const c of due) {
        const r = await pg.evaluate(async ({ ch, since }) => {
          const T = window.__tg; const me = await T.M.appUsersManager.getSelf(); const x = await T.resolve(ch);
          const h = await T.api('messages.getHistory', { peer: x.peer, limit: 60, offset_id: 0, add_offset: 0, max_id: 0, min_id: 0, hash: 0 });
          for (const m of h.messages) {
            if (m.date < since) break;
            if (m.media && m.media._ === 'messageMediaGiveawayResults')
              return { found: m.id, won: (m.media.winners || []).map(String).includes(String(me.id)), how: 'итоги Telegram' };
            const t = T.text(m);
            if (/итог|победител|winner/i.test(t)) {
              const mine = (me.username && new RegExp('@?' + me.username, 'i').test(t)) || (m.entities || []).some(e => String(e.user_id) === String(me.id));
              return { found: m.id, won: mine, how: 'пост с итогами (поиск @username)', text: t.slice(0, 120) };
            }
          }
          return { found: 0 };
        }, { ch: c.organizer, since: dayTs(c.day) - 86400 });
        if (r.found) { c.status = r.won ? 'won' : 'lost'; c.note = `${r.how}: t.me/${c.organizer}/${r.found}`; }
        console.log(`${r.found ? (r.won ? '🏆 ВЫИГРЫШ' : '✗ нас нет') : '… итогов ещё нет'} — ${c.day} ${c.title} @${c.organizer}${r.found ? ' t.me/' + c.organizer + '/' + r.found : ''}`);
        await new Promise(r => setTimeout(r, 400));
      }
    }, undefined, o.account || 1);
    save(); return;
  }
  if (cmd === 'leave') {
    const active = new Set(db.contests.filter(c => !['lost', 'ended'].includes(c.status)).flatMap(c => c.channels.map(lc)));
    const lost = db.contests.filter(c => c.status === 'lost' || c.status === 'ended');
    const go = [...new Set(lost.flatMap(c => c.channels.map(lc)))].filter(ch => !active.has(ch));
    const keep = [...new Set(lost.flatMap(c => c.channels.map(lc)))].filter(ch => active.has(ch));
    if (keep.length) console.log(`оставляю (нужны в других конкурсах): ${keep.map(x => '@' + x).join(' ')}`);
    if (!o.yes) { console.log(`DRY RUN: отписка от ${go.length}: ${go.map(x => '@' + x).join(' ')}\nТолько по команде владельца: --yes`); return; }
    await withTg(async pg => {
      const log = await pg.evaluate(async go => { const T = window.__tg; const out = [];
        for (const ch of go) { try { const c = await T.resolve(ch);
          await T.api('channels.leaveChannel', { channel: { _: 'inputChannel', channel_id: c.ent.id, access_hash: c.ent.access_hash } }); out.push('✓ @' + ch);
        } catch (e) { out.push(`✗ @${ch}: ${e.type || e.message}`); if (/FLOOD/.test(e.type || '')) break; }
        await new Promise(r => setTimeout(r, 3000)); } return out; }, go);
      console.log(log.join('\n'));
    }, undefined, o.account || 1);
    return;
  }
  console.log('команды: import | report | due | results | leave');
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
