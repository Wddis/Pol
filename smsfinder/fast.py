"""Быстрый сетевой слой и быстрый разбор HTML.

Замеры (см. README, раздел «Скорость») показали, что подбор тратил время на три вещи:

1. **TLS-рукопожатия.** У requests пул на 10 соединений на сайт, а проверка шла в 12 потоков:
   лишние соединения закрывались и открывались заново (первый запрос 0,42 с, повторный 0,10 с).
   Здесь один общий на процесс клиент httpx с HTTP/2 (github.com/encode/httpx): все запросы
   к сайту идут параллельными потоками по одному TLS-соединению, и оно переживает подборы.
2. **«Хвост» задержек.** Из 40 запросов к одному сайту медиана 0,34 с, но изредка один висит
   4,8 с — и весь подбор ждёт его. Хеджированные запросы по схеме github.com/bhope/hedge
   (Google, «The Tail at Scale»): если ответа нет дольше p90 задержек этого сайта (не меньше
   MIN_HEDGE), отправляется дубль по отдельному соединению HTTP/1.1, берётся первый ответ.
   Дублей не больше ~15% от числа запросов, чтобы не нагружать сайты.
3. **Разбор HTML.** BeautifulSoup + html.parser — чистый Python под GIL: 40 мс на страницу
   asms.ai, 17 МБ HTML за подбор. selectolax/Lexbor (github.com/rushter/selectolax) — 3 мс.
   Soup ниже — тонкий адаптер с API BeautifulSoup, чтобы логика провайдеров не менялась.

Если httpx/selectolax не установлены, используются requests/BeautifulSoup — медленнее, но работает.
"""
from __future__ import annotations

import collections
import re
import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from urllib.parse import urlsplit

try:
    import httpx
except ImportError:  # pragma: no cover
    httpx = None
try:
    from selectolax.lexbor import LexborHTMLParser
except ImportError:  # pragma: no cover
    LexborHTMLParser = None

MIN_HEDGE = 0.7       # раньше этого дубль не отправляем, с
WARMUP_HEDGE = 0.9    # порог, пока по сайту мало замеров
HEDGE_BUDGET = 0.15   # доля дублей от числа запросов
_lock = threading.Lock()
_clients: dict = {}
_pool = ThreadPoolExecutor(max_workers=256, thread_name_prefix="smsfinder-http")
_lat: dict[str, collections.deque] = collections.defaultdict(lambda: collections.deque(maxlen=64))
stats = {"requests": 0, "hedged": 0, "hedge_won": 0}


def _client(kind: str, timeout: float):
    key = (kind, timeout)
    with _lock:
        c = _clients.get(key)
        if c is None:
            limits = httpx.Limits(max_connections=200, max_keepalive_connections=100, keepalive_expiry=120)
            c = _clients[key] = httpx.Client(http2=(kind == "h2"), limits=limits,
                                             timeout=httpx.Timeout(timeout, connect=min(4.0, timeout)))
        return c


def _hedge_delay(host: str) -> float:
    d = _lat[host]
    if len(d) < 8:
        return WARMUP_HEDGE
    return max(MIN_HEDGE, sorted(d)[int(len(d) * 0.9)] * 1.5)


class Http:
    """Совместим с тем подмножеством requests.Session, которое используют провайдеры:
    .headers, .get(url, params=, headers=, timeout=, allow_redirects=), .post(...)."""

    def __init__(self, headers: dict | None = None):
        self.headers = dict(headers or {})
        self._fallback = None
        if httpx is None:
            import requests
            self._fallback = requests.Session()
            self._fallback.headers.update(self.headers)

    def _send(self, kind, method, url, params, headers, timeout, allow_redirects, **kw):
        h = {**self.headers, **(headers or {})}
        return _client(kind, float(timeout)).request(method, url, params=params, headers=h,
                                                     follow_redirects=allow_redirects, **kw)

    def get(self, url, params=None, headers=None, timeout=12, allow_redirects=True):
        if self._fallback is not None:
            self._fallback.headers.update(self.headers)
            return self._fallback.get(url, params=params, headers=headers, timeout=timeout,
                                      allow_redirects=allow_redirects)
        host = urlsplit(url).netloc
        stats["requests"] += 1
        t0 = time.perf_counter()
        args = ("GET", url, params, headers, timeout, allow_redirects)
        first = _pool.submit(self._send, "h2", *args)
        done, _ = wait([first], timeout=_hedge_delay(host))
        futs = [first]
        if not done and stats["hedged"] < 3 + HEDGE_BUDGET * stats["requests"]:
            stats["hedged"] += 1
            futs.append(_pool.submit(self._send, "h1", *args))
        err = None
        pending = set(futs)
        while pending:
            done, pending = wait(pending, return_when=FIRST_COMPLETED)
            for f in done:
                try:
                    r = f.result()
                except Exception as e:  # ждём второй попытки, если она есть
                    err = e
                    continue
                if f is not first:
                    stats["hedge_won"] += 1
                _lat[host].append(time.perf_counter() - t0)
                return r
        raise err

    def post(self, url, params=None, headers=None, timeout=12, allow_redirects=True, **kw):
        if self._fallback is not None:
            return self._fallback.post(url, params=params, headers={**self.headers, **(headers or {})},
                                       timeout=timeout, allow_redirects=allow_redirects, **kw)
        return self._send("h2", "POST", url, params, headers, timeout, allow_redirects, **kw)


# --- разбор HTML ---------------------------------------------------------------

class Node:
    """Узел selectolax с методами BeautifulSoup, которые используют провайдеры."""

    __slots__ = ("n",)

    def __init__(self, n):
        self.n = n

    def select(self, css):
        return [Node(x) for x in self.n.css(css)]

    def select_one(self, css):
        x = self.n.css_first(css)
        return Node(x) if x is not None else None

    def find_all(self, tag):
        return self.select(tag)

    def find(self, tag=None, string=None):
        if string is not None:
            for t in self.n.traverse(include_text=True):
                if t.tag == "-text" and string.search(t.text_content or ""):
                    return t.text_content
            return None
        return self.select_one(tag)

    def find_parent(self, tag, class_=None):
        p = self.n.parent
        while p is not None:
            if p.tag == tag:
                if class_ is None:
                    return Node(p)
                cls = p.attributes.get("class") or ""
                if (class_.search(cls) if hasattr(class_, "search")
                        else class_ in cls.split()):
                    return Node(p)
            p = p.parent
        return None

    def get(self, attr, default=None):
        v = self.n.attributes.get(attr, default)
        return default if v is None else v

    def get_text(self, separator="", strip=False):
        return self.n.text(deep=True, separator=separator, strip=strip)


def Soup(html: str):
    """BeautifulSoup(html, 'html.parser') — но в 13–30 раз быстрее (selectolax/Lexbor)."""
    if LexborHTMLParser is None:  # pragma: no cover
        from bs4 import BeautifulSoup
        return BeautifulSoup(html, "html.parser")
    tree = LexborHTMLParser(html)
    return Node(tree.root if tree.root is not None else tree.body)


__all__ = ["Http", "Soup", "Node", "stats", "re"]
