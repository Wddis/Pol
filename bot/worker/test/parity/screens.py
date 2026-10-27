# Экраны бота на Python для сравнения с воркером (test/parity.mjs): те же данные — тот же JSON.
import json, sys
from pathlib import Path
from dataclasses import replace
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from oda_bot import views
from oda_bot.config import Settings
from oda_bot.db import Key, Device
cfg = Settings(price_month=299, support="@sup<x>")
now = 1790000000
k = Key("ODA-ACDE-FGHJ-KMNP", 7, "month", 30, 3, now - 9000, now - 5 * 86400, now + 25 * 86400 - 100, False)
k2 = replace(k, key="ODA-QRTU-VWXY-Z234", tariff="trial", days=1, devices_max=1, activated_at=None, expires_at=None)
k3 = replace(k, key="ODA-2346-79AC-DEFG", activated_at=now - 40 * 86400, expires_at=now - 10 * 86400)
devs = [Device("d1", "Pixel <7>", now - 100, now - 50), Device("d2", "", now - 90, now - 40)]
apk = replace(cfg, apk_url="https://x/a.apk")
out = dict(
  main=views.main(cfg), t1=views.tariffs(cfg, "month", 2), t2=views.tariffs(cfg, "week", 5), t3=views.tariffs(replace(cfg, free_mode=False, apk_url="https://x/a.apk"), "q", 1),
  ki=views.key_issued(cfg, k2), ki2=views.key_issued(apk, k), tu=views.trial_used(),
  p0=views.profile(cfg, 7, None, [], now, 0), p1=views.profile(cfg, 7, k, devs, now, 3), p2=views.profile(cfg, 7, k2, [], now, 1), p3=views.profile(cfg, 7, k3, devs, now, 2),
  kl=views.keys_list([k, k2, k3], now), ex=views.extend_choose(cfg, k), rc=views.reset_confirm(k, devs), rc0=views.reset_confirm(k, []),
  app=views.app_info(cfg), app2=views.app_info(apk), about=views.about(cfg), sup=views.support(cfg), sup2=views.support(replace(cfg, support="")),
  howto=views.howto(cfg), howto2=views.howto(apk), trouble=views.trouble(cfg), privacy=views.privacy(cfg), rules=views.rules(cfg),
)
def norm(s):
    return {"text": s.text, "markup": {"inline_keyboard": [[b.model_dump(exclude_none=True) for b in row] for row in s.markup.inline_keyboard]}}
print(json.dumps({k: norm(v) for k, v in out.items()}, ensure_ascii=False))
