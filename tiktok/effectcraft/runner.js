// Выполняется внутри страницы EffectCraft. Конфиг: window.__cfg (подставляет driver)
const ec = window.effectcraft, cfg = window.__cfg, log = [], saved = {};
const proj = await (await fetch('/__local/' + encodeURIComponent(cfg.steps))).json();
const sub = (v) => typeof v === 'string' && v.startsWith('$') ? saved[v.slice(1)] : Array.isArray(v) ? v.map(sub) : (v && typeof v === 'object') ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x)])) : v;
let errs = 0;
for (let i = 0; i < proj.steps.length; i++) {
  const st = proj.steps[i]; const p = sub(st.p); const save = p.save; delete p.save;
  try {
    const r = await ec.execute(st.c, p);
    if (save) saved[save] = r && (r.layer ?? r.animator ?? r.id);
  } catch (e) { errs++; if (errs < 40) log.push([i, st.c, JSON.stringify(p).slice(0, 200), String(e).slice(0, 300)]); }
}
log.push(['errors', errs, 'of', proj.steps.length]);
const outputs = {};
/*EVAL*/
const b64 = (u) => { let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); };
for (const t of (cfg.stills || [])) {
  try { const f = await ec.renderFrame({ comp: 'EDIT', time: t }); outputs[`still_${t}.png`] = f.png; } catch (e) { log.push(['still', t, String(e)]); }
}
if (cfg.saveProject) {
  try { await ec.execute('file.saveAs', { path: '/edit.ecproj' }); outputs['edit.ecproj'] = b64(new Uint8Array(await ec.readFile('/edit.ecproj'))); } catch (e) { log.push(['save', String(e)]); }
}
if (cfg.frames) {
  // покадровый рендер через render.frame (Render Queue в 0.9.2 теряет анимацию ключей)
  const t0 = Date.now();
  for (let n = cfg.frames.from; n < cfg.frames.to; n++) {
    const f = await ec.renderFrame({ comp: 'EDIT', time: n / (cfg.frames.fps || 30) + 1e-4 });
    await window.saveFrame(n, f.png);
  }
  log.push(['framesSec', (Date.now() - t0) / 1000, cfg.frames]);
}
if (cfg.render) {
  const { start, end, out } = cfg.render;
  await ec.execute('renderQueue.add', { comp: 'EDIT', format: 'h264', output: '/' + out, audio: 'off', bitrate: 25000, timeSpan: 'custom', start, end, quality: 'best', resolution: 'full', motionBlur: 'onForChecked', frameBlending: 'onForChecked' });
  const t0 = Date.now();
  await ec.execute('renderQueue.render', { wait: true });
  log.push(['renderSec', (Date.now() - t0) / 1000]);
  outputs[out] = b64(new Uint8Array(await ec.readFile('/' + out)));
}
return { log, outputs };
