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
    print(f"{'#':>2}  {'score':>5}  {'класс':<8} {'провайдер':<12} {'страна':<12} {'номер':<17} {'цена':>7}  заметка")
    for i, c in enumerate(cands, 1):
        price = "free" if c["public"] else f"{c['price']:.2f}"
        print(f"{i:>2}  {c['score']:>5}  {c['tier']:<8} {c['provider']:<12} {c['country'][:12]:<12} "
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
        p.add_argument("--mode", default="niche", choices=["verified", "niche", "premium", "popular", "smart"])
        p.add_argument("--verify", type=int, default=0,
                       help="сколько лучших номеров проверить тестовым SMS (тратит квоту отправки)")
        p.add_argument("--verify-wait", type=int, default=180, help="сколько ждать подтверждения доставки, сек")
        p.add_argument("--verify-method", default="auto", choices=["auto", "passive", "active"],
                       help="passive — ждать новое входящее SMS (бесплатно); active — отправить тестовое SMS")
        p.add_argument("--probe", type=int, default=20, help="длительность живой проверки, сек")
        p.add_argument("--target", type=int, default=0,
                       help="сколько номеров найти тестом (для --mode smart — сколько номеров 2/2, по умолчанию 3)")
        p.add_argument("-v", "--verbose", action="store_true", help="показывать каждую проверку")
        if name == "get":
            p.add_argument("--pick", type=int, default=1, help="номер строки из списка (1 = лучший)")
            p.add_argument("--timeout", type=int, default=600)
    a = ap.parse_args(argv)

    eng = Engine()
    provs = [x.strip() for x in a.providers.split(",") if x.strip()] or None
    res = {}
    for ev in eng.find_stream(a.service, a.country, provs, a.limit, a.mode, a.probe, a.verify, a.verify_wait,
                              verify_method=a.verify_method, verify_target=a.target):
        if ev["type"] == "stage":
            print(f"» {ev['text']}" + (f" [{ev['provider']}]" if ev.get("provider") else ""))
        elif ev["type"] == "error":
            print(f"[!] {ev.get('provider', '')}: {ev['text']}", file=sys.stderr)
        elif ev["type"] == "verify":
            print(f"  {'✓' if ev['ok'] else '✗'} SMS-проверка {ev['number']}: {ev['reason']}")
        elif ev["type"] == "check" and (a.verbose or ev["ok"] is False and "перестал" in ev["reason"]):
            print(f"  {'✓' if ev['ok'] else '✗'} {ev['provider']:<12} {ev['number']:<16} {ev['reason']}")
        elif ev["type"] == "done":
            res = ev
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
