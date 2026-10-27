// Экраны воркера для сравнения с ботом на Python (test/parity.mjs): те же данные, что в screens.py.
const v = await import(process.argv[2]);
const cfg = { priceWeek: 0, priceMonth: 299, price3m: 0, extraDevicePct: 50, freeMode: true, apkUrl: "", support: "@sup<x>" };
const now = 1790000000;
const k = { key: "ODA-ACDE-FGHJ-KMNP", tg_id: 7, tariff: "month", days: 30, devices_max: 3, created_at: now - 9000, activated_at: now - 5 * 86400, expires_at: now + 25 * 86400 - 100, revoked: false };
const k2 = { ...k, key: "ODA-QRTU-VWXY-Z234", tariff: "trial", days: 1, devices_max: 1, activated_at: null, expires_at: null };
const k3 = { ...k, key: "ODA-2346-79AC-DEFG", activated_at: now - 40 * 86400, expires_at: now - 10 * 86400 };
const devs = [{ device_id: "d1", name: "Pixel <7>", first_seen: now - 100, last_seen: now - 50 }, { device_id: "d2", name: "", first_seen: now - 90, last_seen: now - 40 }];
const out = {
  main: v.main(cfg), t1: v.tariffs(cfg, "month", 2), t2: v.tariffs(cfg, "week", 5), t3: v.tariffs({ ...cfg, freeMode: false, apkUrl: "https://x/a.apk" }, "q", 1),
  ki: v.keyIssued(cfg, k2), ki2: v.keyIssued({ ...cfg, apkUrl: "https://x/a.apk" }, k), tu: v.trialUsed(),
  p0: v.profile(cfg, 7, null, [], now, 0), p1: v.profile(cfg, 7, k, devs, now, 3), p2: v.profile(cfg, 7, k2, [], now, 1), p3: v.profile(cfg, 7, k3, devs, now, 2),
  kl: v.keysList([k, k2, k3], now), ex: v.extendChoose(cfg, k), rc: v.resetConfirm(k, devs), rc0: v.resetConfirm(k, []),
  app: v.appInfo(cfg), app2: v.appInfo({ ...cfg, apkUrl: "https://x/a.apk" }), about: v.about(cfg), sup: v.support(cfg), sup2: v.support({ ...cfg, support: "" }),
  howto: v.howto(cfg), howto2: v.howto({ ...cfg, apkUrl: "https://x/a.apk" }), trouble: v.trouble(cfg), privacy: v.privacy(cfg), rules: v.rules(cfg),
};
console.log(JSON.stringify(out));
