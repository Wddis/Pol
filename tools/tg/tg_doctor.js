// Health check of the Telegram pult in one command (what broke on 17.11: white screen, stray mini-app tabs,
// tab left on account 2, two tools in one tab, flood limits).
// usage: node tg_doctor.js [--fix] [--token-file rb_token] [--port 8765] [--cdp 9222]
//   checks: pult web server + token, CDP, Telegram tab alive (rootScope), accounts, which account is shown,
//           lock (who works in the pult now), extra tabs, Telegram FLOOD_WAIT on a harmless call.
//   --fix:  reload a dead/white tab, close stray tabs (mini-apps), return the tab to account 1 (main).
//           Never while another tool holds the lock.
const fs = require('fs'); const http = require('http');
const { chromium } = require('playwright');
const { opts, lockInfo } = require('./tg_api');
const o = opts(process.argv.slice(2));
const PORT = +(o.port || 8765), CDP = +(o.cdp || 9222);
const ok = (b, m) => console.log(`${b ? '✓' : '✗'} ${m}`);
const get = url => new Promise(r => http.get(url, res => { res.resume(); r(res.statusCode); }).on('error', () => r(0)));

(async () => {
  const tok = o['token-file'] && fs.existsSync(o['token-file']) ? fs.readFileSync(o['token-file'], 'utf8').trim() : process.env.RB_TOKEN || '';
  const code = await get(`http://127.0.0.1:${PORT}/${tok ? '?t=' + tok : ''}`);
  ok(code === 200, `пульт http://127.0.0.1:${PORT} → ${code || 'нет ответа'}${code === 403 ? ' (неверный токен)' : ''}${!code ? ' — поднять: docs/telegram.md' : ''}`);
  const l = lockInfo();
  ok(true, l ? `ЗАНЯТ: pid ${l.pid} «${l.cmd}» с ${l.since} — ничего не чиню, не мешаю` : 'пульт свободен (нет блокировки)');
  let b; try { b = await chromium.connectOverCDP(`http://127.0.0.1:${CDP}`); } catch (e) { ok(false, `CDP ${CDP}: ${e.message.slice(0, 80)}`); return; }
  ok(true, `CDP ${CDP}`);
  const fix = o.fix && !l;
  try {
    const pages = b.contexts()[0].pages();
    const tg = pages.filter(p => /web\.telegram\.org/.test(p.url()));
    const stray = pages.filter(p => !tg.includes(p));
    ok(tg.length === 1, `вкладок Telegram: ${tg.length}; прочих: ${stray.length}${stray.length ? ' (' + stray.map(p => p.url().slice(0, 50)).join(', ') + ')' : ''}`);
    if (fix) for (const p of stray) await p.close().catch(() => {});
    let pg = tg[0];
    if (!pg) { if (fix) { pg = await b.contexts()[0].newPage(); await pg.goto('https://web.telegram.org/k/'); ok(true, 'открыл вкладку Telegram'); } else return; }
    const alive = async () => pg.evaluate(() => ({ ready: !!(window.rootScope && rootScope.managers), text: (document.body && document.body.innerText || '').trim().length, n: +localStorage.number_of_accounts || 0 })).catch(() => ({ ready: false, text: 0, n: 0 }));
    let st = await alive();
    if (!st.ready || !st.text) {
      ok(false, `вкладка не отвечает (rootScope ${st.ready ? 'есть' : 'нет'}, текста ${st.text}) — «белый экран»`);
      if (fix) { await pg.goto('https://web.telegram.org/k/', { waitUntil: 'domcontentloaded' }).catch(() => {});
        await pg.waitForFunction(() => window.rootScope && rootScope.managers, null, { timeout: 60000 }).catch(() => {});
        await pg.waitForTimeout(3000); st = await alive(); ok(st.ready, 'перезагрузил вкладку'); }
    } else ok(true, 'вкладка Telegram жива');
    const acc = +(new URL(pg.url()).searchParams.get('account') || 1);
    ok(acc === 1, `показан аккаунт ${acc}${acc !== 1 ? ' (основной — 1)' : ''}; всего аккаунтов во вкладке: ${st.n}`);
    if (fix && acc !== 1) { await pg.goto('https://web.telegram.org/k/', { waitUntil: 'domcontentloaded' }); ok(true, 'вернул на аккаунт 1'); }
    if (st.ready) {
      const me = await pg.evaluate(async () => { try {
        const u = await rootScope.managers.appUsersManager.getSelf();
        let flood = '';
        try { await rootScope.managers.apiManager.invokeApi('help.getNearestDc', {}); } catch (e) { flood = e.type || e.message; }
        return { id: u && u.id, name: u && [u.first_name, u.last_name].filter(Boolean).join(' '), username: u && u.username, flood };
      } catch (e) { return { err: String(e) }; } }).catch(e => ({ err: e.message }));
      ok(!!me.id, me.id ? `в аккаунте: ${me.name} @${me.username || '—'} id ${me.id}` : `аккаунт не определён: ${me.err}`);
      if (me.flood) ok(false, `Telegram ограничивает: ${me.flood}`);
    }
  } finally { await b.close().catch(() => {}); }
})().catch(e => { console.error('ОШИБКА:', e.message); process.exitCode = 1; });
