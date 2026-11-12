from __future__ import annotations

import dataclasses
import threading
import time
from typing import Optional

from ..core import Candidate, Emit, Health, SearchStopped, Sms, noop_emit


SMART_MAX_AGE = 6 * 3600  # умный режим: номер должен получать SMS прямо сейчас


def smart_verdict(service: str, number: str, h: Health):
    """Умный режим: нужен «чистый» номер — выбранный сервис на него НИКОГДА не присылал SMS
    (ни в видимой истории сайта, ни в наших прошлых наблюдениях), и номер живой."""
    from .. import verify
    from ..services import SERVICES
    name = service.capitalize() if service and service != "other" else "сервис"
    if h.service_seen:
        return False, f"не чистый: {name} уже присылал сюда SMS ({h.service_seen} шт.)", 0
    rec = verify.cached(number, service) if service and service != "other" else None
    if rec and rec.get("kind") != "smart":
        return False, f"не чистый: мы видели здесь SMS от {name}", 0
    if h.last_s is None or h.last_s > SMART_MAX_AGE:
        return False, "чистый, но SMS сюда давно не приходили — номер может не принимать", 0
    if service in SERVICES and service != "other":
        label = f"чистый: {name} сюда не писал (в истории {h.usage} SMS от {h.services} отправителей)"
    else:
        label = "живой номер"
    return True, label, 10


class Provider:
    """Базовый интерфейс провайдера номеров."""

    name = "base"
    title = "Base"
    paid = False      # нужна оплата / API-ключ
    public = True     # номера публичные (SMS видят все)

    def available(self) -> tuple[bool, str]:
        return True, ""

    def can_acquire(self) -> bool:
        """Можно ли занять номер (для платных — есть ли API-ключ)."""
        return True

    def find(self, service: str, country: str, limit: int = 20, mode: str = "niche",
             emit: Emit = noop_emit) -> list[Candidate]:
        """Найти номера и выполнить быструю проверку (этап 1).

        Через emit отправляет события {'type': 'check', ...} по каждому номеру,
        чтобы интерфейс показывал проверку в процессе.
        """
        raise NotImplementedError

    def health(self, cand: Candidate, service: str) -> Health:
        """Проверка работоспособности номера (используется повторно в этапе 2
        и во время ожидания SMS — сторожевой таймер)."""
        return Health(ok=True)

    def acquire(self, cand: Candidate, service: str) -> dict:
        """Занять номер. Возвращает state: {'number': ..., ...}."""
        return {"number": cand.number, "ref": cand.ref}

    def messages(self, state: dict) -> list[Sms]:
        raise NotImplementedError

    def release(self, state: dict, success: bool) -> Optional[str]:
        """Завершить/отменить активацию (для платных)."""
        return None


class FreeProvider(Provider):
    """Общая логика бесплатных сайтов: список номеров -> параллельная проверка -> отсев."""

    max_check = 40          # сколько номеров проверять за один поиск
    max_age = 24 * 3600     # номер без SMS дольше этого считается нерабочим
    dead_ttl = 3600         # «мёртвый» номер (архив / нет SMS) не перепроверяем час
    workers = 24          # одновременных проверок на сайт (HTTP/2: потоки по одному соединению)
    grace = 0.3           # сколько после бюджета ждать уже начатые проверки, с
    min_window = 0.35     # минимум времени на проверки после получения списка, с

    # Кеш для повторных подборов на работающем сервере: страница номера, проверенная меньше
    # health_ttl секунд назад, на этапе 1 не скачивается заново (живая перепроверка — всегда свежая).
    listing_ttl = 30
    health_ttl = 45

    def __init__(self):
        self._dead: dict[str, tuple[float, str]] = {}
        self._dead_lock = threading.Lock()
        self._lcache: dict = {}
        self._hcache: dict = {}

    def _listing_cached(self, service: str, country: str, mode: str) -> list[Candidate]:
        key = (service, country, mode)
        rec = self._lcache.get(key)
        if rec and time.time() - rec[0] < self.listing_ttl:
            base = rec[1]
        else:
            base = self.listing_for(service, country, mode)
            self._lcache[key] = (time.time(), base)
        # подбор меняет кандидатов (score, tier, note) — каждому подбору свежие копии с новыми id
        return [Candidate(provider=c.provider, number=c.number, country=c.country, ref=c.ref, price=c.price,
                          public=c.public, stock=c.stock, extra=dict(c.extra)) for c in base]

    def _health_cached(self, c: Candidate, service: str) -> Health:
        key = (c.number, service)
        rec = self._hcache.get(key)
        if rec and time.time() - rec[0] < self.health_ttl:
            h, dt = dataclasses.replace(rec[1]), int(time.time() - rec[0])
            if h.last_s is not None:  # «последнее SMS N с назад» — с поправкой на возраст кеша
                h.last_s += dt
            if h.service_last_s is not None:
                h.service_last_s += dt
            return h
        h = self.health(c, service)
        transient = not h.ok and any(k in h.reason for k in ("недоступн", "HTTP 4", "HTTP 5"))
        if not transient:  # сетевые сбои и ограничения сайта не запоминаем
            self._hcache[key] = (time.time(), dataclasses.replace(h))
        return h

    def _is_dead(self, number: str) -> bool:
        rec = self._dead.get(number)
        return bool(rec) and rec[0] > time.time()

    def _mark_dead(self, number: str, reason: str):
        with self._dead_lock:
            self._dead[number] = (time.time() + self.dead_ttl, reason)

    def listing(self, country: str, mode: str) -> list[Candidate]:
        raise NotImplementedError

    def listing_for(self, service: str, country: str, mode: str) -> list[Candidate]:
        """Список номеров с учётом сервиса (провайдер может поставить первыми номера,
        куда сервис доставлял SMS). По умолчанию — обычный listing()."""
        return self.listing(country, mode)

    def find(self, service, country, limit=20, mode="niche", emit=noop_emit, deadline=None):
        """deadline — момент (time.time()), после которого новые проверки не начинаются:
        номера проверяются в порядке приоритета, и подбор выдаёт то, что успел проверить
        (самые перспективные номера стоят в начале списка)."""
        from ..core import fmt_ago, score_free  # локально, чтобы не плодить циклы импорта
        from .. import registry

        listed = self._listing_cached(service, country, mode)
        if deadline is not None and time.time() + 0.15 > deadline:
            # медленная сеть: список пришёл почти к концу бюджета — всё равно даём время на проверки
            deadline = time.time() + self.min_window
        alive = [c for c in listed if not self._is_dead(c.number)]
        skipped = len(listed) - len(alive)
        items = alive[: self.max_check]
        emit({"type": "stage", "provider": self.name, "total": len(items),
              "text": f"{len(items)} номеров на проверку"
                      + (f" (ещё {skipped} пропущено — признаны мёртвыми за последний час)" if skipped else "")})
        limited = {"n": 0}

        late = {"n": 0}

        def check(c: Candidate):
            emit({"type": "_ping"})  # если подбор остановлен, движок прервёт проверку здесь
            if deadline is not None and time.time() > deadline:
                late["n"] += 1  # бюджет времени исчерпан — номер не проверяем (не отсеиваем навсегда)
                return None
            if limited["n"] >= 4:  # сайт начал ограничивать запросы — не долбим его дальше
                return None
            if registry.is_used(c.number, service):
                why = "остановлен / не подошёл ранее" if registry.is_blocked(c.number) else "уже выдавался"
                c.log(f"отсеян: {why}")
                emit({"type": "check", "provider": self.name, "number": c.number, "ok": False, "reason": why})
                return None
            h = self._health_cached(c, service)
            if h.ok and h.last_s is not None and h.last_s > self.max_age:
                h.ok, h.reason = False, f"нет SMS {fmt_ago(h.last_s)}"
            if not h.ok and any(k in h.reason for k in ("HTTP 403", "HTTP 429", "HTTP 503")):
                limited["n"] += 1
                if limited["n"] == 4:
                    emit({"type": "error", "provider": self.name,
                          "text": "сайт ограничил частоту запросов — остальные номера этого сайта пропущены"})
            elif not h.ok and any(k in h.reason for k in ("архив", "ни одного SMS", "снят", "нет SMS", "отключён")):
                self._mark_dead(c.number, h.reason)
            if not h.ok:
                c.log(f"отсеян: {h.reason}")
                emit({"type": "check", "provider": self.name, "number": c.number, "ok": False,
                      "reason": h.reason})
                return None
            from ..services import profile, service_verdict
            if mode == "smart":
                ok, label, delta = smart_verdict(service, c.number, h)
            else:
                ok, label, delta = service_verdict(service, h.service_seen, h.service_last_s, h.usage)
            if not ok:
                c.log(f"отсеян: {label}")
                emit({"type": "check", "provider": self.name, "number": c.number, "ok": False, "reason": label})
                return None
            c.score = round(score_free(h, c.country, "niche" if mode == "smart" else mode) + delta, 1)
            c.extra["svc_label"] = label
            strict = bool(profile(service).get("strict"))
            busy = h.service_last_s is not None and h.service_last_s < profile(service).get("busy_s", 0)
            low = h.usage <= 25 or h.services <= 5
            # нишевый: сейчас никто не ждёт код этого сервиса и номер малоиспользуемый;
            # для «строгих» сервисов (Telegram) — сервис сюда реально доставляет SMS, но редко (1–4 кода)
            if mode == "smart":
                niche = True  # чистый номер: сервис сюда не писал
            elif strict:
                niche = not busy and 1 <= h.service_seen <= 4
            else:
                niche = not busy and low and h.service_seen == 0
            c.tier = "niche" if niche else "regular"
            c.extra.update(last_s=h.last_s, usage=h.usage, services=h.services, svc_last_s=h.service_last_s,
                           service_seen=h.service_seen, latency_ms=h.latency_ms, keys=h.keys)
            fresh = f"посл. SMS {fmt_ago(h.last_s)} назад" if h.last_s is not None else "лента активна (Live)"
            c.note = f"{fresh} · SMS: {h.usage} · сервисов: {h.services}" + (f" · {label}" if label else "")
            c.log(f"этап 1 пройден: {c.note}, ответ {h.latency_ms} мс")
            emit({"type": "check", "provider": self.name, "number": c.number, "ok": True,
                  "reason": c.note, "score": c.score})
            return c

        from concurrent.futures import ThreadPoolExecutor, wait
        ex = ThreadPoolExecutor(max_workers=max(1, min(self.workers, len(items))))
        futs = [ex.submit(check, c) for c in items]
        # после бюджета ждём зависшие запросы не дольше grace — остальное отбрасываем
        done, hung = wait(futs, timeout=None if deadline is None else max(0.0, deadline - time.time()) + self.grace)
        ex.shutdown(wait=False, cancel_futures=True)
        out = []
        for f in futs:
            if f not in done:
                continue
            err = f.exception()
            if isinstance(err, SearchStopped):
                raise err  # подбор остановлен пользователем
            if err is None and f.result():
                out.append(f.result())
        late["n"] += len(hung)
        if late["n"]:
            emit({"type": "stage", "provider": self.name,
                  "text": f"проверено {len(items) - late['n']} из {len(items)} за отведённое время, "
                          f"годных: {len(out)}"})
        out.sort(key=lambda c: c.score, reverse=True)
        return out[:limit]
