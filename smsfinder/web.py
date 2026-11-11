"""Веб-интерфейс: python -m smsfinder.web  (порт из $PORT, по умолчанию 8000).

Если задан SMSFINDER_TOKEN, доступ только по ссылке ?t=<токен>.
"""
from __future__ import annotations

import os
from functools import wraps
from pathlib import Path

from flask import Flask, abort, jsonify, request

from .core import ProviderError
from .engine import Engine
from .services import SERVICES

app = Flask(__name__)
engine = Engine()
TOKEN = os.getenv("SMSFINDER_TOKEN", "")
INDEX = (Path(__file__).parent / "static" / "index.html").read_text(encoding="utf-8")


def guarded(f):
    @wraps(f)
    def w(*a, **kw):
        if TOKEN and request.args.get("t") != TOKEN and request.headers.get("X-Token") != TOKEN:
            abort(403)
        return f(*a, **kw)
    return w


@app.get("/")
@guarded
def index():
    return INDEX


@app.get("/api/meta")
@guarded
def meta():
    return jsonify(providers=engine.provider_info(), services=list(SERVICES))


@app.get("/api/find")
@guarded
def find():
    provs = [p for p in request.args.get("providers", "").split(",") if p] or None
    return jsonify(engine.find(request.args.get("service", "other"), request.args.get("country", ""),
                               provs, int(request.args.get("limit", 15))))


@app.post("/api/start")
@guarded
def start():
    d = request.get_json(force=True)
    try:
        act = engine.start(d["id"], d.get("service", "other"), int(d.get("timeout", 600)))
    except ProviderError as e:
        return jsonify(error=str(e)), 400
    return jsonify(act.to_dict())


@app.post("/api/auto")
@guarded
def auto():
    d = request.get_json(force=True)
    try:
        act = engine.auto(d.get("service", "other"), d.get("country", ""), d.get("providers") or None,
                          int(d.get("timeout", 600)))
    except ProviderError as e:
        return jsonify(error=str(e)), 400
    return jsonify(act.to_dict())


@app.get("/api/activation/<aid>")
@guarded
def activation(aid):
    act = engine.activations.get(aid) or abort(404)
    return jsonify(act.to_dict())


@app.post("/api/activation/<aid>/cancel")
@guarded
def cancel(aid):
    act = engine.activations.get(aid) or abort(404)
    act.cancel()
    return jsonify(act.to_dict())


@app.get("/healthz")
def health():
    return "ok"


def main():
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", 8000)), threaded=True)


if __name__ == "__main__":
    main()
