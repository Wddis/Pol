// Giveaways: find the contest post in channels and show HOW to take part; join on the owner's command.
// usage: node tg_giveaways.js scan <@ch,@ch2…|--file list.txt> [--days 30] [--json out.json] [--account 2]
//        node tg_giveaways.js join <@ch…|t.me/+invite|--file f> [--archive] [--pace 6] [--yes] [--account 2]
//        node tg_giveaways.js discover [--days 3] [--min-views 5000] [--state contests.json] [--q "розыгрыш,разыгрываем"]
//        node tg_giveaways.js enter --scan scan.json [--only @a,@b] [--yes] [--account 2] [--out res.json]
// scan  — per channel: the latest giveaway post (native Telegram giveaway or a post with "розыгрыш" + buttons):
//         type, end date, required channels (native), buttons (url/bot/webapp/callback). Read-only.
// enter — press "Участвовать" of each scanned giveaway: mini-app (t.me/<bot>/<app>?startapp=…) is opened like the
//         client does (requestAppWebView) and read; inside, a plain "Участвовать" button is pressed; bot links
//         (?start=…) start the bot and read its reply. Never: captchas, votes/boosts, "gifts", payments.
// discover — NEW giveaways across all public channels (server-side global search), minus channels already in
//         the contest base (--state); compact list sorted by views, with how to enter. Read-only.
// join  — subscribe to channels (channels.joinChannel), paced against flood limits; --archive moves them to
//         the Archive right away. Without --yes: dry run. Captchas/web apps are NOT solved: the owner does those.
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2)); // --account 2 = второй аккаунт, только по просьбе владельца
const [cmd, ...rest] = o._;
function refs() {
  let src = rest.join(' '); if (o.file) src += ' ' + fs.readFileSync(o.file, 'utf8');
  const out = [];
  for (const m of src.matchAll(/(?:t(?:elegram)?\.me\/(?:\+|joinchat\/)([A-Za-z0-9_-]{8,}))|(?:(?:t(?:elegram)?\.me\/(?!c\/|\+|joinchat)|@)([A-Za-z][A-Za-z0-9_]{3,31}))/g))
    out.push(m[1] ? '+' + m[1] : m[2].toLowerCase()); // '+hash' = private invite link
  return [...new Set(out)];
}
async function discover() {
  const known = new Set(o.state && fs.existsSync(o.state) ? JSON.parse(fs.readFileSync(o.state, 'utf8')).contests.flatMap(c => c.channels.map(x => x.toLowerCase())) : []);
  const qs = String(o.q || 'розыгрыш,разыгрываем,дарим').split(',');
  const since = Math.floor(Date.now() / 1000) - (+o.days || 3) * 86400, minV = +(o['min-views'] || 3000);
  await withTg(async pg => {
    const found = await pg.evaluate(async ({ qs, since }) => { const T = window.__tg; const out = {};
      for (const q of qs) { let rate = 0, peer = { _: 'inputPeerEmpty' }, id = 0;
        for (let page = 0; page < 3; page++) {
          const r = await T.api('messages.searchGlobal', { q, filter: { _: 'inputMessagesFilterEmpty' }, min_date: since, max_date: 0, offset_rate: rate, offset_peer: peer, offset_id: id, limit: 100, pFlags: { broadcasts_only: true } });
          const chats = Object.fromEntries((r.chats || []).map(c => [c.id, c]));
          for (const m of r.messages || []) { const c = chats[m.peer_id && m.peer_id.channel_id]; if (!c) continue;
            const u = T.uname(c); const k = u || String(c.id); const t = T.text(m);
            if (!/розыгрыш|разыгрыва|дарим|giveaway/i.test(t) || /итог|победител/i.test(t)) continue;
            const btn = ((m.reply_markup && m.reply_markup.rows) || []).flatMap(r => r.buttons).map(b => (b.type || b).url || b.url || '').find(x => /t\.me/.test(x)) || '';
            const how = m.media && /Giveaway/.test(m.media._) ? 'TG-розыгрыш' : btn ? 'кнопка ' + (btn.match(/t\.me\/(\w+)/) || [])[1] : 'текст';
            if (!out[k] || out[k].views < (m.views || 0)) out[k] = { u, title: c.title, id: m.id, views: m.views || 0, date: m.date, how, text: t.slice(0, 110) }; }
          if (!r.messages || r.messages.length < 100) break;
          const last = r.messages[r.messages.length - 1]; rate = r.next_rate || last.date; id = last.id;
          const lc = chats[last.peer_id.channel_id]; peer = lc ? T.inputOf(lc) : peer;
          await new Promise(r => setTimeout(r, 400)); }
      } return Object.values(out); }, { qs, since });
    const list = found.filter(x => x.views >= minV && !known.has((x.u || '').toLowerCase())).sort((a, b) => b.views - a.views);
    for (const x of list) console.log(`${d(x.date)} 👁${(x.views / 1000).toFixed(1)}k ${x.u ? 'https://t.me/' + x.u + '/' + x.id : x.title} [${x.how}]\n   ${x.title}: ${x.text.replace(/\n+/g, ' ⏎ ')}`);
    console.log(`— новых: ${list.length} (всего найдено ${found.length}, уже в базе/мало просмотров отсеяно)`);
  }, undefined, o.account || 1);
}
async function enter() {
  const scan = JSON.parse(fs.readFileSync(o.scan, 'utf8'));
  const only = o.only ? new Set(String(o.only).split(',').map(x => x.replace('@', '').toLowerCase())) : null;
  const jobs = [];
  for (const r of scan) {
    const p = r.posts && r.posts[0]; if (!p || p.native) continue;
    if (only && !only.has((r.username || '').toLowerCase())) continue;
    const b = p.buttons.find(b => b.type === 'app' || b.type === 'botstart' || /t\.me\//.test(b.url));
    if (b) jobs.push({ ch: r.username, title: r.title, post: p.url, btn: b });
  }
  const results = [];
  await withTg(async pg => {
    for (const j of jobs) {
      const u = new URL(j.btn.url.replace('telegram.me', 't.me'));
      const [bot, app] = u.pathname.split('/').filter(Boolean);
      const sp = u.searchParams.get('startapp'), st = u.searchParams.get('start');
      let res = { ch: j.ch, title: j.title, post: j.post, bot, status: '', note: '' };
      if (!o.yes) { res.status = 'dry'; res.note = `${app ? 'app ' + bot + '/' + app : 'bot ' + bot} ${sp || st || ''}`; results.push(res); console.log(`· @${j.ch}: ${res.note}`); continue; }
      try {
        if (app || sp !== null) {
          const url = await pg.evaluate(async ({ bot, app, sp }) => { const T = window.__tg; const b = await T.resolve(bot);
            const inUser = { _: 'inputUser', user_id: b.ent.id, access_hash: b.ent.access_hash };
            const r = app ? await T.api('messages.requestAppWebView', { peer: { _: 'inputPeerSelf' }, app: { _: 'inputBotAppShortName', bot_id: inUser, short_name: app }, start_param: sp || '', platform: 'android', pFlags: { write_allowed: true } })
              : await T.api('messages.requestMainWebView', { peer: { _: 'inputPeerSelf' }, bot: inUser, start_param: sp || '', platform: 'android' });
            return r.url; }, { bot, app, sp });
          const ap = await pg.context().newPage(); await ap.setViewportSize({ width: 420, height: 860 });
          try {
            await ap.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }); await ap.waitForTimeout(7000);
            const read = async () => (await ap.evaluate(() => document.body ? document.body.innerText : '').catch(() => '')).replace(/\s+/g, ' ');
            let t = await read();
            const YES = /(вы|ты) (уже )?участвуете|теперь вы участвуете|вы участник|участие принято|you are participating|you're in|успешно/i;
            if (!YES.test(t)) {
              const btn = ap.locator('button, [role=button], a').filter({ hasText: /^\s*(Участвовать|Принять участие|Участвую|Join|Participate)\s*$/i }).first();
              if (await btn.count()) { await btn.click({ timeout: 5000 }).catch(() => {}); await ap.waitForTimeout(6000); t = await read(); }
            }
            const cap = /капч|captcha|робот|не робот|human|turnstile|выберите (все )?изображ/i.test(t) || await ap.locator('iframe[src*="turnstile"], iframe[src*="captcha"], iframe[src*="recaptcha"]').count();
            res.status = YES.test(t) ? 'ok' : cap ? 'captcha' : /подпиш|subscribe|не подписан/i.test(t) ? 'need_sub' : /заверш|окончен|ended|finished|итоги/i.test(t) ? 'ended' : 'unknown';
            res.note = t.slice(0, 220);
            if (res.status !== 'ok') await ap.screenshot({ path: (o.shots || '.') + `/gw_${j.ch}.png` }).catch(() => {});
          } finally { await ap.close().catch(() => {}); }
        } else {
          const reply = await pg.evaluate(async ({ bot, st }) => { const T = window.__tg; const b = await T.resolve(bot);
            const inUser = { _: 'inputUser', user_id: b.ent.id, access_hash: b.ent.access_hash };
            const rnd = String(Math.floor(Math.random() * 2 ** 52));
            await T.api('messages.startBot', { bot: inUser, peer: b.peer, random_id: rnd, start_param: st || '' });
            await new Promise(r => setTimeout(r, 6000));
            const h = await T.api('messages.getHistory', { peer: b.peer, limit: 3, offset_id: 0, add_offset: 0, max_id: 0, min_id: 0, hash: 0 });
            return h.messages.filter(m => !m.pFlags.out).map(m => T.text(m) + ' ' + JSON.stringify(m.reply_markup && m.reply_markup.rows ? m.reply_markup.rows.flatMap(r => r.buttons.map(x => x.text)) : [])).join(' | '); }, { bot, st });
          const t = reply.replace(/\s+/g, ' ');
          res.status = /(вы|ты) (уже )?участв|участие принято|теперь вы участвуете|успешно/i.test(t) ? 'ok' : /капч|captcha|робот/i.test(t) ? 'captcha' : /подпиш/i.test(t) ? 'need_sub' : 'bot_reply';
          res.note = t.slice(0, 220);
        }
      } catch (e) { res.status = 'error'; res.note = String(e.message || e).slice(0, 160); }
      results.push(res);
      console.log(`${{ ok: '✓', captcha: '🧩', need_sub: '➕', ended: '⏹', error: '✗' }[res.status] || '?'} @${j.ch} [${bot}] ${res.status}: ${res.note.slice(0, 140)}`);
      await new Promise(r => setTimeout(r, 2500));
    }
  }, undefined, o.account || 1);
  if (o.out) fs.writeFileSync(o.out, JSON.stringify(results, null, 1));
  if (!o.yes) console.log(`DRY RUN: ${jobs.length} розыгрышей. Участие только по команде владельца: --yes`);
}
const d = t => t ? new Date(t * 1000 + 3 * 3600e3).toISOString().slice(0, 16).replace('T', ' ') : '';

(async () => {
  if (cmd === 'enter') return enter();
  if (cmd === 'discover') return discover();
  if (!['scan', 'join'].includes(cmd)) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 9).join('\n')); return; }
  const list = refs(); if (!list.length) throw new Error('нет каналов');
  await withTg(async pg => {
    if (cmd === 'scan') {
      const since = Math.floor(Date.now() / 1000) - (+o.days || 30) * 86400;
      const out = [];
      for (const ref of list) {
        const r = await pg.evaluate(async ({ ref, since }) => {
          const T = window.__tg; const sleep = ms => new Promise(r => setTimeout(r, ms));
          let c; try { c = await T.resolve(ref); } catch (e) { return { ref, error: e.type || e.message }; }
          const h = await T.api('messages.getHistory', { peer: c.peer, limit: 100, offset_id: 0, add_offset: 0, max_id: 0, min_id: 0, hash: 0 });
          const chats = Object.fromEntries((h.chats || []).map(x => [x.id, x]));
          const found = [];
          for (const m of h.messages) {
            if (m._ !== 'message' || m.date < since) continue;
            const t = T.text(m);
            const g = m.media && /Giveaway/.test(m.media._) ? m.media : null;
            // old layout: keyboardButtonUrl{url}; new: keyboardInlineButton{type:{_:'inlineButtonTypeUrl',url}}
            const btns = ((m.reply_markup && m.reply_markup.rows) || []).flatMap(r => r.buttons).map(b => {
              const ty = b.type || b; const url = ty.url || b.url || '';
              const kind = (ty._ || b._).replace(/^(inlineButtonType|keyboardButton)/, '').toLowerCase();
              return { type: /startapp=|\/[A-Za-z0-9_]+\?startapp|webview|webapp/i.test(url + kind) ? 'app' : /[?&]start=/.test(url) ? 'botstart' : kind,
                text: b.text, url, data: (ty.data || b.data) ? 1 : 0, bot: url.match(/t\.me\/([A-Za-z0-9_]*bot)\b/i)?.[1] || '' };
            });
            const txtHit = /розыгрыш|разыгрыва|giveaway|конкурс|итоги|победител/i.test(t);
            if (!g && !(txtHit && (btns.length || /подпис|участв/i.test(t)))) continue;
            found.push({ id: m.id, date: m.date, url: `https://t.me/${c.username}/${m.id}`,
              native: g ? { kind: g._, until: g.until_date, quantity: g.quantity, months: g.months, stars: g.stars, prize: g.prize_description || '',
                only_new: !!(g.pFlags && g.pFlags.only_new_subscribers), countries: g.countries_iso2 || [],
                channels: (g.channels || []).map(id => { const x = chats[id] || {}; return { id, title: x.title || '', username: T.uname(x) }; }) } : null,
              buttons: btns, text: t.slice(0, 700) });
          }
          await sleep(300);
          return { ref, title: c.title, username: c.username, posts: found.slice(0, 4) };
        }, { ref, since });
        out.push(r);
        if (r.error) { console.log(`✗ @${ref}: ${r.error}`); continue; }
        const p = r.posts;
        console.log(`\n■ ${r.title} @${r.username} — найдено ${p.length}`);
        for (const x of p) {
          const n = x.native;
          console.log(`  ${d(x.date)} ${x.url} ${n ? `[TG-розыгрыш ${n.kind === 'messageMediaGiveawayResults' ? 'ИТОГИ' : 'до ' + d(n.until)}${n.only_new ? ', только новые' : ''}] каналы: ${n.channels.map(c => '@' + (c.username || c.id)).join(' ')}` : ''}`);
          for (const b of x.buttons) console.log(`    кнопка [${b.type}] «${b.text}» ${b.url}`);
          console.log(`    ${x.text.replace(/\n+/g, ' ⏎ ').slice(0, 220)}`);
        }
      }
      if (o.json) fs.writeFileSync(o.json, JSON.stringify(out, null, 1));
      return;
    }
    // join
    const res = await pg.evaluate(async ({ list, yes, archive, pace }) => {
      const T = window.__tg; const sleep = ms => new Promise(r => setTimeout(r, ms)); const log = [];
      for (const ref of list) {
        if (ref.startsWith('+')) { // private invite link
          const hash = ref.slice(1);
          try {
            const ci = await T.api('messages.checkChatInvite', { hash });
            // public channels answer chatInviteAlready even when NOT joined — trust only the `left` flag
            if (ci._ === 'chatInviteAlready' || ci._ === 'chatInvitePeek') {
              const chat = ci.chat;
              if (!(chat.pFlags && chat.pFlags.left)) { log.push(`= ${chat.title} (приглашение ${hash.slice(0, 6)}…) уже подписан`); continue; }
              if (!yes) { log.push(`→ ${chat.title} (приглашение ${hash.slice(0, 6)}…)`); continue; }
              let err = '';
              for (let a = 0; a < 3; a++) {
                try { await T.api('channels.joinChannel', { channel: { _: 'inputChannel', channel_id: chat.id, access_hash: chat.access_hash } }); err = ''; break; }
                catch (e) { const t = e.type || e.message || ''; const w = /FLOOD_WAIT_(\d+)/.exec(t);
                  if (w && +w[1] <= 900) { log.push(`… FLOOD_WAIT ${w[1]} с`); await sleep((+w[1] + 2) * 1000); continue; } err = t; break; }
              }
              if (!err && archive) await T.api('folders.editPeerFolders', { folder_peers: [{ _: 'inputFolderPeer', peer: T.inputOf(chat), folder_id: 1 }] }).catch(() => {});
              log.push(err ? `✗ ${chat.title} (приглашение) ОШИБКА ${err}` : `✓ ${chat.title} (приглашение)${archive ? ' → архив' : ''}`);
              if (err && /FLOOD|TOO_MUCH|SPAM/i.test(err)) { log.push('СТОП: лимит Telegram — продолжить позже'); break; }
              await sleep(pace * 1000 * (0.8 + Math.random() * 0.4)); continue;
            }
            if (!yes) { log.push(`→ ${ci.title} (приглашение ${hash.slice(0, 6)}…)${ci.pFlags && ci.pFlags.request_needed ? ' — по заявке' : ''}`); continue; }
            let up, err = '';
            for (let a = 0; a < 3; a++) {
              try { up = await T.api('messages.importChatInvite', { hash }); break; }
              catch (e) { const t = e.type || e.message || ''; const w = /FLOOD_WAIT_(\d+)/.exec(t);
                if (w && +w[1] <= 900) { log.push(`… FLOOD_WAIT ${w[1]} с`); await sleep((+w[1] + 2) * 1000); continue; } err = t; break; }
            }
            if (/INVITE_REQUEST_SENT/.test(err)) { log.push(`📨 ${ci.title}: заявка на вступление отправлена (ждёт одобрения админа)`); }
            else if (err) { log.push(`✗ ${ci.title} (приглашение) ОШИБКА ${err}`); if (/FLOOD|TOO_MUCH|SPAM/i.test(err)) { log.push('СТОП: лимит Telegram — продолжить позже'); break; } }
            else { const ch = up && up.chats && up.chats[0];
              if (ch && archive) await T.api('folders.editPeerFolders', { folder_peers: [{ _: 'inputFolderPeer', peer: T.inputOf(ch), folder_id: 1 }] }).catch(() => {});
              log.push(`✓ ${ci.title} (приглашение)${archive ? ' → архив' : ''}`); }
          } catch (e) { log.push(`✗ приглашение ${hash.slice(0, 6)}…: ${e.type || e.message}`); }
          if (yes) await sleep(pace * 1000 * (0.8 + Math.random() * 0.4));
          continue;
        }
        let c; try { c = await T.resolve(ref); } catch (e) { log.push(`✗ @${ref}: ${e.type || e.message}`); continue; }
        if (c.ent._ !== 'channel') { log.push(`✗ @${ref}: не канал/чат`); continue; }
        const was = !(c.ent.pFlags && c.ent.pFlags.left);
        if (!yes) { log.push(`${was ? '=' : '→'} ${c.title} @${c.username}${was ? ' (уже подписан)' : ''}`); continue; }
        const ch = { _: 'inputChannel', channel_id: c.ent.id, access_hash: c.ent.access_hash };
        let ok = was, err = '';
        if (!was) for (let a = 0; a < 3; a++) {
          try { await T.api('channels.joinChannel', { channel: ch }); ok = true; break; }
          catch (e) { const t = e.type || e.message || ''; const w = /FLOOD_WAIT_(\d+)/.exec(t);
            if (w && +w[1] <= 900) { log.push(`… FLOOD_WAIT ${w[1]} с`); await sleep((+w[1] + 2) * 1000); continue; }
            err = t; break; }
        }
        if (ok && archive) await T.api('folders.editPeerFolders', { folder_peers: [{ _: 'inputFolderPeer', peer: c.peer, folder_id: 1 }] }).catch(e => err = 'архив: ' + (e.type || e.message));
        log.push(`${ok ? (was ? '=' : '✓') : '✗'} ${c.title} @${c.username}${was ? ' (уже был)' : ''}${ok && archive ? ' → архив' : ''}${err ? ' ОШИБКА ' + err : ''}`);
        if (err && /CHANNELS_TOO_MUCH|FLOOD|SPAM/i.test(err)) { log.push('СТОП: лимит Telegram — продолжить позже'); break; }
        await sleep(was ? 300 : pace * 1000 * (0.8 + Math.random() * 0.4)); // spread joins out: new accounts get limited fast
      }
      return log;
    }, { list, yes: !!o.yes, archive: !!o.archive, pace: +(o.pace || 6) });
    console.log(res.join('\n'));
    if (!o.yes) console.log('DRY RUN. Подписка только по команде владельца: добавьте --yes');
  }, undefined, o.account || 1);
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
