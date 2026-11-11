"""Отправка тестовых SMS для проверки номеров.

Шлюзы (используется первый настроенный, Textbelt free — запасной):
  textbee   — open-source шлюз github.com/textbee/textbee: SMS уходит с вашего Android-телефона.
              TEXTBEE_KEY [, TEXTBEE_DEVICE]
  httpsms   — open-source шлюз github.com/NdoleStudio/httpsms (тоже через ваш Android).
              HTTPSMS_KEY, HTTPSMS_FROM
  twilio    — TWILIO_SID, TWILIO_TOKEN, TWILIO_FROM
  textbelt  — TEXTBELT_KEY (платный ключ) или бесплатный ключ "textbelt": 1 SMS в день с одного IP.
"""
from __future__ import annotations

import os
import threading
from dataclasses import dataclass
from typing import Optional

import requests

TIMEOUT = 20


@dataclass
class SendResult:
    ok: bool
    via: str
    id: str = ""
    error: str = ""
    quota: Optional[int] = None


class Sender:
    name = "base"

    def configured(self) -> bool:
        return False

    def quota(self) -> Optional[int]:
        """Сколько SMS ещё можно отправить (None — без ограничения/неизвестно)."""
        return None

    def send(self, to: str, text: str) -> SendResult:
        raise NotImplementedError


class TextBelt(Sender):
    name = "textbelt"
    blocked = ""  # причина, если бесплатная отправка отклонена (запоминаем, чтобы не повторять)

    def __init__(self):
        self.key = os.getenv("TEXTBELT_KEY", "textbelt")
        self._quota: Optional[int] = None

    def configured(self):
        return True

    def quota(self):
        try:
            self._quota = requests.get(f"https://textbelt.com/quota/{self.key}", timeout=TIMEOUT).json().get(
                "quotaRemaining")
        except (requests.RequestException, ValueError):
            pass
        return self._quota

    def send(self, to, text):
        try:
            d = requests.post("https://textbelt.com/text", timeout=TIMEOUT,
                              data={"phone": to, "message": text, "key": self.key}).json()
        except (requests.RequestException, ValueError) as e:
            return SendResult(False, self.name, error=str(e))
        self._quota = d.get("quotaRemaining", self._quota)
        if "disabled" in (d.get("error") or "") and self.key == "textbelt":
            TextBelt.blocked = d["error"]
        return SendResult(bool(d.get("success")), self.name, id=str(d.get("textId") or ""),
                          error=d.get("error") or "", quota=self._quota)

    def status(self, text_id: str) -> str:
        try:
            return requests.get(f"https://textbelt.com/status/{text_id}", timeout=TIMEOUT).json().get("status", "")
        except (requests.RequestException, ValueError):
            return ""


class TextBee(Sender):
    name = "textbee"

    def __init__(self):
        self.key, self.device = os.getenv("TEXTBEE_KEY", ""), os.getenv("TEXTBEE_DEVICE", "")

    def configured(self):
        return bool(self.key)

    def send(self, to, text):
        body = {"recipients": [to], "message": text}
        if self.device:
            body["deviceId"] = self.device
        try:
            r = requests.post("https://api.textbee.dev/api/v1/gateway/send-sms", json=body, timeout=TIMEOUT,
                              headers={"x-api-key": self.key})
            ok = r.status_code < 300
            return SendResult(ok, self.name, error="" if ok else r.text[:200])
        except requests.RequestException as e:
            return SendResult(False, self.name, error=str(e))


class HttpSms(Sender):
    name = "httpsms"

    def __init__(self):
        self.key, self.frm = os.getenv("HTTPSMS_KEY", ""), os.getenv("HTTPSMS_FROM", "")

    def configured(self):
        return bool(self.key and self.frm)

    def send(self, to, text):
        try:
            r = requests.post("https://api.httpsms.com/v1/messages/send", timeout=TIMEOUT,
                              headers={"x-api-key": self.key},
                              json={"from": self.frm, "to": to, "content": text})
            ok = r.status_code < 300
            return SendResult(ok, self.name, error="" if ok else r.text[:200])
        except requests.RequestException as e:
            return SendResult(False, self.name, error=str(e))


class Twilio(Sender):
    name = "twilio"

    def __init__(self):
        self.sid, self.token = os.getenv("TWILIO_SID", ""), os.getenv("TWILIO_TOKEN", "")
        self.frm = os.getenv("TWILIO_FROM", "")

    def configured(self):
        return bool(self.sid and self.token and self.frm)

    def send(self, to, text):
        try:
            r = requests.post(f"https://api.twilio.com/2010-04-01/Accounts/{self.sid}/Messages.json",
                              auth=(self.sid, self.token), timeout=TIMEOUT,
                              data={"From": self.frm, "To": to, "Body": text})
            d = r.json()
            ok = r.status_code < 300
            return SendResult(ok, self.name, id=d.get("sid", ""), error="" if ok else d.get("message", r.text[:200]))
        except (requests.RequestException, ValueError) as e:
            return SendResult(False, self.name, error=str(e))


_lock = threading.Lock()


def get_sender() -> Sender:
    for cls in (TextBee, HttpSms, Twilio):
        s = cls()
        if s.configured():
            return s
    return TextBelt()


def describe() -> dict:
    s = get_sender()
    free = s.name == "textbelt" and os.getenv("TEXTBELT_KEY", "textbelt") == "textbelt"
    note = ""
    if free:
        note = ("бесплатный Textbelt отклонил отправку: " + TextBelt.blocked) if TextBelt.blocked else \
            "бесплатный Textbelt: 1 SMS в день и для многих стран отключён"
        note += " — для надёжной проверки задайте TEXTBEE_KEY (github.com/textbee/textbee) или Twilio"
    return {"via": s.name, "quota": s.quota(), "free": free, "note": note}


def send(to: str, text: str) -> SendResult:
    with _lock:  # чтобы параллельные проверки не перерасходовали квоту
        s = get_sender()
        q = s.quota()
        if q is not None and q <= 0:
            return SendResult(False, s.name, error="квота отправки исчерпана", quota=0)
        if isinstance(s, TextBelt) and TextBelt.blocked and s.key == "textbelt":
            return SendResult(False, s.name, error=TextBelt.blocked)
        return s.send(to, text)
