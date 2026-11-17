// Move channels/chats to Telegram's Archive (or back) — ONLY on the owner's command.
// Without --yes it is a dry run: shows what would move and where each chat is now. Nothing else is touched
// (no unsubscribing, no mute, no read marks).
// usage: node tg_archive.js archive   <refs…> [--file list.txt] [--yes]
//        node tg_archive.js unarchive <refs…> [--file list.txt] [--yes]
//        node tg_archive.js list [--limit 200]          — what is in the Archive now
// refs: @username, t.me/username[/post], peer id from tg_web.js `chats` (e.g. -1905100403); comma or space separated.
// --file: any text (e.g. the owner's "ОТПИСАТЬСЯ" list) — every @username and t.me/… link in it is taken.
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2)); // --account 2 = второй аккаунт, только по просьбе владельца
const [cmd, ...rest] = o._;

function refsFrom() {
  let src = rest.join(' ');
  if (o.file) src += ' ' + fs.readFileSync(o.file, 'utf8');
  const out = [];
  for (const m of src.matchAll(/(?:https?:\/\/)?t\.me\/(?!c\/|\+|joinchat)([A-Za-z0-9_]{4,})|@([A-Za-z0-9_]{4,})|(?:^|[\s,])(-?\d{5,})(?=$|[\s,])/g))
    out.push(m[1] || m[2] || m[3]);
  return [...new Set(out.map(x => x.toLowerCase()))];
}

(async () => {
  if (!['archive', 'unarchive', 'list'].includes(cmd)) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 9).join('\n')); return; }
  await withTg(async pg => {
    if (cmd === 'list') {
      const r = await pg.evaluate(async lim => {
        const T = window.__tg; const out = []; let total = 0; let off = { date: 0, id: 0, peer: { _: 'inputPeerEmpty' } };
        while (out.length < lim) {
          const d = await T.api('messages.getDialogs', { folder_id: 1, offset_date: off.date, offset_id: off.id, offset_peer: off.peer, limit: 100, hash: 0 });
          const chats = Object.fromEntries([...(d.chats || []), ...(d.users || [])].map(c => [c.id, c]));
          if (!d.dialogs.length) break;
          total = d.count || total;
          for (const x of d.dialogs) { if ((x.folder_id || 0) !== 1) continue; // server also returns main-list pins
            const id = x.peer.channel_id || x.peer.chat_id || x.peer.user_id; const c = chats[id] || {};
            out.push(`${x.peer.user_id ? id : -id}\t@${T.uname(c) || '—'}\t${c.title || c.first_name || ''}\t[${x.unread_count}]`); }
          const last = d.dialogs[d.dialogs.length - 1]; const lm = (d.messages || []).find(m => m.id === last.top_message);
          const lid = last.peer.channel_id || last.peer.chat_id || last.peer.user_id;
          off = { date: lm ? lm.date : 0, id: last.top_message, peer: T.inputOf(chats[lid]) };
          if (d.dialogs.length < 100) break;
        }
        return { out: out.slice(0, lim), total };
      }, +(o.limit || 200));
      console.log(r.out.join('\n') + `\n— показано ${r.out.length}, всего в архиве ${r.total}`); return;
    }
    const refs = refsFrom();
    if (!refs.length) throw new Error('не указаны каналы (@username / t.me / id / --file)');
    const folder = cmd === 'archive' ? 1 : 0;
    const res = await pg.evaluate(async ({ refs, folder, yes }) => {
      const T = window.__tg; const ok = [], bad = [];
      for (const r of refs) {
        try { const c = await T.resolve(r);
          const pd = await T.api('messages.getPeerDialogs', { peers: [{ _: 'inputDialogPeer', peer: c.peer }] });
          const dlg = pd.dialogs && pd.dialogs[0];
          ok.push({ r, title: c.title, username: c.username, peer: c.peer, inList: !!dlg, now: dlg ? (dlg.folder_id || 0) : null });
        } catch (e) { bad.push(`${r}: ${e.type || e.message}`); }
      }
      const todo = ok.filter(x => x.inList && x.now !== folder);
      let done = 0;
      if (yes) for (let i = 0; i < todo.length; i += 100) {
        await T.api('folders.editPeerFolders', { folder_peers: todo.slice(i, i + 100).map(x => ({ _: 'inputFolderPeer', peer: x.peer, folder_id: folder })) });
        done += Math.min(100, todo.length - i);
      }
      return { ok: ok.map(({ peer, ...x }) => x), bad, todo: todo.length, done };
    }, { refs, folder, yes: !!o.yes });
    const where = f => f === null ? 'нет в списке чатов (не подписан)' : f === 1 ? 'в архиве' : 'в общем списке';
    for (const x of res.ok) console.log(`${x.inList && x.now !== folder ? '→' : '='} ${x.title}  @${x.username || '—'}  (${where(x.now)})`);
    for (const b of res.bad) console.log(`✗ ${b}`);
    const verb = folder ? 'в архив' : 'из архива';
    console.log(o.yes ? `перенесено ${verb}: ${res.done}` : `DRY RUN: ${verb} ушло бы ${res.todo} (→). Только по команде владельца: добавьте --yes`);
  }, undefined, o.account || 1);
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
