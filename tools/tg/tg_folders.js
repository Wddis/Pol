// Chat folders: list them, or copy a folder (its rules) from one account to another — ONLY on the owner's command.
// usage: node tg_folders.js list [--account 2]
//        node tg_folders.js copy "Личные" --from 1 --to 2 [--yes]
// copy takes the folder RULES (contacts / non-contacts / groups / channels / bots, exclude muted/read/archived).
// Chats picked by hand (include/exclude/pinned) belong to the source account and are not copied.
// Without --yes: dry run. New folder goes right after «Все чаты», as in the source.
const fs = require('fs');
const { withTg, opts } = require('./tg_api');
const o = opts(process.argv.slice(2));
const [cmd, name] = o._;
// returns only after withTg fully finished (tab back on account 1, lock released) — the early resolve here was the 17.11 bug
const read = acc => withTg(pg => pg.evaluate(async () => {
  const T = window.__tg; const r = await T.api('messages.getDialogFilters', {}); return (r.filters || r);
}), undefined, acc);
const title = f => f.title ? (f.title.text || f.title) : (f._ === 'dialogFilterDefault' ? 'Все чаты' : '');
const RULES = { contacts: 'контакты', non_contacts: 'не контакты', groups: 'группы', broadcasts: 'каналы', bots: 'боты',
  exclude_muted: 'без заглушённых', exclude_read: 'без прочитанных', exclude_archived: 'без архива' };
const rules = f => Object.keys(f.pFlags || {}).filter(k => RULES[k]).map(k => RULES[k]).join(', ') || 'только выбранные чаты';

(async () => {
  if (cmd === 'list') {
    for (const f of await read(o.account || 1))
      console.log(`${f.id ?? 0}\t${title(f)}\t${f._ === 'dialogFilterDefault' ? '' : rules(f)}${(f.include_peers || []).length ? ` +${f.include_peers.length} выбранных` : ''}`);
    return;
  }
  if (cmd !== 'copy' || !name) { console.log(fs.readFileSync(__filename, 'utf8').split('\n').slice(0, 7).join('\n')); return; }
  const from = +(o.from || 1), to = +(o.to || 2);
  const src = (await read(from)).find(f => title(f) === name);
  if (!src) throw new Error(`в аккаунте ${from} нет папки «${name}»`);
  const dst = await read(to);
  if (dst.some(f => title(f) === name)) { console.log(`в аккаунте ${to} папка «${name}» уже есть — ничего не делаю`); return; }
  const id = Math.max(1, ...dst.map(f => f.id || 0)) + 1;
  console.log(`«${name}»: ${rules(src)} → аккаунт ${to}, id ${id}${(src.include_peers || []).length ? ' (выбранные вручную чаты не переносятся)' : ''}`);
  if (!o.yes) { console.log('DRY RUN. Только по команде владельца: --yes'); return; }
  await withTg(async pg => {
    const r = await pg.evaluate(async ({ src, id }) => {
      const T = window.__tg;
      const pFlags = {}; for (const k of Object.keys(src.pFlags || {})) if (['contacts', 'non_contacts', 'groups', 'broadcasts', 'bots', 'exclude_muted', 'exclude_read', 'exclude_archived'].includes(k)) pFlags[k] = true;
      const filter = { _: 'dialogFilter', pFlags, id, title: { _: 'textWithEntities', text: src.title.text || src.title, entities: [] },
        include_peers: [], exclude_peers: [], pinned_peers: [] };
      if (src.emoticon) filter.emoticon = src.emoticon;
      await T.api('messages.updateDialogFilter', { id, filter });
      const all = await T.api('messages.getDialogFilters', {}); const list = (all.filters || all).filter(f => f._ !== 'dialogFilterDefault').map(f => f.id);
      const order = [id, ...list.filter(x => x !== id)];                  // right after «Все чаты»
      await T.api('messages.updateDialogFiltersOrder', { order });
      return order;
    }, { src, id });
    console.log(`создана папка «${name}» в аккаунте ${to}; порядок папок: ${r.join(', ')}`);
  }, undefined, to);
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
