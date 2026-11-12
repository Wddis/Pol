"""Веб-интерфейс: python -m smsfinder.web  (порт из $PORT, по умолчанию 8000).

Если задан SMSFINDER_TOKEN, доступ только по ссылке ?t=<токен>.
"""
from __future__ import annotations

import os
from functools import wraps
from pathlib import Path

import json

from flask import Flask, Response, abort, jsonify, request, stream_with_context

from .core import ProviderError
from . import sender
from .engine import MODES, Engine
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
    return jsonify(providers=engine.provider_info(), services=list(SERVICES), modes=MODES,
                   sender=sender.describe())


def _find_args():
    a = request.args
    return dict(service=a.get("service", "other"), country=a.get("country", ""),
                providers=[p for p in a.get("providers", "").split(",") if p] or None,
                limit=int(a.get("limit", 15)), mode=a.get("mode", "niche"),
                probe_s=max(0, min(int(a.get("probe", 20)), 120)),
                verify_n=max(0, min(int(a.get("verify", 0)), 5)),
                verify_wait=max(30, min(int(a.get("vwait", 180)), 600)),
                verify_method=a.get("vmethod", "auto"))


@app.get("/api/find")
@guarded
def find():
    return jsonify(engine.find(**_find_args()))


@app.get("/api/find/stream")
@guarded
def find_stream():
    """Server-Sent Events: проверка номеров в реальном времени."""
    args = _find_args()

    def gen():
        for ev in engine.find_stream(**args):
            yield f"data: {json.dumps(ev, ensure_ascii=False)}\n\n"

    return Response(stream_with_context(gen()), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


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
                          int(d.get("timeout", 600)), d.get("mode", "niche"), int(d.get("probe", 20)),
                          verify_n=min(int(d.get("verify", 0)), 5))
    except ProviderError as e:
        return jsonify(error=str(e)), 400
    return jsonify(act.to_dict())


@app.post("/api/verify")
@guarded
def verify_start():
    d = request.get_json(force=True)
    try:
        job = engine.verify(d["id"], max(30, min(int(d.get("wait", 180)), 900)), d.get("method", "auto"),
                            d.get("service", "other"))
    except ProviderError as e:
        return jsonify(error=str(e)), 400
    return jsonify(job.to_dict())


@app.get("/api/verify/<vid>")
@guarded
def verify_get(vid):
    job = engine.verifications.get(vid) or abort(404)
    return jsonify(job.to_dict())


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


@app.post("/api/activation/<aid>/feedback")
@guarded
def feedback(aid):
    act = engine.activations.get(aid) or abort(404)
    d = request.get_json(force=True)
    act.feedback(bool(d.get("ok")), d.get("reason", ""), bool(d.get("switch", True)))
    return jsonify(act.to_dict())


@app.post("/api/activation/<aid>/switch")
@guarded
def switch(aid):
    act = engine.activations.get(aid) or abort(404)
    act.switch()
    return jsonify(act.to_dict())


@app.get("/api/verified")
@guarded
def verified_pool():
    """Пул «проверенных» номеров + состояние фонового наблюдения."""
    from . import registry
    a = request.args
    return jsonify(pool=engine.watcher.pool(a.get("service", "other"), a.get("country", "")),
                   watcher=engine.watcher.status(), stats=registry.stats())


@app.get("/healthz")
def health():
    return "ok"


def main():
    if os.getenv("SMSFINDER_WATCH", "1") != "0":
        engine.watcher.start()  # фоновый пул проверенных номеров
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", 8000)), threaded=True)


if __name__ == "__main__":
    main()
