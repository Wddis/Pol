"""Командная строка.

  python -m smsfinder find --service telegram --country de
  python -m smsfinder get  --service telegram --country de [--pick 2] [--timeout 600]
"""
from __future__ import annotations

import argparse
import sys
import time

from .engine import Engine


def _print_table(cands):
    print(f"{'#':>2}  {'score':>5}  {'провайдер':<12} {'страна':<12} {'номер':<17} {'цена':>7}  заметка")
    for i, c in enumerate(cands, 1):
        price = "free" if c["public"] else f"{c['price']:.2f}"
        print(f"{i:>2}  {c['score']:>5}  {c['provider']:<12} {c['country'][:12]:<12} "
              f"{c['number'] or '(после покупки)':<17} {price:>7}  {c['note']}")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="smsfinder", description="Подбор виртуальных номеров и приём SMS")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("find", "get"):
        p = sub.add_parser(name)
        p.add_argument("--service", default="other", help="telegram, whatsapp, google, ...")
        p.add_argument("--country", default="", help="ISO2 (de), название (germany) или код")
        p.add_argument("--providers", default="", help="через запятую: receivesms,onlinesim,5sim,smsactivate")
        p.add_argument("--limit", type=int, default=15)
        if name == "get":
            p.add_argument("--pick", type=int, default=1, help="номер строки из списка (1 = лучший)")
            p.add_argument("--timeout", type=int, default=600)
    a = ap.parse_args(argv)

    eng = Engine()
    provs = [x.strip() for x in a.providers.split(",") if x.strip()] or None
    res = eng.find(a.service, a.country, provs, a.limit)
    for k, v in res["errors"].items():
        print(f"[!] {k}: {v}", file=sys.stderr)
    cands = res["candidates"][: a.limit]
    if not cands:
        print("Номеров не найдено.")
        return 1
    _print_table(cands)
    print(f"\nПроверено за {res['took']} c.")
    if a.cmd == "find":
        return 0

    c = cands[a.pick - 1]
    act = eng.start(c["id"], a.service, a.timeout)
    print(f"\n✅ Номер: {act.number}  ({c['provider']})")
    if act.cand.public:
        print("⚠  Номер публичный — SMS видны всем. Не используйте для важных аккаунтов.")
    print("Жду SMS... (Ctrl+C — отмена)")
    shown = 0
    try:
        while act.status in ("starting", "waiting"):
            for m in act.messages[shown:]:
                print(f"📩 {m.sender}: {m.text}" + (f"  [код {m.code}]" if m.code else ""))
            shown = len(act.messages)
            time.sleep(1)
    except KeyboardInterrupt:
        act.cancel()
    for m in act.messages[shown:]:
        print(f"📩 {m.sender}: {m.text}" + (f"  [код {m.code}]" if m.code else ""))
    print(f"\nСтатус: {act.status}" + (f"  КОД: {act.code}" if act.code else ""))
    return 0 if act.code else 2


if __name__ == "__main__":
    sys.exit(main())
