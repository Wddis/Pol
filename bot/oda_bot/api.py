"""HTTP-сервер ключей для приложения ODA (тот же процесс, что и бот).

POST /v1/activate  {"key","device_id","device_name"} — ввод ключа: первая активация запускает срок,
                   устройство занимает место; ответ — подписанная лицензия.
POST /v1/check     {"key","device_id"} — проверка раз в сутки и при запуске; ответ — свежая лицензия.
GET  /v1/pubkey    — открытый ключ подписи (он же вшит в приложение).
GET  /health       — жив ли сервер.
Ошибки: {"status": "<код>", "message": "<текст для экрана приложения>"}.
"""
import re
import time
from collections import defaultdict, deque

from aiohttp import web

from .db import DAY, Db, Key, Result
from .keys import normalize
from .license import Signer

DEVICE_ID = re.compile(r"^[A-Za-z0-9_\-]{8,128}$")
# Неудачных вводов ключа с одного адреса — не больше стольких за окно: перебор ключей бессмыслен
FAIL_LIMIT = 10
FAIL_WINDOW = 600

MESSAGES = {
    Result.NOT_FOUND: (404, "Ключ не найден. Проверьте буквы и цифры"),
    Result.REVOKED: (403, "Ключ отключён. Напишите в поддержку бота"),
    Result.EXPIRED: (410, "Тариф закончился. Продлите ключ в Telegram-боте или введите новый"),
    Result.DEVICE_LIMIT: (409, "На этом ключе уже все устройства тарифа. Освободите место: бот → Профиль → «Сменить устройство»"),
    Result.DEVICE_REMOVED: (401, "Это устройство отвязано от ключа. Введите ключ заново"),
}


class KeyApi:
    def __init__(self, db: Db, signer: Signer):
        self.db = db
        self.signer = signer
        self.fails: dict[str, deque] = defaultdict(deque)

    def app(self) -> web.Application:
        app = web.Application(client_max_size=4096)
        app.router.add_post("/v1/activate", self.activate)
        app.router.add_post("/v1/check", self.check)
        app.router.add_get("/v1/pubkey", self.pubkey)
        app.router.add_get("/health", self.health)
        return app

    async def health(self, request: web.Request) -> web.Response:
        return web.json_response({"status": "ok", "time": int(time.time())})

    async def pubkey(self, request: web.Request) -> web.Response:
        return web.json_response({"alg": "Ed25519", "key": self.signer.public_b64()})

    async def activate(self, request: web.Request) -> web.Response:
        return await self._handle(request, activate=True)

    async def check(self, request: web.Request) -> web.Response:
        return await self._handle(request, activate=False)

    async def _handle(self, request: web.Request, activate: bool) -> web.Response:
        ip = request.remote or "?"
        now = int(time.time())
        if self._too_many(ip, now):
            return _error(429, "too_many", "Слишком много попыток. Подождите 10 минут")
        try:
            body = await request.json()
        except ValueError:
            return _error(400, "bad_request", "Неверный запрос")
        key = normalize(str(body.get("key", "")))
        device_id = str(body.get("device_id", ""))
        name = str(body.get("device_name", ""))[:64]
        if not DEVICE_ID.match(device_id):
            return _error(400, "bad_request", "Неверный запрос")
        if key is None:
            self._fail(ip, now)
            status, message = MESSAGES[Result.NOT_FOUND]
            return _error(status, Result.NOT_FOUND, message)
        if activate:
            result, k = self.db.activate(key, device_id, name, now)
        else:
            result, k = self.db.check(key, device_id, now)
        if result != Result.OK:
            if result == Result.NOT_FOUND:
                self._fail(ip, now)
            status, message = MESSAGES[result]
            return _error(status, result, message, k)
        return web.json_response(self._license(k, device_id, now))

    def _license(self, k: Key, device_id: str, now: int) -> dict:
        token = self.signer.sign({
            "v": 1, "key": k.key, "dev": device_id, "tariff": k.tariff, "devices": k.devices_max,
            "exp": k.expires_at, "iat": now,
        })
        return {
            "status": Result.OK, "license": token, "key": k.key, "tariff": k.tariff,
            "tariff_title": k.tariff_title, "devices_max": k.devices_max,
            "devices_used": len(self.db.devices(k.key)), "activated_at": k.activated_at,
            "expires_at": k.expires_at, "days_left": k.seconds_left(now) // DAY, "server_time": now,
        }

    def _too_many(self, ip: str, now: int) -> bool:
        q = self.fails[ip]
        while q and q[0] <= now - FAIL_WINDOW:
            q.popleft()
        return len(q) >= FAIL_LIMIT

    def _fail(self, ip: str, now: int) -> None:
        self.fails[ip].append(now)


def _error(status: int, code: str, message: str, k: Key | None = None) -> web.Response:
    body = {"status": code, "message": message}
    if k is not None and code == Result.EXPIRED:
        body["expires_at"] = k.expires_at
    return web.json_response(body, status=status)
