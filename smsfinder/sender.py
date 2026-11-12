"""Отправка тестовых SMS для проверки номеров.

Маршрут (route) — один независимый способ отправить SMS. Умный режим шлёт на номер два SMS
по двум случайным разным маршрутам; если дошли оба — номер принимает SMS (2/2).

  smsgate  — SMS Gateway for Android (github.com/capcom6/android-sms-gateway, ★6000, open-source,
             бесплатное облако api.sms-gate.app). SMS уходит с вашего Android-телефона;
             каждый телефон и каждая его SIM — отдельный маршрут.
             SMSGATE_LOGIN + SMSGATE_PASSWORD (выдаёт приложение, режим Cloud) или SMSGATE_TOKEN;
             SMSGATE_URL — свой сервер (по умолчанию облако); SMSGATE_SIMS — какие SIM использовать, «1,2».
  httpsms  — github.com/NdoleStudio/httpsms (тоже через ваш Android). HTTPSMS_KEY, HTTPSMS_FROM
  telnyx   — платный API без телефона, шлёт на международные номера. TELNYX_KEY, TELNYX_FROM
  twilio   — платный. TWILIO_SID, TWILIO_TOKEN, TWILIO_FROM (пробный аккаунт шлёт только на
             подтверждённые номера — для публичных номеров нужен платный)
  textbelt — платный ключ TEXTBELT_KEY. Бесплатный ключ «textbelt» (1 SMS/день, для многих стран
             отключён) используется только обычной проверкой, в умный режим не берётся.

Бесплатных API, которые шлют SMS на любые номера без своего телефона, нет: у пробных тарифов
Twilio/Vonage/Infobip отправка только на номера, подтверждённые владельцем аккаунта.
"""
from __future__ import annotations

import base64
import os
import random
import threading
import time
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


class Route:
    """Один маршрут отправки."""

    name = "base"

    def send(self, to: str, text: str) -> SendResult:
        raise NotImplementedError

    def state(self, msg_id: str) -> str:
        """Статус доставки от шлюза ('' — неизвестно)."""
        return ""


# --- SMS Gateway for Android (SMSGate) -------------------------------------------
class SmsGate(Route):
    def __init__(self, base: str, auth: str, device: str = "", device_name: str = "", sim: Optional[int] = None):
        self.base, self.auth, self.device, self.sim = base, auth, device, sim
        label = device_name or (device[:6] if device else "телефон")
        self.name = f"smsgate:{label}" + (f"/SIM{sim}" if sim else "")

    @staticmethod
    def config():
        base = os.getenv("SMSGATE_URL", "https://api.sms-gate.app/3rdparty/v1").rstrip("/")
        login, pwd, tok = os.getenv("SMSGATE_LOGIN", ""), os.getenv("SMSGATE_PASSWORD", ""), os.getenv("SMSGATE_TOKEN", "")
        if login and pwd:
            return base, "Basic " + base64.b64encode(f"{login}:{pwd}".encode()).decode()
        if tok:
            return base, f"Bearer {tok}"
        return base, ""

    @classmethod
    def routes(cls) -> list["SmsGate"]:
        base, auth = cls.config()
        if not auth:
            return []
        sims = [int(s) for s in os.getenv("SMSGATE_SIMS", "").replace(" ", "").split(",") if s.isdigit()]
        devices: list[tuple[str, str]] = []
        try:  # каждый телефон аккаунта — отдельный маршрут
            r = requests.get(f"{base}/devices", headers={"Authorization": auth}, timeout=TIMEOUT)
            if r.status_code < 300:
                devices = [(d.get("id", ""), d.get("name", "")) for d in r.json() if not d.get("deletedAt")]
        except (requests.RequestException, ValueError):
            pass
        devices = devices or [("", "")]
        return [cls(base, auth, d, n, s) for d, n in devices for s in (sims or [None])]

    def send(self, to, text):
        body = {"phoneNumbers": [to], "textMessage": {"text": text}, "withDeliveryReport": True, "priority": 100}
        if self.device:
            body["deviceId"] = self.device
        if self.sim:
            body["simNumber"] = self.sim
        try:
            r = requests.post(f"{self.base}/messages", json=body, timeout=TIMEOUT,
                              headers={"Authorization": self.auth, "Content-Type": "application/json"})
            d = r.json() if r.content else {}
            ok = r.status_code < 300
            return SendResult(ok, self.name, id=str(d.get("id", "")), error="" if ok else str(d)[:200])
        except (requests.RequestException, ValueError) as e:
            return SendResult(False, self.name, error=str(e))

    def state(self, msg_id):
        try:
            r = requests.get(f"{self.base}/messages/{msg_id}", headers={"Authorization": self.auth}, timeout=TIMEOUT)
            return str(r.json().get("state", "")) if r.status_code < 300 else ""
        except (requests.RequestException, ValueError):
            return ""


class HttpSms(Route):
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


class Telnyx(Route):
    name = "telnyx"

    def __init__(self):
        self.key, self.frm = os.getenv("TELNYX_KEY", ""), os.getenv("TELNYX_FROM", "")

    def configured(self):
        return bool(self.key and self.frm)

    def send(self, to, text):
        try:
            r = requests.post("https://api.telnyx.com/v2/messages", timeout=TIMEOUT,
                              headers={"Authorization": f"Bearer {self.key}"},
                              json={"from": self.frm, "to": to, "text": text})
            d = r.json() if r.content else {}
            ok = r.status_code < 300
            return SendResult(ok, self.name, id=str((d.get("data") or {}).get("id", "")),
                              error="" if ok else str(d.get("errors") or r.text)[:200])
        except (requests.RequestException, ValueError) as e:
            return SendResult(False, self.name, error=str(e))


class Twilio(Route):
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


class TextBelt(Route):
    name = "textbelt"
    blocked = ""  # причина, если бесплатная отправка отклонена (запоминаем, чтобы не повторять)

    def __init__(self):
        self.key = os.getenv("TEXTBELT_KEY", "textbelt")
        self._quota: Optional[int] = None

    def paid(self):
        return self.key != "textbelt"

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


# --- набор маршрутов --------------------------------------------------------------
_routes_cache: tuple[float, list[Route]] = (0.0, [])
_lock = threading.Lock()


def routes(refresh: bool = False) -> list[Route]:
    """Все настроенные маршруты (список телефонов SMSGate кешируется на 5 мин)."""
    global _routes_cache
    with _lock:
        if not refresh and time.time() - _routes_cache[0] < 300:
            return list(_routes_cache[1])
        out: list[Route] = list(SmsGate.routes())
        out += [r for r in (HttpSms(), Telnyx(), Twilio()) if r.configured()]
        tb = TextBelt()
        if tb.paid():
            out.append(tb)
        _routes_cache = (time.time(), out)
        return list(out)


def pick_two() -> tuple[list[Route], str]:
    """Два случайных разных маршрута. Если маршрут один — оба SMS через него (слабее: тот же отправитель)."""
    rs = routes()
    if len(rs) >= 2:
        return random.sample(rs, 2), ""
    if len(rs) == 1:
        return [rs[0], rs[0]], f"настроен один маршрут ({rs[0].name}) — оба SMS идут через него"
    return [], ("нет ни одного шлюза отправки: задайте SMSGATE_LOGIN/SMSGATE_PASSWORD "
                "(приложение SMS Gateway for Android, sms-gate.app) или TELNYX_KEY/TELNYX_FROM")


# --- совместимость с обычной активной проверкой (verify.py) ---------------------
def get_sender() -> Route:
    rs = routes()
    return rs[0] if rs else TextBelt()


def describe() -> dict:
    s = get_sender()
    free = isinstance(s, TextBelt) and not s.paid()
    note = ""
    if free:
        note = ("бесплатный Textbelt отклонил отправку: " + TextBelt.blocked) if TextBelt.blocked else \
            "бесплатный Textbelt: 1 SMS в день и для многих стран отключён"
        note += " — для надёжной проверки подключите SMS Gateway for Android (SMSGATE_LOGIN/PASSWORD) или Telnyx"
    return {"via": s.name, "quota": s.quota() if isinstance(s, TextBelt) else None, "free": free, "note": note,
            "routes": [r.name for r in routes()]}


def send(to: str, text: str) -> SendResult:
    s = get_sender()
    if isinstance(s, TextBelt):
        q = s.quota()
        if q is not None and q <= 0:
            return SendResult(False, s.name, error="квота отправки исчерпана", quota=0)
        if TextBelt.blocked and not s.paid():
            return SendResult(False, s.name, error=TextBelt.blocked)
    return s.send(to, text)
