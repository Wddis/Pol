"""Fail-closed application gate for a named Cloudflare Access tunnel."""
import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import time

import jwt
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from starlette.concurrency import run_in_threadpool

COOKIE = "__Host-t2x2-session"
LOGIN = """<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Вход · Двойная резка</title><style>
body{color-scheme:dark;background:#0d1118;color:#edf1f9;font:18px system-ui;margin:24px}
main{max-width:380px;margin:12vh auto}input,button{display:block;margin:12px 0;
font:inherit;padding:12px;border-radius:10px;width:100%;box-sizing:border-box}
</style><main><h1>Двойная резка</h1><form method="post" action="/unlock">
<label>Код доступа<input name="code" type="password" inputmode="numeric" maxlength="32"
autocomplete="off" required></label><button>Войти</button></form></main></html>"""


class AccessGate:
    def __init__(self, env=os.environ):
        self.tunnel = env.get("T2X2_NGROK") == "1"
        cloudflare = env.get("T2X2_PUBLIC") == "1"
        if self.tunnel and cloudflare:
            raise RuntimeError("Выбери только один режим внешнего доступа.")
        self.enabled = self.tunnel or cloudflare
        if not self.enabled:
            return
        self.host = env.get("T2X2_PUBLIC_HOST", "").lower()
        if self.tunnel:
            password = env.get("T2X2_NGROK_PASSWORD", "")
            if not re.fullmatch(r"[a-z0-9][a-z0-9-]*\.ngrok-free\.(?:app|dev)", self.host) or len(password) < 20:
                raise RuntimeError("Для ngrok нужны точный бесплатный домен и пароль от 20 символов.")
            self.expected = hashlib.sha256(("t2x2:"+password).encode()).digest()
            return
        team = env.get("T2X2_ACCESS_TEAM", "")
        self.audience = env.get("T2X2_ACCESS_AUD", "")
        self.pin = env.get("T2X2_ACCESS_PIN", "")
        self.key = env.get("T2X2_SESSION_KEY", "").encode()
        if (not re.fullmatch(r"[a-z0-9-]+\.cloudflareaccess\.com", team)
                or not re.fullmatch(r"[a-z0-9.-]+\.[a-z]{2,}", self.host)
                or not self.audience or not self.pin or len(self.key) < 32):
            raise RuntimeError("Не заданы параметры защищённого доступа T2X2.")
        self.issuer = "https://" + team
        self.jwks = jwt.PyJWKClient(self.issuer + "/cdn-cgi/access/certs", timeout=5)
        self.lock = threading.Lock()
        self.attempts = {}

    def identity(self, token):
        if not token or len(token) > 16000:
            return None
        try:
            key = self.jwks.get_signing_key_from_jwt(token).key
            claims = jwt.decode(token, key, algorithms=["RS256"], audience=self.audience,
                                issuer=self.issuer, options={"require":["exp","sub","aud","iss"]})
            return claims["sub"] if claims.get("email") and isinstance(claims["sub"],str) else None
        except (jwt.PyJWTError, OSError, ValueError):
            return None

    def signature(self, subject, payload):
        return hmac.new(self.key, json.dumps([subject,payload]).encode(), hashlib.sha256).hexdigest()

    def valid_cookie(self, subject, value):
        try:
            expiry, nonce, mac = value.split(".", 2)
            return int(expiry) > time.time() and hmac.compare_digest(mac,self.signature(subject,expiry+"."+nonce))
        except ValueError:
            return False

    def allow_attempt(self, subject):
        now=time.monotonic()
        with self.lock:
            self.attempts={name:[x for x in entries if now-x<900]
                           for name,entries in self.attempts.items() if entries and now-entries[-1]<900}
            attempts=self.attempts.setdefault(subject,[])
            if len(attempts)>=5:
                return False
            attempts.append(now)
            return True

    async def __call__(self, request, call_next):
        from urllib.parse import parse_qs
        if not self.enabled:
            if request.url.hostname not in ("localhost","127.0.0.1","::1"):
                return JSONResponse({"detail":"Локальный режим."}, status_code=403)
            return await call_next(request)
        if request.url.hostname != self.host:
            return JSONResponse({"detail":"Неизвестный адрес панели."}, status_code=403)
        if self.tunnel:
            scheme, _, encoded = request.headers.get("authorization","").partition(" ")
            try:
                if scheme.lower()!="basic" or len(encoded)>4096:
                    raise ValueError
                supplied = hashlib.sha256(base64.b64decode(encoded,validate=True)).digest()
            except (ValueError,binascii.Error):
                supplied = b""
            if not hmac.compare_digest(self.expected,supplied):
                return JSONResponse({"detail":"Введи логин и пароль монтажки."},status_code=401,
                                    headers={"WWW-Authenticate":'Basic realm="T2x2", charset="UTF-8"',
                                             "Cache-Control":"no-store"})
            if request.method not in ("GET","HEAD") and request.headers.get("origin") != "https://" + self.host:
                return JSONResponse({"detail":"Недопустимый источник запроса."},status_code=403)
            if re.fullmatch(r"/api/jobs/[^/]+/(file|short)/[^/]+",request.url.path):
                return JSONResponse({"detail":"Видео выдаются отдельно, чтобы сохранить лимит ngrok."},
                                    status_code=403,headers={"Cache-Control":"no-store"})
            response=await call_next(request)
            response.headers["Cache-Control"]="private, no-store"
            return response
        subject=await run_in_threadpool(self.identity, request.headers.get("cf-access-jwt-assertion"))
        if not subject:
            return JSONResponse({"detail":"Требуется Cloudflare Access."}, status_code=401,
                                headers={"Cache-Control":"no-store"})
        if request.method != "GET" and request.headers.get("origin") != "https://" + self.host:
            return JSONResponse({"detail":"Недопустимый источник запроса."}, status_code=403)
        if request.url.path == "/unlock" and request.method == "POST":
            declared=request.headers.get("content-length","")
            if not declared.isdigit() or int(declared)>256:
                return HTMLResponse(LOGIN,status_code=400,headers={"Cache-Control":"no-store"})
            if not self.allow_attempt(subject):
                return HTMLResponse("Слишком много попыток. Повтори через 15 минут.",status_code=429,
                                    headers={"Cache-Control":"no-store"})
            values=parse_qs((await request.body()).decode("utf-8", errors="replace"))
            if not hmac.compare_digest(values.get("code",[""])[0].encode(), self.pin.encode()):
                return HTMLResponse(LOGIN+"<p>Неверный код.</p>",status_code=403,
                                    headers={"Cache-Control":"no-store"})
            payload=str(int(time.time())+3600)+"."+secrets.token_hex(16)
            response=RedirectResponse("/",status_code=303)
            response.set_cookie(COOKIE,payload+"."+self.signature(subject,payload),max_age=3600,
                                secure=True,httponly=True,samesite="strict",path="/")
        elif not self.valid_cookie(subject,request.cookies.get(COOKIE,"")):
            response=(HTMLResponse(LOGIN) if request.url.path=="/" and request.method=="GET"
                      else JSONResponse({"detail":"Введи код доступа."},status_code=401))
        else:
            response=await call_next(request)
        response.headers["Cache-Control"]="private, no-store"
        response.headers["X-Content-Type-Options"]="nosniff"
        return response
