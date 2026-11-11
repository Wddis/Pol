from __future__ import annotations

from typing import Optional

from ..core import Candidate, Sms


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

    def find(self, service: str, country: str, limit: int = 20) -> list[Candidate]:
        """Найти и проверить номера. Возвращает кандидатов с выставленным score."""
        raise NotImplementedError

    def acquire(self, cand: Candidate, service: str) -> dict:
        """Занять номер. Возвращает state: {'number': ..., ...}."""
        return {"number": cand.number, "ref": cand.ref}

    def messages(self, state: dict) -> list[Sms]:
        raise NotImplementedError

    def release(self, state: dict, success: bool) -> Optional[str]:
        """Завершить/отменить активацию (для платных)."""
        return None
