// Contest base per account: one JSON instead of hand-glued scan/join/enter logs; report in the 06:28 style;
// results check and unsubscribe after the draw. State file lives in the scratchpad (has account data).
// usage: node tg_contests.js <cmd> --state contests_a2.json [...]
//   import  --list list.txt [--scan scan.json] [--enter res.json,res2.json] [--join join.log,join2.log] [--times 06:28.txt]
//           list = report text with lines «• приз: @каналы» under «🗓 ДД.ММ» (the 14:20 format) or «ДД.ММ [ЧЧ:ММ] — приз · …»
//   report  [--title "МОИ РОЗЫГРЫШИ"] [--captcha] [--out report.txt] — grouped by action, link to each post;
//           --captcha = separate «я не бот» report. Messages split by ===== (send: tg_post.js)
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
    if (st === 'bot_reply' || st === 'unknown') st = /участник|registered|участвуете|participating|все условия выполнены/i.test(r.note) && !/^[^|]*(not subscribed|не подписан)/i.test(r.note) ? 'ok' : /человечн|captcha|капч/i.test(r.note) ? 'captcha' : /not subscribed|не подписан|подпишит|необходимо подписаться|не соблюдены/i.test(r.note) ? 'need_sub' : st;
    for (const c of byCh(r.ch)) if ((RANK[st] ?? 0) >= (RANK[c.status] ?? -1) || c.status === 'need_sub' || c.status === 'captcha') { c.status = st; c.note = (r.note || '').slice(0, 160); }
  }
  // sub_only is real only when every channel is joined
  for (const c of db.contests) if (c.status === 'sub_only' && c.joined && c.channels.some(ch => c.joined[lc(ch)] === false)) c.status = 'need_sub';
  save(); const cnt = {}; db.contests.forEach(c => cnt[c.status || '—'] = (cnt[c.status || '—'] || 0) + 1);
  console.log(`конкурсов ${db.contests.length}: ` + Object.entries(cnt).map(([k, v]) => `${ICON[k] || ''}${k} ${v}`).join(', '));
}

// Report v2 (owner 17.11): no icon mosaic. Contests are GROUPED by what to do; the status is written in words
// in the section header with a short "what to do"; every line = date/time of the draw · prize · channel ·
// clickable «перейти на пост» (the giveaway post itself). Nothing is cut. --captcha = separate "I'm not a bot" report.
const SECTIONS = [
  { st: ['captcha'], h: '🤖 ПОДТВЕРДИТЬ «Я НЕ БОТ»', how: 'Нажмите «перейти на пост» → под постом «Участвовать» → пройдите проверку «я не бот» → дождитесь «Вы участвуете».', link: 'перейти на пост', act: 1 },
  { st: ['manual'], h: '✍️ ВЫПОЛНИТЬ УСЛОВИЕ ПОСТА', how: 'По условиям нужен комментарий, репост, лайк и т. п. — откройте пост и сделайте, что там написано.', link: 'перейти на пост', act: 1 },
  { st: ['unknown', 'bot_reply', 'error'], h: '🔎 ПРОВЕРИТЬ ВРУЧНУЮ', how: 'Бот не ответил ясно, участвуем ли мы. Откройте пост, нажмите «Участвовать» и посмотрите ответ.', link: 'перейти на пост', act: 1, note: 1 },
  { st: ['need_sub'], h: '⏳ ЖДЁТ ПОДПИСКИ НА КАНАЛЫ-ПАРТНЁРЫ', how: 'Telegram временно ограничил подписки — подпишу сам после снятия лимита и нажму «Участвовать». От вас ничего не нужно.', link: 'пост' },
  { st: ['won'], h: '🏆 ВЫИГРЫШ', how: 'Напишите организатору, от каналов не отписывайтесь до получения приза.', link: 'пост с итогами', act: 1 },
  { st: ['ok'], h: '✅ УЧАСТВУЮ — БОТ ПОДТВЕРДИЛ', how: 'Ничего делать не нужно. Не отписывайтесь от каналов до итогов.', link: 'пост' },
  { st: ['sub_only'], h: '☑️ УЧАСТВУЮ ПО ПОДПИСКЕ', how: 'Условие — только подписка, она оформлена. Бот такие конкурсы не подтверждает — проверим по итогам.', link: 'пост' },
  { st: ['no_post'], h: '📭 ПОДПИСАН, ПОСТ НЕ НАЙДЕН', how: 'На каналы подписан, но пост розыгрыша не нашёлся (закреп, старый пост или розыгрыш у партнёра). Загляните в канал при случае.', link: 'перейти в канал' },
  { st: ['lost'], h: '✗ ИТОГИ: НАС НЕТ', how: 'Можно отписываться (tg_contests.js leave).', link: 'пост с итогами' },
  { st: ['ended'], h: '⛔ РОЗЫГРЫШ УЖЕ ЗАВЕРШЁН', how: 'Участие невозможно — бот ответил «розыгрыш завершён».', link: 'пост' },
];
const sortKey = c => dayTs(c.day) ? dayTs(c.day) + (c.time ? +c.time.slice(0, 2) * 3600 + +c.time.slice(3) * 60 : 86399) : 9e12;
function itemLine(c, sec) {
  const date = /^\d\d\.\d\d/.test(c.day) ? `📅 ${c.day}${c.time ? ' ' + c.time : ''}${dayTs(c.day) === today() ? ' ❗ СЕГОДНЯ' : ''}` : '📅 без даты';
  const url = c.post || (c.organizer ? `https://t.me/${c.organizer}` : '');
  const chans = c.channels.length ? c.channels.map(x => '@' + x).join(' ') : `канал без ссылки — искать в архиве по названию`;
  const link = url ? ` — [${c.post ? sec.link : 'перейти в канал'}](${url})` : '';
  const why = sec.note && c.note ? `\n   ↳ ${c.note.replace(/\s+/g, ' ').slice(0, 110)}` : '';
  return `• ${date} — ${c.title}${link}\n   каналы: ${chans}${why}`;
}
function pack(head, blocks, cont) {
  const msgs = [head]; let cur = '';
  for (const bl of blocks) for (const part of bl) {
    if (cur && cur.length + part.length + 2 > 3800) { msgs.push(cur.trim() + `\n\n${cont} 👇`); cur = ''; }
    cur += part + '\n';
  }
  if (cur.trim()) msgs.push(cur.trim()); return msgs;
}
function report() {
  const cs = db.contests, by = st => cs.filter(c => [].concat(st).includes(c.status)).sort((a, b) => sortKey(a) - sortKey(b));
  const stamp = new Date(Date.now() + 3 * 3600e3).toISOString(); const upd = `${stamp.slice(8, 10)}.${stamp.slice(5, 7)} ${stamp.slice(11, 16)} МСК`;
  const secs = o.captcha ? SECTIONS.filter(x => x.st.includes('captcha')) : SECTIONS;
  const n = st => by(st).length;
  let head;
  if (o.captcha) {
    const x = by('captcha');
    head = [`🤖 ПОДТВЕРДИТЬ «Я НЕ БОТ» — обновлено ${upd}`, '━━━━━━━━━━━━━━━', `📊 Конкурсов с проверкой: ${x.length}`,
      `⏰ Ближайший срок: ${x[0] ? x[0].day + (x[0].time ? ' ' + x[0].time : '') : '—'}`, '━━━━━━━━━━━━━━━', '',
      'ЧТО ДЕЛАТЬ (с телефона, в приложении Telegram):', '1. Нажмите «перейти на пост» у конкурса', '2. Под постом нажмите «Участвовать»',
      '3. Пройдите проверку «я не бот»', '4. Дождитесь надписи «Вы участвуете в розыгрыше»', '5. Кнопки «Буст», «Голосовать», «Забрать подарок» — НЕ нужны',
      '', 'Сначала — те, у кого итоги раньше. Список — в следующем сообщении 👇'].join('\n');
  } else {
    head = [`🎁 ${o.title || 'МОИ РОЗЫГРЫШИ'} — обновлено ${upd}`, '━━━━━━━━━━━━━━━', `📊 Всего конкурсов: ${cs.length}`, '',
      'НУЖНО ВАШЕ ДЕЙСТВИЕ:', ...SECTIONS.filter(x => x.act).map(x => `${x.h}: ${n(x.st)}`), '',
      'ДЕЛАЮ САМ / НИЧЕГО НЕ НУЖНО:', ...SECTIONS.filter(x => !x.act).map(x => `${x.h}: ${n(x.st)}`), '━━━━━━━━━━━━━━━', '',
      'КАК ЧИТАТЬ: конкурсы разбиты по разделам — в заголовке раздела написано, что с ними делать.',
      'В каждой строке: 📅 дата и время итогов (МСК) — приз — ссылка на пост розыгрыша; ниже — каналы конкурса.',
      'Внутри раздела — по дате итогов, сначала ближайшие.', '', 'Списки по разделам — в следующих сообщениях 👇'].join('\n');
  }
  // one line per giveaway post: contests that point to the same post are merged (titles joined, channels united)
  const merge = list => { const out = [], seen = new Map();
    for (const c of list) { const k = c.post || c.key; const m = seen.get(k);
      if (m) { if (!m.title.includes(c.title)) m.title += ' / ' + c.title; m.channels = [...new Set([...m.channels, ...c.channels])]; if (!/^\d/.test(m.day) && /^\d/.test(c.day)) { m.day = c.day; m.time = c.time; } continue; }
      const cp = { ...c, channels: [...c.channels] }; seen.set(k, cp); out.push(cp); }
    return out.sort((a, b) => sortKey(a) - sortKey(b)); };
  const blocks = secs.map(sec => { const x = merge(by(sec.st)); if (!x.length) return [];
    return [`\n${sec.h} — ${x.length}\n${sec.how}\n`, ...x.map(c => itemLine(c, sec))]; }).filter(b => b.length);
  const msgs = pack(head, blocks, 'Продолжение — в следующем сообщении');
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
