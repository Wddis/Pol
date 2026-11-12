"""Умный режим: чистые номера + тест 2/2 своими шлюзами.

1. Отбор (этап 1, mode="smart"): номер живой (SMS приходили за последние 6 ч) и ЧИСТЫЙ —
   выбранный сервис ни разу не присылал на него SMS (по истории сайта и нашим наблюдениям).
2. Тест: на номер уходят два SMS со своими случайными кодами по двум случайным разным
   маршрутам (sender.pick_two: телефоны/SIM в SMS Gateway for Android, Telnyx, httpSMS...).
   Лента номера опрашивается каждые POLL с; оба кода во входящих — 2/2, номер принимает SMS.
3. Перед отметкой номер ещё раз проверяется на чистоту (сервис мог написать за время теста).

Номера тестируются параллельно; как только набрано нужное число 2/2, подбор останавливается.
Отправленные SMS видны всем на странице номера вместе с номером отправителя.
"""
from __future__ import annotations

import queue
import secrets
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from . import sender, verify
from .core import Candidate, Emit, noop_emit
from .services import service_matchers

POLL = 3.0


class SmartCheck:
    def __init__(self, engine, cand: Candidate, service: str, wait: int = 90, emit: Emit = noop_emit,
                 stop: threading.Event | None = None):
        self.engine, self.cand, self.service, self.wait = engine, cand, service, wait
        self.emit, self.stop = emit, stop or threading.Event()
        self.provider = engine.providers[cand.provider]
        self.status, self.detail, self.got, self.routes = "pending", "", 0, []
        self.sent = 0

    def _say(self, ok: bool, reason: str):
        self.cand.log(f"умный тест: {reason}")
        self.emit({"type": "verify", "provider": self.cand.provider, "number": self.cand.number, "ok": ok,
                   "reason": reason, "status": self.status, "id": self.cand.id, "note": self.cand.note,
                   "tier": self.cand.tier, "smart": f"{self.got}/2"})

    def _dirty(self, msgs) -> bool:
        m = service_matchers(self.service)
        return bool(m) and any(any(k in (x.sender + " " + x.text).lower() for k in m) for x in msgs)

    def run(self) -> "SmartCheck":
        c = self.cand
        routes, warn = sender.pick_two()
        if not routes:
            self.status = "failed"
            self._say(False, warn)
            return self
        self.routes = [r.name for r in routes]
        tokens = [f"{secrets.randbelow(900000) + 100000}" for _ in routes]
        while tokens[1] == tokens[0]:
            tokens[1] = f"{secrets.randbelow(900000) + 100000}"
        try:
            before = self.provider.messages({"ref": c.ref, "number": c.number})
        except Exception as e:
            self.status = "failed"
            self._say(False, f"лента недоступна ({e})")
            return self
        if self._dirty(before):
            self.status = "failed"
            self._say(False, f"не чистый: в ленте есть SMS от {self.service}")
            return self

        t0 = time.time()
        with ThreadPoolExecutor(max_workers=2) as ex:  # оба SMS — одновременно
            res = list(ex.map(lambda rt: rt[0].send(c.number, f"Check code {rt[1]}"), zip(routes, tokens)))
        self.sent = sum(1 for r in res if r.ok)
        if not self.sent:
            self.status = "failed"
            self._say(False, "шлюзы не отправили: " + "; ".join(f"{r.via}: {r.error}" for r in res))
            return self
        failed = [r for r in res if not r.ok]
        self.status = "watching"
        self._say(True, f"отправлено {self.sent}/2 ({', '.join(self.routes)})" + (f" · {warn}" if warn else "")
                  + (f" · не ушло: {failed[0].via}: {failed[0].error}" if failed else ""))

        seen = [False, False]
        delivered_s = 0
        while time.time() - t0 < self.wait and not self.stop.is_set():
            if self.stop.wait(POLL):
                break
            try:
                msgs = self.provider.messages({"ref": c.ref, "number": c.number})
            except Exception:
                continue
            text = " ".join(m.text for m in msgs)
            for i, tok in enumerate(tokens):
                if not seen[i] and res[i].ok and tok in text:
                    seen[i] = True
                    delivered_s = int(time.time() - t0)
            self.got = sum(seen)
            if all(seen) or (self.got == self.sent and self.sent < 2):
                break
        self.got = sum(seen)
        # перед отметкой — ещё раз на чистоту: сервис мог написать сюда за время теста
        try:
            if self._dirty(self.provider.messages({"ref": c.ref, "number": c.number})):
                self.status = "failed"
                self._say(False, f"за время теста пришло SMS от {self.service} — номер больше не чистый")
                return self
        except Exception:
            pass
        if self.got < 2 and self.stop.is_set() and time.time() - t0 < self.wait:
            self.status = "cancelled"  # цель уже набрана или подбор остановлен — это не провал номера
            self._say(False, f"тест прерван на {self.got}/2 — цель уже набрана")
            return self
        if self.got == 2:
            self.status = "verified"
            rec = {"ok": True, "at": time.time(), "kind": "smart", "delivered_s": delivered_s,
                   "routes": self.routes, "via": "+".join(self.routes)}
            verify._save(c, self.service, rec)
            verify.mark(c, rec, self.service)
            self._say(True, f"2/2 — оба SMS дошли за {delivered_s} с → номер рабочий и чистый")
        else:
            self.status = "failed"
            why = "ни одно SMS не дошло" if self.got == 0 else "дошло только одно SMS из двух"
            c.note = f"✗ умный тест {self.got}/2: {why} · " + c.note
            self._say(False, f"{self.got}/2 за {int(time.time() - t0)} с — {why}")
        return self


def run_smart(engine, feed: "queue.Queue[Candidate]", feeding_done: threading.Event, service: str,
              target: int, parallel: int, wait: int, max_sms: int, emit: Emit,
              stop: threading.Event) -> list[Candidate]:
    """Тестировать кандидатов конвейером: одновременно `parallel` номеров. Номера берутся из
    очереди `feed`, которую движок пополняет на ходу (опоздавшие сайты, досмотр); тест первых
    номеров начинается сразу. Стоп — `target` номеров 2/2, кончился лимит SMS или кандидаты."""
    good: list[Candidate] = []
    lock = threading.Lock()
    user_stop, stop = stop, threading.Event()  # своё событие: цель достигнута ≠ «пользователь остановил»
    threading.Thread(target=lambda: (user_stop.wait(), stop.set()), daemon=True).start()
    budget = {"sms": max_sms}

    def next_cand():
        while not stop.is_set():
            with lock:
                if budget["sms"] < 2:
                    return None
            try:
                c = feed.get(timeout=0.3)
            except queue.Empty:
                if feeding_done.is_set() and feed.empty():
                    return None
                continue
            with lock:
                if budget["sms"] < 2:
                    return None
                budget["sms"] -= 2
            return c
        return None

    def worker():
        while True:
            c = next_cand()
            if c is None:
                return
            chk = SmartCheck(engine, c, service, wait, emit, stop)
            chk.run()
            with lock:
                budget["sms"] += 2 - chk.sent  # неотправленные SMS возвращаются в лимит
                if chk.status == "verified":
                    good.append(c)
                    if len(good) >= target:
                        stop.set()  # остальные тесты прекращают ждать (но не весь подбор)

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(max(1, parallel))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return good
