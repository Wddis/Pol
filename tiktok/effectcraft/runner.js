// Выполняется внутри страницы EffectCraft. Конфиг: window.__cfg (подставляет driver)
const ec = window.effectcraft, cfg = window.__cfg, log = [], saved = {};
const proj = await (await fetch('/__local/' + encodeURIComponent(cfg.steps))).json();
const sub = (v) => typeof v === 'string' && v.startsWith('$') ? saved[v.slice(1)] : Array.isArray(v) ? v.map(sub) : (v && typeof v === 'object') ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x)])) : v;
// --- aefx: значения в долях слоя ($frac) пересчитываются в пиксели по реальному размеру слоя на сайте
const lsize = {}, clamps = [];
const layerSize = async (id) => {
  if (!lsize[id]) {
    const t = await ec.execute('layer.tree', { layer: id, depth: 1 });
    const r = await ec.execute('script.run', { code: `var l = app.project.activeItem.layer(${t.index}); JSON.stringify([l.width, l.height])` });
    lsize[id] = JSON.parse(r.result);
  }
  return lsize[id];
};
const hasFrac = (v) => !!v && typeof v === 'object' && (Array.isArray(v) ? v.some(hasFrac) : ('$frac' in v || '$fracVec' in v || '$fracSpeed' in v || Object.values(v).some(hasFrac)));
const frac = (v, s) => Array.isArray(v) ? v.map((x) => frac(x, s)) : !(v && typeof v === 'object') ? v
  : '$frac' in v ? [v.$frac[0] * s[0], v.$frac[1] * s[1]]
  : '$fracVec' in v ? [v.$fracVec[0] * s[0], v.$fracVec[1] * s[1]]
  : '$fracSpeed' in v ? (() => { const [dx, dy] = v.dir, l = Math.hypot(dx, dy); return v.$fracSpeed * (l ? Math.hypot(dx * s[0], dy * s[1]) / l : (s[0] + s[1]) / 2); })()
  : Object.fromEntries(Object.entries(v).map(([k, x]) => [k, frac(x, s)]));
let errs = 0;
for (let i = 0; i < proj.steps.length; i++) {
  const st = proj.steps[i]; let p = sub(st.p); const save = p.save; delete p.save;
  try {
    if (hasFrac(p)) p = frac(p, await layerSize(p.layer ?? (p.layers && p.layers[0]) ?? (p.keys && p.keys[0].layer)));
    const r = await ec.execute(st.c, p);
    if (save) saved[save] = r && (r.layer ?? r.animator ?? r.id ?? (r.effects && r.effects[0]));
    // движок молча обрезает значение по диапазону параметра — фиксируем, чтобы это не осталось незамеченным
    if (st.c === 'prop.set' && typeof p.value === 'number' && typeof r === 'number' && Math.abs(r - p.value) > 1e-6 * Math.max(1, Math.abs(p.value))) clamps.push({ step: i, path: p.path, asked: p.value, got: r });
  } catch (e) { errs++; if (errs < 40) log.push([i, st.c, JSON.stringify(p).slice(0, 200), String(e).slice(0, 300)]); }
}
log.push(['errors', errs, 'of', proj.steps.length]);
const outputs = {};
const b64s = (str) => { const u = new TextEncoder().encode(str); let o = ''; for (let i = 0; i < u.length; i += 0x8000) o += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(o); };
if (clamps.length) log.push(['clamped', clamps]);
if (cfg.aefxVerify) {
  // состояние проекта после применения: по нему aefx/verify.py сверяет каждое значение, ключ, ease и выражение
  try {
    await ec.execute('file.saveAs', { path: '/__aefx_verify.ecproj' });
    const pj = JSON.parse(new TextDecoder().decode(new Uint8Array(await ec.readFile('/__aefx_verify.ecproj'))));
    const comps = Object.values(pj.items).filter((it) => it.kind && it.kind.type === 'Comp');
    const errors = await ec.execute('expr.errors', {}).catch((e) => ({ error: String(e) }));
    outputs['aefx_state.json'] = b64s(JSON.stringify({ saved, lsize, clamps, errors, stepErrors: errs, comps }));
  } catch (e) { log.push(['aefxVerify', String(e)]); }
}
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
    const f = await ec.renderFrame({ comp: 'EDIT', time: n / 30 + 1e-4 });
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
