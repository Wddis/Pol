from __future__ import annotations

from typing import Optional

from ..core import Candidate, Emit, Health, Sms, noop_emit


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

    def listing(self, country: str, mode: str) -> list[Candidate]:
        raise NotImplementedError

    def find(self, service, country, limit=20, mode="niche", emit=noop_emit):
        from ..core import fmt_ago, score_free  # локально, чтобы не плодить циклы импорта
        from .. import registry

        items = self.listing(country, mode)[: self.max_check]
        emit({"type": "stage", "provider": self.name, "text": f"{len(items)} номеров на проверку"})

        def check(c: Candidate):
            if registry.is_used(c.number, service):
                c.log("отсеян: уже выдавался для этого сервиса")
                emit({"type": "check", "provider": self.name, "number": c.number, "ok": False,
                      "reason": "уже выдавался"})
                return None
            h = self.health(c, service)
            if h.ok and h.last_s is not None and h.last_s > self.max_age:
                h.ok, h.reason = False, f"нет SMS {fmt_ago(h.last_s)}"
            if not h.ok:
                c.log(f"отсеян: {h.reason}")
                emit({"type": "check", "provider": self.name, "number": c.number, "ok": False,
                      "reason": h.reason})
                return None
            c.score = score_free(h, c.country, mode)
            # нишевый: целевой сервис на номере не регистрировался и номер малоиспользуемый
            c.tier = "niche" if (h.service_seen == 0 and (h.usage <= 25 or h.services <= 5)) else "regular"
            c.extra.update(last_s=h.last_s, usage=h.usage, services=h.services,
                           service_seen=h.service_seen, latency_ms=h.latency_ms, keys=h.keys)
            fresh = f"посл. SMS {fmt_ago(h.last_s)} назад" if h.last_s is not None else "лента активна (Live)"
            c.note = (f"{fresh} · SMS: {h.usage} · сервисов: {h.services} · "
                      + ("сервис здесь НЕ регистрировался" if h.service_seen == 0
                         else f"сервис уже был здесь {h.service_seen}×"))
            c.log(f"этап 1 пройден: {c.note}, ответ {h.latency_ms} мс")
            emit({"type": "check", "provider": self.name, "number": c.number, "ok": True,
                  "reason": c.note, "score": c.score})
            return c

        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=12) as ex:
            out = [c for c in ex.map(check, items) if c]
        out.sort(key=lambda c: c.score, reverse=True)
        return out[:limit]
