"""Подбор номеров по всем провайдерам, проверка в процессе работы и ожидание SMS.

Проверка идёт в три этапа:
  1. быстрый отсев (секунды): страница/API отвечает, номер не снят и не в архиве,
     последнее SMS не старше суток, номер не выдавался ранее (реестр);
  2. живая проверка (probe_s секунд): лучшие номера перепроверяются повторно —
     кто перестал отвечать, отсекается; если за это время пришло новое SMS,
     номер помечается «работает прямо сейчас»;
  3. проверка доставкой (по запросу): на номер отправляется SMS со случайным кодом;
     дошло — номер получает статус «проверенный» и попадает в отдельную категорию;
  4. сторожевой таймер во время ожидания SMS: номер периодически перепроверяется,
     и если он «умер», скрипт сам переключается на следующий из резерва.
"""
from __future__ import annotations

import os
import queue
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Iterator, Optional

import requests

from . import registry, sender, verify
from .core import Candidate, ProviderError, SearchStopped, Sms
from .providers import all_providers
from .services import country_matches, profile, service_code, service_matchers

MODES = {
    "niche": "Лучшие под сервис (рекомендуется)",
    "verified": "Только проверенные тестом под сервис",
    "premium": "Только платные приватные",
    "popular": "Все рабочие номера",
}


def telegram_notify(text: str) -> None:
    """Отправить сообщение в Telegram, если заданы TELEGRAM_TOKEN и TELEGRAM_CHAT_ID."""
    token, chat = os.getenv("TELEGRAM_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if not (token and chat):
        return
    try:
        requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                      json={"chat_id": chat, "text": text}, timeout=10)
    except requests.RequestException:
        pass


class Activation:
    WATCH_EVERY = 30  # сек между перепроверками номера во время ожидания

    def __init__(self, engine: "Engine", cand: Candidate, service: str, timeout: int, poll: float,
                 reserve: list[str]):
        self.id = uuid.uuid4().hex[:12]
        self.engine, self.service = engine, service
        self.timeout, self.poll = timeout, poll
        self.reserve = list(reserve)
        self.status = "starting"
        self.messages: list[Sms] = []
        self.code = ""
        self.codes: list[dict] = []     # все коды сервиса, пришедшие после выдачи номера
        self.seen_at: dict[str, int] = {}  # отпечаток SMS -> секунда с начала ожидания
        self.warning = ""
        self.error = ""
        self.started = time.time()
        self.events: list[str] = []
        self.history: list[str] = []
        self._stop = threading.Event()
        self.on_sms: list[Callable[["Activation", Sms], None]] = []
        self._bind(cand)

    def _bind(self, cand: Candidate):
        self.cand = cand
        self.provider = self.engine.providers[cand.provider]
        self.number = cand.number
        self.state: dict = {}
        self._baseline: set[str] = set()
        self._fails = 0
        self._last_watch = time.time()
        self._bound_at = time.time()

    def log(self, msg: str):
        self.events.append(f"{time.strftime('%H:%M:%S')} {msg}")

    def to_dict(self) -> dict:
        return {
            "id": self.id, "status": self.status, "number": self.number, "code": self.code,
            "error": self.error, "service": self.service, "provider": self.cand.provider,
            "public": self.cand.public, "tier": self.cand.tier, "elapsed": int(time.time() - self.started),
            "timeout": self.timeout, "candidate": self.cand.to_dict(), "events": self.events[-30:],
            "history": self.history, "reserve": len(self.reserve), "warning": self.warning,
            "codes": self.codes, "hint": profile(self.service).get("hint", ""),
            "messages": [dict(m.to_dict(), since_s=self.seen_at.get(m.key),
                              is_service=bool(self._service_code(m))) for m in self.messages],
        }

    def _service_code(self, m: Sms) -> str:
        """Код именно от выбранного сервиса (для Telegram — строго в формате Telegram)."""
        if profile(self.service).get("code_re"):
            return service_code(self.service, m.sender + " " + m.text)
        matchers = service_matchers(self.service)
        relevant = not matchers or any(k in (m.sender + " " + m.text).lower() for k in matchers)
        return m.code if relevant else ""

    def _forget(self, reason: str):
        """Номер больше не выдавать: убрать из реестра, пула проверенных и наблюдения."""
        if self.cand.public:
            registry.block(self.number, reason)
            verify.invalidate(self.number)
            self.engine.watcher.drop(self.number)

    def _acquire(self):
        self.state = self.provider.acquire(self.cand, self.service)
        self.number = self.state.get("number") or self.cand.number
        registry.mark_used(self.number, self.service, self.cand.provider)
        self.history.append(self.number)
        if self.cand.public:
            # публичный номер: запоминаем уже пришедшие SMS, ждём только новые
            try:
                self._baseline = {m.key for m in self.provider.messages(self.state)}
            except Exception:
                self._baseline = set(self.cand.extra.get("keys") or [])
        self.log(f"выдан номер {self.number} ({self.cand.provider}, {self.cand.tier})"
                 + (" — доставка подтверждена тестовым SMS" if self.cand.tier == "verified" else ""))

    def start(self) -> "Activation":
        self._acquire()
        self.status = "waiting"
        threading.Thread(target=self._loop, daemon=True).start()
        return self

    def _failover(self, why: str) -> bool:
        """Номер перестал работать — переключиться на следующий из резерва."""
        self.log(f"номер {self.number}: {why}" if why in ("заменён вручную", "не подошёл")
                 else f"номер {self.number} не работает: {why}")
        self._forget(why)
        self.code, self.codes, self.warning = "", [], ""
        if not self.cand.public and self.state:
            self.provider.release(self.state, success=False)
        while self.reserve:
            cid = self.reserve.pop(0)
            cand = self.engine.candidates.get(cid)
            if not cand or registry.is_used(cand.number, self.service):
                continue
            if cand.public:
                h = self.engine.providers[cand.provider].health(cand, self.service)
                if not h.ok:
                    self.log(f"резерв {cand.number} отсеян: {h.reason}")
                    continue
            try:
                self._bind(cand)
                self._acquire()
                self.status = "waiting"
                self.log("автозамена выполнена — запросите код на новый номер")
                telegram_notify(f"🔁 Номер заменён: {self.number}")
                return True
            except ProviderError as e:
                self.log(f"резерв не занят: {e}")
        self.status, self.error = "error", f"номер не работает ({why}), резерв закончился"
        return False

    def _loop(self):
        matchers = service_matchers(self.service)
        while not self._stop.is_set():
            if time.time() - self.started > self.timeout:
                self.status = "done" if self.code else "timeout"
                if not self.cand.public:  # платный номер без кода — отменяем, деньги вернутся
                    self.provider.release(self.state, success=bool(self.code))
                return
            # сторожевой таймер: периодически проверяем, что номер ещё живой
            if self.cand.public and time.time() - self._last_watch > self.WATCH_EVERY:
                self._last_watch = time.time()
                h = self.provider.health(self.cand, self.service)
                if not h.ok and not self._failover(h.reason):
                    return
            try:
                for m in self.provider.messages(self.state):
                    if m.key in self._baseline:
                        continue
                    self._baseline.add(m.key)
                    self.messages.append(m)
                    at = int(time.time() - self._bound_at)
                    self.seen_at[m.key] = at
                    code = self._service_code(m)
                    if code:
                        self.codes.append({"code": code, "since_s": at, "sender": m.sender, "text": m.text[:120]})
                        self.code = code          # всегда самый свежий код
                        self.status = "received"  # но продолжаем слушать номер
                        self.log(f"получен код {code} (через {at} с)")
                        if len(self.codes) > 1:
                            self.warning = (f"На номер пришло {len(self.codes)} кода(ов) — их запрашивают и другие люди. "
                                            "Ваш — тот, что пришёл сразу после ВАШЕГО запроса; самый свежий отмечен ★.")
                    for cb in self.on_sms:
                        cb(self, m)
                self._fails = 0
                if self.code and not self.cand.public:
                    self.provider.release(self.state, success=True)
                    return
            except ProviderError as e:
                self._fails += 1
                self.error = str(e)
                if "отменена" in self.error or self._fails >= 3:
                    if not self._failover(self.error):
                        return
            except Exception as e:  # сеть/разметка
                self._fails += 1
                self.error = f"{type(e).__name__}: {e}"
                if self._fails >= 3 and not self._failover(self.error):
                    return
            self._stop.wait(self.poll)

    def cancel(self):
        """Остановить: номер больше никогда не выдаётся и пропадает из списков."""
        self._stop.set()
        if not self.cand.public and self.state:
            self.provider.release(self.state, success=bool(self.code))
        self._forget("остановлен пользователем")
        if self.status in ("waiting", "starting", "received"):
            self.status = "cancelled"
        self.log("остановлено — номер убран из выдачи и пула")

    def feedback(self, ok: bool, reason: str = "", switch: bool = True) -> None:
        """Отзыв пользователя: код подошёл / не подошёл (с причиной)."""
        registry.feedback(self.number, self.service, ok, reason)
        if ok:
            self._stop.set()
            self.status = "done"
            self.log("✓ код подошёл")
            if not self.cand.public and self.state:
                self.provider.release(self.state, success=True)
            return
        self.log(f"✗ не подошёл: {reason or 'без причины'}")
        if not (switch and self._failover("не подошёл")):
            self._stop.set()
            self._forget(reason or "не подошёл")
            self.status = "cancelled"

    def switch(self) -> bool:
        """Ручная замена номера («этот не пришёл — дай другой»)."""
        return self._failover("заменён вручную")


class SearchJob:
    """Подбор номеров, выполняемый на сервере в фоне.

    Раньше страница получала события одним длинным потоком (SSE), но Cloudflare-туннель
    буферизует такой поток целиком и рвёт «молчащее» соединение ~через 100 с — на телефоне
    было видно «Проверено 0», а затем «Соединение прервано». Теперь подбор идёт здесь, а
    страница раз в секунду забирает новые события короткими запросами и может
    переподключиться в любой момент без потери прогресса.
    """

    def __init__(self, engine: "Engine", params: dict):
        self.id = uuid.uuid4().hex[:12]
        self.engine, self.params = engine, params
        self.events: list[dict] = []
        self.stop = threading.Event()
        self.started = time.time()
        self.finished = 0.0
        self.lock = threading.Lock()

    def _run(self):
        try:
            for ev in self.engine.find_stream(**self.params, stop=self.stop):
                with self.lock:
                    self.events.append(ev)
        except Exception as e:  # не даём задаче «зависнуть» молча
            with self.lock:
                self.events.append({"type": "error", "provider": "движок", "text": f"{type(e).__name__}: {e}"})
                self.events.append({"type": "done", "candidates": [], "errors": {"движок": str(e)},
                                    "took": round(time.time() - self.started, 1), "stopped": False})
        self.finished = time.time()

    def start(self) -> "SearchJob":
        threading.Thread(target=self._run, daemon=True).start()
        return self

    def poll(self, after: int) -> dict:
        with self.lock:
            evs = self.events[after:]
            n = len(self.events)
        return {"id": self.id, "events": evs, "next": n, "running": not self.finished,
                "elapsed": int(time.time() - self.started)}


class Engine:
    def __init__(self, providers: Optional[dict] = None):
        self.providers = providers or all_providers()
        self.candidates: dict[str, Candidate] = {}
        self.activations: dict[str, Activation] = {}
        self.last_order: list[str] = []
        self.verifications: dict[str, verify.Verification] = {}
        self.watcher = verify.Watcher(self)
        self.jobs: dict[str, "SearchJob"] = {}

    def provider_info(self) -> list[dict]:
        out = []
        for p in self.providers.values():
            ok, why = p.available()
            out.append({"name": p.name, "title": p.title, "paid": p.paid, "public": p.public,
                        "enabled": ok, "note": why})
        return out

    # --- подбор ----------------------------------------------------------
    def find_stream(self, service: str, country: str = "", providers: Optional[list[str]] = None,
                    limit: int = 15, mode: str = "niche", probe_s: int = 20,
                    verify_n: int = 0, verify_wait: int = 180, track: bool = True,
                    verify_method: str = "auto", stop: Optional[threading.Event] = None) -> Iterator[dict]:
        """Генератор событий проверки; последнее событие — {'type': 'done', ...}."""
        names = providers or [p["name"] for p in self.provider_info() if p["enabled"]]
        if mode == "premium":
            names = [n for n in names if self.providers[n].paid]
        elif mode == "verified":
            names = [n for n in names if not self.providers[n].paid]  # платный номер до покупки не проверить
            verify_n = verify_n or 2
        t0, q = time.time(), queue.Queue()
        results: dict[str, list[Candidate]] = {}
        errors: dict[str, str] = {}

        stop = stop or threading.Event()

        def emit(ev):
            if stop.is_set():
                raise SearchStopped()
            if ev.get("type") != "_ping":
                q.put(ev)

        def run(name):
            try:
                pmode = "popular" if mode == "verified" else mode
                results[name] = self.providers[name].find(service, country, limit * 2, pmode, emit)
            except SearchStopped:
                pass
            except Exception as e:
                errors[name] = str(e)
                q.put({"type": "error", "provider": name, "text": str(e)})
            q.put({"type": "_finished", "provider": name})

        svc_t = verify.svc_title(service)
        yield {"type": "stage", "stage": 1, "text": f"Этап 1 из 3: собираю номера и отсеиваю нерабочие под "
                                                    f"{svc_t} · источники: {', '.join(names)}"}
        for n in names:
            threading.Thread(target=run, args=(n,), daemon=True).start()
        left = len(names)
        while left:
            try:
                ev = q.get(timeout=1)
            except queue.Empty:
                continue
            if ev["type"] == "_finished":
                left -= 1
            else:
                yield ev

        cands = [c for cs in results.values() for c in cs]
        # строгий режим: только нишевые и премиум
        if mode == "niche":
            dropped = [c for c in cands if c.tier == "regular"]
            cands = [c for c in cands if c.tier != "regular"]
            if dropped:
                yield {"type": "stage", "text": f"Отсеяно как «заезженные» (много SMS или номер сейчас "
                                                f"занят другим человеком): {len(dropped)}"}
        elif mode == "premium":
            dropped = [c for c in cands if c.public or c.tier != "premium"]
            cands = [c for c in cands if not c.public and c.tier == "premium"]
            if dropped:
                yield {"type": "stage", "text": f"Отсеяно не-премиум (доставка <50%, виртуальный "
                                                f"оператор): {len(dropped)}"}

        # этап 2: живая проверка лучших бесплатных номеров
        free = sorted([c for c in cands if c.public], key=lambda c: c.score, reverse=True)[:limit]
        if free and probe_s > 0:
            yield {"type": "stage", "stage": 2, "text": f"Этап 2 из 3: живая перепроверка {len(free)} лучших номеров "
                                                        f"({probe_s} с)"}
            for rnd in (1, 2):
                if stop.wait(probe_s / 2):
                    break
                with ThreadPoolExecutor(max_workers=12) as ex:
                    res = list(ex.map(lambda c: (c, self.providers[c.provider].health(c, service)), free))
                alive = []
                for c, h in res:
                    if not h.ok:
                        c.log(f"этап 2, раунд {rnd}: отсеян — {h.reason}")
                        yield {"type": "check", "provider": c.provider, "number": c.number, "ok": False,
                               "reason": f"перестал отвечать: {h.reason}"}
                        cands.remove(c)
                        continue
                    new = set(h.keys) - set(c.extra.get("keys") or [])
                    if new and not c.extra.get("live"):
                        c.extra["live"] = True
                        c.score = round(c.score + 15, 1)
                        c.note += " · SMS пришло во время проверки"
                        # новое SMS во время наблюдения — записываем, от каких сервисов оно
                        try:
                            msgs = self.providers[c.provider].messages({"ref": c.ref, "number": c.number})
                            for sms in (m for m in msgs if m.key in new):
                                if service in verify.record_sms(c, sms, int(probe_s / 2 * rnd)):
                                    verify.mark(c, verify.cached(c.number, service), service)
                                    yield {"type": "verify", "provider": c.provider, "number": c.number,
                                           "ok": True, "id": c.id, "tier": c.tier, "note": c.note,
                                           "reason": f"во время проверки пришло SMS от {sms.sender} → "
                                                     f"«проверенный» для {svc_t}"}
                        except Exception:
                            pass
                    c.extra["keys"] = h.keys
                    c.log(f"этап 2, раунд {rnd}: отвечает за {h.latency_ms} мс"
                          + (", получено новое SMS" if new else ""))
                    yield {"type": "check", "provider": c.provider, "number": c.number, "ok": True,
                           "reason": f"раунд {rnd}: отвечает ({h.latency_ms} мс)"
                                     + (" · пришло новое SMS ✓" if new else "")}
                    alive.append(c)
                free = alive

        # статус «проверенный» под выбранный сервис из прошлых наблюдений и фонового пула
        for c in cands:
            rec = verify.cached(c.number, service) if c.public else None
            if rec:
                verify.mark(c, rec, service)
        if mode == "verified":
            have = {c.number for c in cands}
            for d in self.watcher.pool(service, country):
                c = self.candidates.get(d["id"])
                if c and c.number not in have:
                    cands.append(c)

        def finalize(lst):
            for c in lst:
                if not c.public and not c.extra.get("_paid_bonus"):
                    c.score = round(c.score + 15, 1)  # приватный номер надёжнее
                    c.extra["_paid_bonus"] = True
                c.extra["can_buy"] = self.providers[c.provider].can_acquire()
                self.candidates[c.id] = c
            # сначала доступные, затем НЕ занятые другим человеком, затем проверенные, затем score
            lst.sort(key=lambda c: (c.extra["can_buy"], not c.extra.get("busy_left"), c.tier == "verified",
                                    c.score), reverse=True)
            return lst[: limit * 2]

        cands = finalize(cands)
        if track:
            self.last_order = [c.id for c in cands if c.extra["can_buy"]]
        # результаты показываем сразу, тест доставки идёт поверх них
        yield {"type": "results", "candidates": [c.to_dict() for c in cands], "errors": errors,
               "took": round(time.time() - t0, 1)}

        # этап 3: тест доставки под выбранный сервис
        todo = [c for c in cands if c.public and c.tier != "verified"][:verify_n]
        if todo:
            yield {"type": "stage", "stage": 3, "text": f"Этап 3 из 3: тест доставки {svc_t} на {len(todo)} номерах — "
                                                        f"жду новое SMS от {svc_t} до {max(1, verify_wait // 60)} мин "
                                                        "(номера уже можно брать)"}
            vq: queue.Queue = queue.Queue()
            jobs = [verify.Verification(self, c, verify_wait, vq.put, verify_method, service, stop) for c in todo]

            def run_v(j):
                try:
                    j.run()
                finally:
                    vq.put({"type": "_vdone"})
            for j in jobs:
                self.verifications[j.id] = j
                threading.Thread(target=run_v, args=(j,), daemon=True).start()
            left = len(jobs)
            while left:
                try:
                    ev = vq.get(timeout=1)
                except queue.Empty:
                    continue
                if ev["type"] == "_vdone":
                    left -= 1
                else:
                    yield ev
        if mode == "verified" and not stop.is_set():
            cands = [c for c in cands if c.tier == "verified"]
            yield {"type": "stage", "text": f"Проверенных для {svc_t}: {len(cands)}"}
        cands = finalize(cands)
        if track:
            self.last_order = [c.id for c in cands if c.extra["can_buy"]]
        yield {"type": "done", "candidates": [c.to_dict() for c in cands], "errors": errors,
               "took": round(time.time() - t0, 1), "used_total": registry.count(), "stopped": stop.is_set()}

    # --- подбор как фоновая задача (переживает обрывы соединения) ------------
    def start_search(self, **kw) -> "SearchJob":
        job = SearchJob(self, kw).start()
        self.jobs[job.id] = job
        for jid, j in list(self.jobs.items()):  # чистим старые задачи
            if j.finished and time.time() - j.finished > 1800:
                del self.jobs[jid]
        return job

    def find(self, *a, **kw) -> dict:
        last = {}
        for ev in self.find_stream(*a, **kw):
            last = ev
        return last

    def verify(self, cand_id: str, wait: int = 180, method: str = "auto",
               service: str = "other") -> verify.Verification:
        """Проверить один номер доставкой тестового SMS (в фоне)."""
        cand = self.candidates.get(cand_id)
        if not cand:
            raise ProviderError("кандидат не найден — выполните поиск заново")
        if not cand.public:
            raise ProviderError("платный номер можно проверить только после покупки")
        job = verify.Verification(self, cand, wait, method=method, service=service).start()
        self.verifications[job.id] = job
        return job

    # --- выдача и ожидание -------------------------------------------------
    def start(self, cand_id: str, service: str, timeout: int = 600, poll: float = 5.0) -> Activation:
        cand = self.candidates.get(cand_id)
        if not cand:
            raise ProviderError("кандидат не найден — выполните поиск заново")
        reserve = [i for i in self.last_order if i != cand_id
                   and self.candidates[i].public == cand.public]
        act = Activation(self, cand, service, timeout, poll, reserve)
        act.on_sms.append(lambda a, m: telegram_notify(
            f"📩 SMS на {a.number} ({a.cand.provider})\nОт: {m.sender}\n{m.text}"
            + (f"\nКод: {m.code}" if m.code else "")))
        act.start()
        self.activations[act.id] = act
        return act

    def auto(self, service: str, country: str = "", providers: Optional[list[str]] = None,
             timeout: int = 600, mode: str = "niche", probe_s: int = 20, tries: int = 3,
             verify_n: int = 0) -> Activation:
        """Подобрать лучший номер и занять его; при ошибке покупки — следующий."""
        res = self.find(service, country, providers, 15, mode, probe_s, verify_n)
        last_err = None
        for c in [c for c in res.get("candidates", []) if c["extra"].get("can_buy")][:tries]:
            try:
                return self.start(c["id"], service, timeout)
            except ProviderError as e:
                last_err = e
        raise ProviderError(f"не удалось занять номер: {last_err or res.get('errors') or 'нет кандидатов'}")
