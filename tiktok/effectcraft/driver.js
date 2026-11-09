// Headless-драйвер EffectCraft: node driver.js config.json
// config: {files:[...], steps:"steps.json", stills:[t...], render:{start,end,out}, saveProject, outDir}
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
(async () => {
  const cfgPath = path.resolve(process.argv[2]);
  const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  const dir = path.dirname(cfgPath);
  const abs = (f) => path.isAbsolute(f) ? f : path.join(dir, f);
  cfg.steps = abs(cfg.steps);
  const outDir = abs(cfg.outDir || '.');
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
  const p = await b.newPage({ viewport: { width: 1600, height: 1000 } });
  await p.route('**/__local/**', (route) => {
    const f = decodeURIComponent(route.request().url().split('/__local/')[1]);
    route.fulfill({ body: fs.readFileSync(f), headers: { 'access-control-allow-origin': '*' } });
  });
  if (cfg.frames) {
    const fd = abs(cfg.framesDir || 'frames'); fs.mkdirSync(fd, { recursive: true });
    await p.exposeFunction('saveFrame', (n, b64) => fs.writeFileSync(path.join(fd, String(n).padStart(5, '0') + '.png'), Buffer.from(b64, 'base64')));
  }
  await p.goto('https://photocraftonline.app/engine/apps/effectcraft/0.9.2/', { waitUntil: 'load', timeout: 180000 });
  await p.waitForFunction(() => window.effectcraft && window.effectcraftLoad && window.effectcraftLoad.readyMs, null, { timeout: 180000 });
  await p.waitForTimeout(3000);
  for (const f of cfg.files) {
    await p.evaluate(async ([u, n]) => window.effectcraft.addFile(u, n), ['/__local/' + encodeURIComponent(abs(f)), path.basename(f)]);
  }
  await p.evaluate((c) => { window.__cfg = c; }, cfg);
  let code = fs.readFileSync(path.join(__dirname, 'runner.js'), 'utf8');
  if (cfg.evalFile) code = code.replace('/*EVAL*/', "try { log.push(['eval', await (async () => {" + fs.readFileSync(abs(cfg.evalFile), 'utf8') + "})()]); } catch (e) { log.push(['eval', 'ERR ' + e]); }");
  const res = await p.evaluate(new Function('return (async () => {' + code + '})()'));
  fs.mkdirSync(outDir, { recursive: true });
  for (const [n, v] of Object.entries(res.outputs || {})) fs.writeFileSync(path.join(outDir, n), Buffer.from(v, 'base64'));
  console.log(JSON.stringify(res.log));
  await b.close();
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
