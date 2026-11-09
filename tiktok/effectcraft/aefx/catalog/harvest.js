// Снимает каталог эффектов с живого EffectCraft: node harvest.js raw.json   (NODE_PATH=$(npm root -g))
// Два источника: effect.list (id/типы/диапазоны параметров движка) и скриптовый слой
// (addProperty: match name Adobe, вложенность групп). Склейка — build_catalog.py.
const { chromium } = require('playwright');
const fs = require('fs');
const URL = process.env.EFFECTCRAFT_URL || 'https://photocraftonline.app/engine/apps/effectcraft/0.9.2/';
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
  const p = await b.newPage({ viewport: { width: 1600, height: 1000 } });
  await p.goto(URL, { waitUntil: 'load', timeout: 180000 });
  await p.waitForFunction(() => window.effectcraft && window.effectcraftLoad && window.effectcraftLoad.readyMs, null, { timeout: 180000 });
  await p.waitForTimeout(3000);
  const res = await p.evaluate(async () => {
    const ec = window.effectcraft;
    await ec.execute('file.newProject', {});
    await ec.execute('comp.new', { name: 'T', width: 640, height: 360, frameRate: 30, duration: 5, open: true });
    await ec.execute('layer.newSolid', { name: 'S', color: '#808080' });
    const list = await ec.execute('effect.list', {});
    const caps = await ec.execute('app.capabilities', {});
    const plugins = await ec.execute('effect.plugins.list', {});
    const code = `
      var names = ${JSON.stringify(list.map((e) => e.name))};
      var par = app.project.activeItem.layer(1).property("ADBE Effect Parade"); var res = [];
      function dump(g){ var a=[]; for (var j=1;j<=g.numProperties;j++){ var p=g.property(j); var o={n:p.name,m:p.matchName};
        if (p.propertyType==6212){ o.vt=p.propertyValueType; } else { o.ch=dump(p); } a.push(o);} return a; }
      for (var i=0;i<names.length;i++){ try { var e = par.addProperty(names[i]); res.push({name:names[i], mn:e.matchName, ps:dump(e)}); e.remove(); } catch(err){ res.push({name:names[i], err:String(err)}); } }
      JSON.stringify(res);`;
    const r = await ec.execute('script.run', { code });
    return { caps, plugins, list, script: JSON.parse(r.result) };
  });
  fs.writeFileSync(process.argv[2] || 'raw.json', JSON.stringify(res));
  console.log('effects', res.list.length, 'version', res.caps.version, 'wasm plugins', res.plugins.wasm);
  await b.close();
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
