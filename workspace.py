"""Рабочая папка пользователя, сохранённая история диалога, API-коннекторы и «скриншоты» API.

Всё хранится на диске сервера в DATA_DIR/users/<user_id>/ и переживает сон/перезапуск бота.
"""
import asyncio
import base64
import io
import ipaddress
import json
import os
import re
import shutil
import socket
import textwrap
import time
import zipfile
from urllib.parse import urlencode, urlsplit

import httpx

import binary

DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))

MAX_FILE_BYTES = 2 * 1024 * 1024          # один текстовый файл
MAX_BINARY_BYTES = 20 * 1024 * 1024       # один двоичный файл (.so и т.п.) — предел Telegram для ботов
MAX_WORKSPACE_BYTES = 60 * 1024 * 1024    # все файлы пользователя
MAX_FILES = 200
MAX_ZIP_MEMBERS = 200
MAX_HISTORY_MESSAGES = 60                 # сколько сообщений диалога помним
MAX_HISTORY_CHARS = 200_000
READ_CHUNK = 60_000                       # сколько символов отдаёт read_file за раз
API_TIMEOUT = 30
API_MAX_BODY = 200_000


# ---------- Пути и хранение ----------

def user_dir(uid: int) -> str:
    return os.path.join(DATA_DIR, "users", str(int(uid)))


def files_dir(uid: int) -> str:
    return os.path.join(user_dir(uid), "files")


def _write_json(path: str, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def _read_json(path: str, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def safe_name(name: str) -> str:
    """Относительный путь внутри рабочей папки: без '..', абсолютных путей и странных символов."""
    name = (name or "").replace("\\", "/").strip().strip("/")
    parts = []
    for p in name.split("/"):
        p = re.sub(r"[^\w.\-+@() а-яА-ЯёЁ]", "_", p).strip()
        if p in ("", ".", ".."):
            continue
        parts.append(p[:100])
    if not parts:
        raise ValueError("пустое имя файла")
    return "/".join(parts[-6:])


def _path(uid: int, name: str) -> str:
    base = os.path.realpath(files_dir(uid))
    p = os.path.realpath(os.path.join(base, safe_name(name)))
    if not p.startswith(base + os.sep):
        raise ValueError("недопустимое имя файла")
    return p


def list_files(uid: int):
    base = files_dir(uid)
    out = []
    for root, _, names in os.walk(base):
        for n in names:
            full = os.path.join(root, n)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            st = os.stat(full)
            out.append({"name": rel, "size": st.st_size, "mtime": st.st_mtime})
    return sorted(out, key=lambda x: x["name"])


def workspace_size(uid: int) -> int:
    return sum(f["size"] for f in list_files(uid))


def is_text(data: bytes) -> bool:
    if b"\x00" in data[:8192] and not data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return False
    return True


def decode_text(data: bytes) -> str:
    if data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        try:
            return data.decode("utf-16")
        except UnicodeDecodeError:
            pass
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        pass
    try:
        return data.decode("cp1251")
    except UnicodeDecodeError:
        return data.decode("latin-1", errors="replace")


def save_file(uid: int, name: str, data: bytes) -> str:
    limit = MAX_FILE_BYTES if is_text(data) else MAX_BINARY_BYTES
    if len(data) > limit:
        raise ValueError(f"файл больше {limit // 1024 // 1024} МБ")
    name = safe_name(name)
    p = _path(uid, name)
    existing = os.path.getsize(p) if os.path.exists(p) else 0
    files = list_files(uid)
    if not os.path.exists(p) and len(files) >= MAX_FILES:
        raise ValueError(f"в рабочей папке уже {MAX_FILES} файлов — удали лишние (/files)")
    if sum(f["size"] for f in files) - existing + len(data) > MAX_WORKSPACE_BYTES:
        raise ValueError(f"рабочая папка переполнена ({MAX_WORKSPACE_BYTES // 1024 // 1024} МБ) — "
                         f"удали лишние файлы (/files)")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)
    os.chmod(p, 0o600)
    return name


def read_bytes(uid: int, name: str) -> bytes:
    with open(_path(uid, name), "rb") as f:
        return f.read()


def delete_file(uid: int, name: str):
    os.remove(_path(uid, name))


def delete_all_files(uid: int):
    shutil.rmtree(files_dir(uid), ignore_errors=True)


def extract_zip(uid: int, data: bytes):
    """Распаковывает текстовые файлы из архива. Возвращает (сохранённые, пропущенные)."""
    saved, skipped = [], []
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        members = [m for m in z.infolist() if not m.is_dir()]
        for m in members[:MAX_ZIP_MEMBERS]:
            if m.file_size > MAX_BINARY_BYTES:
                skipped.append(f"{m.filename} (больше 20 МБ)")
                continue
            if any(seg in m.filename for seg in ("__MACOSX/", ".git/", "node_modules/", "__pycache__/")):
                continue
            content = z.read(m)
            try:
                saved.append(save_file(uid, m.filename, content))
            except ValueError as e:
                skipped.append(f"{m.filename} ({e})")
        if len(members) > MAX_ZIP_MEMBERS:
            skipped.append(f"ещё {len(members) - MAX_ZIP_MEMBERS} файлов (лимит {MAX_ZIP_MEMBERS})")
    return saved, skipped


# ---------- История диалога ----------

def load_history(uid: int):
    return _read_json(os.path.join(user_dir(uid), "history.json"), [])


def save_history(uid: int, history: list):
    # обрезаем старое, чтобы история не росла бесконечно
    while len(history) > MAX_HISTORY_MESSAGES:
        del history[:2]
    while sum(len(m.get("content") or "") for m in history) > MAX_HISTORY_CHARS and len(history) > 2:
        del history[:2]
    _write_json(os.path.join(user_dir(uid), "history.json"), history)


def clear_history(uid: int):
    try:
        os.remove(os.path.join(user_dir(uid), "history.json"))
    except FileNotFoundError:
        pass


# ---------- API-коннекторы ----------

def load_connectors(uid: int) -> dict:
    return _read_json(os.path.join(user_dir(uid), "connectors.json"), {})


def save_connectors(uid: int, conns: dict):
    _write_json(os.path.join(user_dir(uid), "connectors.json"), conns)


def parse_api_add(args: list):
    """/api add <имя> <https://адрес> [header:Имя=значение] [query:имя=значение] ...

    Значение заголовка может содержать пробелы (например «Bearer xxx») — всё до следующего
    header:/query: относится к нему."""
    if len(args) < 2:
        raise ValueError("нужно: /api add имя https://адрес [header:Имя=значение] [query:имя=значение]")
    name = re.sub(r"[^\w\-]", "", args[0])[:30]
    if not name:
        raise ValueError("имя коннектора — латиница/цифры, например weather")
    base = args[1].rstrip("/")
    if not base.startswith("https://"):
        raise ValueError("адрес должен начинаться с https://")
    headers, query = {}, {}
    cur = None
    for tok in args[2:]:
        m = re.match(r"^(header|query):([^=]+)=(.*)$", tok)
        if m:
            kind, k, v = m.groups()
            cur = (headers if kind == "header" else query, k.strip())
            cur[0][cur[1]] = v
        elif cur:
            cur[0][cur[1]] += " " + tok
        else:
            raise ValueError(f"не понял «{tok}» — используй header:Имя=значение или query:имя=значение")
    return name, {"base": base, "headers": headers, "query": query, "added": time.strftime("%d.%m.%Y")}


def _is_public_host(host: str) -> bool:
    try:
        infos = socket.getaddrinfo(host, 443)
    except socket.gaierror:
        return False
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
            return False
    return True


def _redact(text: str, secrets: list) -> str:
    for s in secrets:
        if s and len(s) >= 4:
            text = text.replace(s, "•••")
    return text


def _fill_placeholders(uid: int, obj):
    """В теле запроса «{{base64:имя}}» заменяется на base64 файла из рабочей папки,
    «{{file:имя}}» — на его текст. Нужно, например, для записи файлов в GitHub."""
    if isinstance(obj, dict):
        return {k: _fill_placeholders(uid, v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_fill_placeholders(uid, v) for v in obj]
    if isinstance(obj, str):
        m = re.fullmatch(r"\{\{(base64|file):(.+?)\}\}", obj.strip())
        if m:
            data = read_bytes(uid, m.group(2))
            return base64.b64encode(data).decode() if m.group(1) == "base64" else decode_text(data)
    return obj


async def call_api(uid: int, args: dict):
    """Выполняет HTTP-запрос через коннектор (или к публичному https-адресу).
    Возвращает dict для модели и для «скриншота». Секреты коннектора модели не показываются."""
    method = (args.get("method") or "GET").upper()
    if method not in ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"):
        raise ValueError("метод должен быть GET/POST/PUT/PATCH/DELETE/HEAD")
    conn_name = (args.get("connector") or "").strip()
    path = args.get("path") or ""
    headers = {"User-Agent": "NoizozBot/1.0", "Accept": "application/json, */*"}
    query = dict(args.get("query") or {})
    secrets_list = []
    if conn_name:
        conns = load_connectors(uid)
        if conn_name not in conns:
            raise ValueError(f"нет коннектора «{conn_name}». Есть: {', '.join(conns) or 'никаких'}")
        c = conns[conn_name]
        if path.startswith("http"):
            raise ValueError("для коннектора укажи path относительно его адреса, например /users")
        url = c["base"] + ("/" + path.lstrip("/") if path else "")
        headers.update(c.get("headers") or {})
        for k, v in (c.get("query") or {}).items():
            query.setdefault(k, v)
        secrets_list = list((c.get("headers") or {}).values()) + list((c.get("query") or {}).values())
    else:
        url = path or args.get("url") or ""
        if not url.startswith("https://"):
            raise ValueError("без коннектора нужен полный адрес https://…")
    for k, v in (args.get("headers") or {}).items():
        if k.lower() not in ("authorization", "cookie", "host"):
            headers[k] = str(v)
    host = urlsplit(url).hostname or ""
    if not host or not await asyncio.to_thread(_is_public_host, host):
        raise ValueError("этот адрес недоступен (только публичные https-сайты)")
    body = _fill_placeholders(uid, args.get("body"))
    t0 = time.time()
    async with httpx.AsyncClient(timeout=API_TIMEOUT, follow_redirects=False) as client:
        r = await client.request(method, url, params=query or None,
                                 json=body if isinstance(body, (dict, list)) else None,
                                 content=body.encode() if isinstance(body, str) else None,
                                 headers=headers)
    ms = int((time.time() - t0) * 1000)
    raw = r.content[:API_MAX_BODY]
    ctype = r.headers.get("content-type", "")
    is_html = "html" in ctype.lower()
    try:
        if is_html:
            raise ValueError
        parsed = json.loads(raw)
        body_text = json.dumps(parsed, ensure_ascii=False, indent=2)
    except Exception:
        if is_html:
            page = decode_text(raw)
            m = re.search(r"<title[^>]*>(.*?)</title>", page, re.S | re.I)
            title = re.sub(r"\s+", " ", m.group(1)).strip() if m else "—"
            body_text = (f"[Это HTML-страница, а не API: {len(r.content) // 1024} КБ разметки. "
                         f"Заголовок: «{title}». Чтобы показать, как она выглядит, "
                         f"используй web_screenshot.]")
        else:
            body_text = decode_text(raw) if is_text(raw) else f"<двоичные данные, {len(r.content)} байт, {ctype}>"
    shown_url = _redact(url + ("?" + urlencode(query) if query else ""), secrets_list)
    saved_to = None
    if args.get("save_to") and 200 <= r.status_code < 300:
        saved_to = save_file(uid, args["save_to"], r.content)
    return {
        "saved_to": saved_to,
        "method": method, "url": shown_url, "status": r.status_code, "reason": r.reason_phrase,
        "ms": ms, "content_type": ctype, "size": len(r.content),
        "headers": {k: _redact(v, secrets_list) for k, v in r.headers.items()
                    if k.lower() in ("content-type", "content-length", "date", "server",
                                     "x-ratelimit-remaining", "retry-after", "location")},
        "body": _redact(body_text, secrets_list),
        "request_body": _redact(json.dumps(body, ensure_ascii=False, indent=2) if body is not None else "",
                                secrets_list),
    }


# ---------- «Скриншот» запроса к API ----------

_FONT_DIRS = ["/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu", "/usr/share/fonts/TTF"]


def _font(bold=False, size=17):
    from PIL import ImageFont
    name = "DejaVuSansMono-Bold.ttf" if bold else "DejaVuSansMono.ttf"
    for d in _FONT_DIRS:
        p = os.path.join(d, name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default(size=size)


def render_api_screenshot(res: dict, title: str = "") -> bytes:
    """Картинка «как в Postman»: запрос, статус, время, заголовки и тело ответа."""
    from PIL import Image, ImageDraw
    W, pad, lh = 1100, 28, 24
    f, fb, fs = _font(size=17), _font(True, 19), _font(size=15)
    ok = 200 <= res["status"] < 300
    status_col = (80, 200, 120) if ok else (235, 170, 60) if res["status"] < 500 else (235, 90, 90)
    wrap = lambda s, w=95: [ln for raw in s.splitlines() for ln in (textwrap.wrap(raw, w, replace_whitespace=False) or [""])]
    req_lines = wrap(f"{res['method']} {res['url']}", 95)
    rb_lines = wrap(res.get("request_body") or "", 95)[:12]
    hdr_lines = [f"{k}: {v}" for k, v in res["headers"].items()][:8]
    body_lines = wrap(res["body"], 95)
    cut = len(body_lines) > 40
    body_lines = body_lines[:40] + (["… (ответ обрезан)"] if cut else [])
    H = pad * 2 + 46 + lh * (len(req_lines) + 1) + (lh * (len(rb_lines) + 1) if rb_lines else 0) \
        + 50 + lh * (len(hdr_lines) + 1) + lh * (len(body_lines) + 2) + 30
    img = Image.new("RGB", (W, H), (24, 26, 32))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([12, 12, W - 12, H - 12], 16, fill=(32, 35, 43), outline=(55, 60, 72))
    y = pad
    d.text((pad + 8, y), f"API · {title or res['method']}", font=fb, fill=(230, 232, 240))
    d.text((W - pad - 260, y + 2), time.strftime("%d.%m.%Y %H:%M:%S UTC", time.gmtime()), font=fs, fill=(140, 146, 160))
    y += 46
    d.text((pad + 8, y), "ЗАПРОС", font=fs, fill=(140, 146, 160)); y += lh
    for ln in req_lines:
        d.text((pad + 8, y), ln, font=f, fill=(120, 180, 255)); y += lh
    if rb_lines:
        d.text((pad + 8, y), "тело запроса:", font=fs, fill=(140, 146, 160)); y += lh
        for ln in rb_lines:
            d.text((pad + 24, y), ln, font=f, fill=(200, 205, 215)); y += lh
    y += 10
    d.rounded_rectangle([pad, y, W - pad, y + 36], 8, fill=(40, 44, 54))
    d.text((pad + 12, y + 7), f"{res['status']} {res['reason']}", font=fb, fill=status_col)
    d.text((pad + 300, y + 9), f"{res['ms']} мс   ·   {res['size']} байт   ·   {res['content_type'][:40]}",
           font=fs, fill=(170, 176, 190))
    y += 50
    d.text((pad + 8, y), "ЗАГОЛОВКИ ОТВЕТА", font=fs, fill=(140, 146, 160)); y += lh
    for ln in hdr_lines:
        d.text((pad + 8, y), ln[:110], font=fs, fill=(170, 176, 190)); y += lh
    y += lh // 2
    d.text((pad + 8, y), "ТЕЛО ОТВЕТА", font=fs, fill=(140, 146, 160)); y += lh
    for ln in body_lines:
        col = (230, 200, 120) if ln.lstrip().startswith('"') else (215, 220, 230)
        d.text((pad + 8, y), ln, font=f, fill=col); y += lh
    out = io.BytesIO()
    img.save(out, "PNG", optimize=True)
    return out.getvalue()


# ---------- Снимок веб-страницы (как в браузере) ----------

from urllib.parse import quote as _q

def _shot_url(service: str, url: str, full_page: bool, mobile: bool, wait: int) -> str:
    w, h = (390, 844) if mobile else (1280, 845)
    e = _q(url, safe="")
    if service == "microlink":
        dev = "&device=iPhone%2015" if mobile else f"&viewport.width={w}&viewport.height={h}"
        return (f"https://api.microlink.io/?url={e}&screenshot=true&meta=false&embed=screenshot.url"
                f"{dev}&waitForTimeout={max(1, wait) * 1000}" + ("&fullPage=true" if full_page else ""))
    if service == "thum":
        size = f"width/{w}" + ("/fullpage" if full_page else f"/crop/{h}")
        return f"https://image.thum.io/get/{size}/wait/{min(max(wait, 1), 10)}/noanimate/{url}"
    return f"https://s.wordpress.com/mshots/v1/{e}?w={w}&h={h}"      # mshots: только первый экран


def check_shot(img_bytes: bytes, expected_width: int):
    """Быстрая проверка без модели: заглушки, пустые и слишком маленькие картинки.
    Возвращает (ok, причина, ширина, высота)."""
    from PIL import Image, ImageStat
    try:
        im = Image.open(io.BytesIO(img_bytes))
        w, h = im.size
    except Exception:
        return False, "это не картинка", 0, 0
    if w < expected_width * 0.8:
        return False, f"картинка {w}×{h} — похоже на заглушку «генерируется»", w, h
    g = im.convert("L").resize((64, 64))
    if ImageStat.Stat(g).stddev[0] < 4:
        return False, "почти однотонный экран — страница не прогрузилась", w, h
    return True, "", w, h


async def web_screenshot(url: str, full_page=False, mobile=False, wait=3, service="auto"):
    """Возвращает (bytes, сервис, ширина, высота). Пробует сервисы по очереди, отсеивая заглушки."""
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    host = urlsplit(url).hostname or ""
    if not host or not await asyncio.to_thread(_is_public_host, host):
        raise ValueError("этот адрес недоступен (только публичные сайты)")
    if service in ("mshots", "thum", "microlink"):
        order = [service]
    elif full_page or mobile:
        order = ["microlink", "thum"]           # mShots не умеет всю страницу и мобильный вид
    else:
        order = ["mshots", "thum", "microlink"]
    expected = 390 if mobile else 1280
    errors = []
    async with httpx.AsyncClient(timeout=90, follow_redirects=True,
                                 headers={"User-Agent": "Mozilla/5.0"}) as client:
        for svc in order:
            tries = 4 if svc == "mshots" else 1     # mShots сначала отдаёт заглушку, потом готовый снимок
            for attempt in range(tries):
                try:
                    r = await client.get(_shot_url(svc, url, full_page, mobile, wait))
                except httpx.HTTPError as e:
                    errors.append(f"{svc}: {type(e).__name__}")
                    break
                if r.status_code != 200 or not r.headers.get("content-type", "").startswith("image/"):
                    errors.append(f"{svc}: HTTP {r.status_code}")
                    break
                ok, why, w, h = await asyncio.to_thread(check_shot, r.content, expected)
                if ok:
                    return r.content, svc, w, h
                errors.append(f"{svc}: {why}")
                if attempt + 1 < tries:
                    await asyncio.sleep(5)
    raise ValueError("не удалось сделать нормальный снимок: " + "; ".join(errors[-4:]))


def shot_preview_jpeg(img_bytes: bytes, max_side=1400) -> bytes:
    """Уменьшенная копия снимка, чтобы модель могла его «посмотреть»."""
    from PIL import Image
    im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    if im.height > im.width * 3:                      # очень длинная страница — показываем верх
        im = im.crop((0, 0, im.width, im.width * 3))
    im.thumbnail((max_side, max_side))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=80)
    return out.getvalue()


pending_shots = {}     # uid -> {shot_id: {"img", "url", "w", "h"}} — снимки, ждущие проверки моделью


async def send_shot(out, shot: dict, caption: str):
    """Отправляет снимок. Слишком длинные страницы — файлом (Telegram режет фото) + превью верха."""
    w, h = shot["w"], shot["h"]
    if w + h > 9500 or h > w * 15:
        from PIL import Image
        im = Image.open(io.BytesIO(shot["img"])).convert("RGB")
        top = im.crop((0, 0, w, min(h, int(w * 1.6))))
        b = io.BytesIO(); top.save(b, "JPEG", quality=85)
        await out.reply_photo(photo=b.getvalue(), filename="top.jpg", caption=(caption + " · верх страницы")[:1000])
        ext = "png" if shot["img"][:4] == b"\x89PNG" else "jpg"
        await out.reply_document(document=shot["img"], filename=f"page_full.{ext}",
                                 caption="Вся страница целиком (файлом — так не теряется качество)")
    else:
        await out.reply_photo(photo=shot["img"], filename="page.png", caption=caption[:1000])


# ---------- Инструменты для модели ----------

FILE_TOOLS = [
    binary.TOOL,
    {"type": "function", "function": {
        "name": "list_files",
        "description": "Список файлов в рабочей папке пользователя (присланные им и созданные тобой).",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "read_file",
        "description": f"Прочитать текстовый файл из рабочей папки. Отдаёт до {READ_CHUNK} символов, "
                       "для длинных файлов используй offset.",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string", "description": "Имя файла (путь в рабочей папке)"},
            "offset": {"type": "integer", "description": "С какого символа читать (по умолчанию 0)"}},
            "required": ["name"]}}},
    {"type": "function", "function": {
        "name": "write_file",
        "description": "Создать или полностью перезаписать файл в рабочей папке. Пиши ВЕСЬ файл целиком. "
                       "send=true — сразу отправить файл пользователю (без отдельного send_file).",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"}, "content": {"type": "string", "description": "Полное содержимое"},
            "send": {"type": "boolean", "description": "Сразу прислать файл пользователю"},
            "caption": {"type": "string"}},
            "required": ["name", "content"]}}},
    {"type": "function", "function": {
        "name": "edit_file",
        "description": "Точечная правка: заменить фрагмент old на new в файле (old должен встречаться ровно один раз). "
                       "Удобно для небольших изменений больших файлов.",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"},
            "send": {"type": "boolean", "description": "Сразу прислать изменённый файл пользователю"},
            "caption": {"type": "string"}},
            "required": ["name", "old", "new"]}}},
    {"type": "function", "function": {
        "name": "delete_file",
        "description": "Удалить файл из рабочей папки.",
        "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
    {"type": "function", "function": {
        "name": "send_file",
        "description": "Отправить файл из рабочей папки пользователю в чат (как документ). "
                       "Используй, когда пользователь просит файл, или после изменения файла.",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"}, "caption": {"type": "string", "description": "Короткая подпись"}},
            "required": ["name"]}}},
]

API_TOOLS = [
    {"type": "function", "function": {
        "name": "web_screenshot",
        "description": "Сделать НАСТОЯЩИЙ снимок веб-страницы (как в браузере). Снимок НЕ отправляется сразу: "
                       "он будет показан тебе для проверки, потом ты отправишь его через send_screenshot. "
                       "Используй, когда просят фото/скриншот/как выглядит сайт или страница.",
        "parameters": {"type": "object", "properties": {
            "url": {"type": "string", "description": "Адрес страницы"},
            "full_page": {"type": "boolean", "description": "Вся страница целиком (просят «весь», «полностью», «до конца»)"},
            "mobile": {"type": "boolean", "description": "Как на телефоне"},
            "wait": {"type": "integer", "description": "Сколько секунд ждать загрузки страницы (1-10, обычно 3)"},
            "service": {"type": "string", "description": "auto | mshots | thum | microlink — другой сервис при переделке"}},
            "required": ["url"]}}},
    {"type": "function", "function": {
        "name": "send_screenshot",
        "description": "Отправить пользователю проверенный снимок (id из web_screenshot). "
                       "Вызывай ТОЛЬКО если на картинке нормально загруженная страница.",
        "parameters": {"type": "object", "properties": {
            "id": {"type": "string"}, "caption": {"type": "string"}},
            "required": ["id"]}}},
    {"type": "function", "function": {
        "name": "call_api",
        "description": "HTTP-запрос к API. С коннектором пользователя (ключи подставятся сами, ты их не видишь) "
                       "или к публичному https-адресу без коннектора. Возвращает статус, заголовки и тело ответа.",
        "parameters": {"type": "object", "properties": {
            "connector": {"type": "string", "description": "Имя коннектора пользователя (если есть)"},
            "method": {"type": "string", "description": "GET/POST/PUT/PATCH/DELETE"},
            "path": {"type": "string", "description": "Путь от адреса коннектора (/users/1) или полный https-адрес"},
            "query": {"type": "object", "description": "Параметры строки запроса"},
            "body": {"type": "object", "description": "JSON-тело для POST/PUT/PATCH"},
            "headers": {"type": "object", "description": "Доп. заголовки (без авторизации)"},
            "save_to": {"type": "string", "description": "Сохранить тело ответа в файл рабочей папки "
                        "(например, скачать файл из GitHub для правки)"}},
            "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "api_screenshot",
        "description": "То же, что call_api, но присылает картинку с HTTP-запросом и ответом (статус, время, JSON). "
                       "Только для API (JSON). Для сайтов и страниц НЕ используй — для них web_screenshot.",
        "parameters": {"type": "object", "properties": {
            "connector": {"type": "string"}, "method": {"type": "string"}, "path": {"type": "string"},
            "query": {"type": "object"}, "body": {"type": "object"}, "headers": {"type": "object"},
            "title": {"type": "string", "description": "Заголовок картинки"}},
            "required": ["path"]}}},
]


def workspace_note(uid: int) -> str:
    """Короткое описание рабочей папки и коннекторов для системного промпта."""
    files = list_files(uid)
    conns = load_connectors(uid)
    lines = ["\nРАБОЧАЯ ПАПКА И API:",
             "У тебя есть рабочая папка пользователя: читай (read_file), меняй (write_file/edit_file), "
             "создавай файлы и присылай их пользователю (send_file). Когда пользователь просит изменить "
             "скрипт — внеси правку в файл и пришли его через send_file. "
             "Можешь вызывать API (call_api) и присылать скриншоты их работы (api_screenshot), "
             "а также делать настоящие снимки сайтов (web_screenshot) — если просят фото/скрин сайта, используй его. "
             "ПРОВЕРЯЙ СНИМОК: после web_screenshot картинка будет показана тебе. Если на ней нормально "
             "загруженная нужная страница — вызови send_screenshot. Если это заглушка, «загрузка», пустой экран, "
             "капча, ошибка или не та страница — переделай web_screenshot (другой service и/или wait побольше). "
             "Не больше 3 попыток; если не вышло — честно скажи пользователю, что видно на снимке. "
             "Страницы, требующие входа в аккаунт, снять нельзя — снимается то, что видит гость.",
             "В теле call_api строка «{{base64:имя}}» заменяется на base64 файла из рабочей папки, "
             "«{{file:имя}}» — на его текст; save_to сохраняет ответ в файл.",
             "GitHub (если есть коннектор на api.github.com): список репозиториев — GET /user/repos; "
             "прочитать файл — GET /repos/ВЛАДЕЛЕЦ/РЕПО/contents/ПУТЬ с заголовком "
             "Accept: application/vnd.github.raw и save_to=имя (получишь файл в рабочей папке); "
             "изменить файл — сначала GET того же пути без raw, чтобы узнать sha, затем "
             "PUT /repos/ВЛАДЕЛЕЦ/РЕПО/contents/ПУТЬ с телом "
             "{\"message\": \"описание\", \"content\": \"{{base64:имя}}\", \"sha\": \"…\", \"branch\": \"…\"}. "
             "Перед записью в репозиторий кратко скажи пользователю, что именно и в какую ветку меняешь.",
             "ЭКОНОМЬ ШАГИ (у модели лимит запросов в минуту): делай нужные вызовы инструментов сразу "
             "за один шаг; для изменения файла с отправкой используй write_file/edit_file с send=true; "
             "не перечитывай файл, если его содержимое уже есть в этом сообщении или ты только что его записал; "
             "не трогай файлы, о которых не просили.",
             "Двоичные файлы (.so, исполняемые) анализируй через analyze_binary: summary, exports/imports, "
             "functions, strings, sections, disasm (дизассемблер ARM64/ARM/x86). Изменять .so ты не можешь — "
             "только изучать; правки предлагай в исходном коде."]
    if files:
        shown = files[:60]
        lines.append(f"Файлы ({len(files)}): " + ", ".join(f"{f['name']} ({f['size']} Б)" for f in shown)
                     + (" …" if len(files) > 60 else ""))
    else:
        lines.append("Файлов пока нет.")
    if conns:
        lines.append("API-коннекторы пользователя: " + ", ".join(f"{n} → {c['base']}" for n, c in conns.items()))
    else:
        lines.append("API-коннекторов нет (пользователь добавляет их командой /api). "
                     "Публичные https-API можно вызывать без коннектора.")
    return "\n".join(lines)


async def _maybe_send(uid, name, args, out, status) -> str:
    if not args.get("send") or out is None:
        return ""
    await out.reply_document(document=read_bytes(uid, name), filename=os.path.basename(safe_name(name)),
                             caption=(args.get("caption") or "")[:1000] or None)
    status.actions.append(f"📤 Отправил {name}")
    return " Файл отправлен пользователю."


async def run_file_or_api_tool(uid: int, name: str, args: dict, out, status) -> str:
    """Выполняет инструмент. out — сообщение Telegram, в ответ на которое шлём файлы/картинки."""
    from telegram import InputFile  # noqa: F401  (pTB принимает bytes + filename напрямую)
    try:
        if name == "list_files":
            files = list_files(uid)
            return "\n".join(f"{f['name']}\t{f['size']} байт" for f in files) or "Рабочая папка пуста."
        if name == "read_file":
            raw = read_bytes(uid, args["name"])
            if not is_text(raw):
                return (f"{args['name']} — двоичный файл ({binary.kind(raw)}, {len(raw)} байт). "
                        f"Используй analyze_binary.")
            text = decode_text(raw)
            off = max(0, int(args.get("offset") or 0))
            chunk = text[off:off + READ_CHUNK]
            more = f"\n[… ещё {len(text) - off - READ_CHUNK} символов, читай с offset={off + READ_CHUNK}]" \
                if len(text) > off + READ_CHUNK else ""
            return f"Файл {args['name']} ({len(text)} символов):\n{chunk}{more}"
        if name == "write_file":
            saved = save_file(uid, args["name"], (args.get("content") or "").encode("utf-8"))
            status.actions.append(f"✏️ Записал {saved}")
            sent = await _maybe_send(uid, saved, args, out, status)
            return f"Файл {saved} сохранён ({len(args.get('content') or '')} символов).{sent}"
        if name == "edit_file":
            text = decode_text(read_bytes(uid, args["name"]))
            n = text.count(args["old"])
            if n != 1:
                return f"Фрагмент old встречается {n} раз — нужно ровно 1. Уточни фрагмент или используй write_file."
            save_file(uid, args["name"], text.replace(args["old"], args["new"]).encode("utf-8"))
            status.actions.append(f"✏️ Изменил {args['name']}")
            sent = await _maybe_send(uid, args["name"], args, out, status)
            return f"Файл {args['name']} изменён.{sent}"
        if name == "delete_file":
            delete_file(uid, args["name"])
            status.actions.append(f"🗑 Удалил {args['name']}")
            return f"Файл {args['name']} удалён."
        if name == "send_file":
            data = read_bytes(uid, args["name"])
            await out.reply_document(document=data, filename=os.path.basename(safe_name(args["name"])),
                                     caption=(args.get("caption") or "")[:1000] or None)
            status.actions.append(f"📤 Отправил {args['name']}")
            return f"Файл {args['name']} отправлен пользователю."
        if name == "analyze_binary":
            status.actions.append(f"🔬 {args.get('what', 'summary')} {args.get('name', '')}")
            await status.update(force=True)
            data = read_bytes(uid, args["name"])
            return await asyncio.to_thread(
                binary.analyze, data, args["name"], args.get("what") or "summary", args.get("query") or "",
                int(args.get("offset") or 0), args.get("target") or "", int(args.get("count") or 120))
        if name == "web_screenshot":
            status.actions.append(f"📷 Снимок {args.get('url', '')[:60]}")
            await status.update(force=True)
            img, service, w, h = await web_screenshot(
                args.get("url", ""), bool(args.get("full_page")), bool(args.get("mobile")),
                int(args.get("wait") or 3), args.get("service") or "auto")
            shot_id = f"s{len(pending_shots.get(uid, {})) + 1}"
            pending_shots.setdefault(uid, {})[shot_id] = {"img": img, "url": args.get("url"), "w": w, "h": h}
            if getattr(status, "vision", True):
                status.attach_images = getattr(status, "attach_images", []) + [(shot_id, shot_preview_jpeg(img))]
                return (f"Снимок {shot_id} сделан ({service}, {w}×{h}). Он показан тебе ниже — проверь его. "
                        f"Если всё нормально — send_screenshot(id=\"{shot_id}\"), иначе переделай.")
            # модель без зрения — отправляем сразу (быстрая проверка заглушек уже пройдена)
            await send_shot(out, pending_shots[uid].pop(shot_id), args.get("url", ""))
            status.actions.append("📸 Отправил снимок сайта")
            return f"Снимок {args.get('url')} отправлен пользователю ({service}, {w}×{h})."
        if name == "send_screenshot":
            shot = pending_shots.get(uid, {}).pop(args.get("id", ""), None)
            if not shot:
                return "Такого снимка нет — сделай web_screenshot заново."
            await send_shot(out, shot, args.get("caption") or shot["url"])
            status.actions.append("📸 Отправил снимок сайта")
            return "Снимок отправлен пользователю."
        if name in ("call_api", "api_screenshot"):
            status.actions.append(f"🔌 {(args.get('method') or 'GET').upper()} "
                                  f"{(args.get('connector') or '')} {args.get('path', '')}"[:90])
            await status.update(force=True)
            res = await call_api(uid, args)
            if name == "api_screenshot":
                png = await asyncio.to_thread(render_api_screenshot, res, args.get("title") or args.get("connector") or "")
                await out.reply_photo(photo=png, filename="api.png",
                                      caption=f"🔌 {res['method']} → {res['status']} {res['reason']} · {res['ms']} мс")
                status.actions.append("📸 Отправил скриншот")
            body = res["body"]
            if len(body) > 30_000:
                body = body[:30_000] + f"\n[… тело обрезано, всего {len(res['body'])} символов]"
            head = f"{res['method']} {res['url']}\nСтатус: {res['status']} {res['reason']} · {res['ms']} мс · {res['size']} байт\n"
            head += "".join(f"{k}: {v}\n" for k, v in res["headers"].items())
            if res.get("saved_to"):
                status.actions.append(f"💾 Сохранил ответ в {res['saved_to']}")
                head += f"[Тело ответа сохранено в файл {res['saved_to']}]\n"
            return head + "\n" + body + ("\n[скриншот отправлен пользователю]" if name == "api_screenshot" else "")
    except FileNotFoundError:
        return f"Файла {args.get('name')} нет. Посмотри list_files."
    except (ValueError, KeyError) as e:
        return f"Ошибка: {e}"
    except httpx.HTTPError as e:
        return f"Ошибка сети при запросе к API: {type(e).__name__}: {e}"
    return "Неизвестный инструмент."
