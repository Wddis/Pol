"""Большие файлы (до 2 ГБ): скачивание по ссылке потоком, архивы без распаковки целиком,
побайтовый просмотр и построчное чтение. Память не зависит от размера файла.

Telegram разрешает боту скачивать из чата только файлы до 20 МБ — это ограничение платформы.
Большие проекты пользователь присылает ссылкой (Google Drive, Dropbox, GitHub Release, прямая ссылка).
"""
import asyncio
import fnmatch
import os
import re
import shutil
import tarfile
import time
import zipfile
from urllib.parse import urlsplit, parse_qs

import httpx

import workspace as ws

MAX_BIG_FILE = 2 * 1024 * 1024 * 1024 + 64 * 1024 * 1024     # 2 ГБ (+запас)
TG_SEND_LIMIT = 50 * 1024 * 1024                               # бот может отправить файл до 50 МБ
STREAM_LIMIT = 32 * 1024 * 1024                                # больше — читаем потоково
ARCHIVE_EXT = (".zip", ".apk", ".aab", ".jar", ".aar", ".ipa", ".obb", ".xapk", ".apks",
               ".tar", ".tgz", ".tar.gz", ".tar.bz2", ".tar.xz", ".unitypackage")


def free_disk() -> int:
    os.makedirs(ws.DATA_DIR, exist_ok=True)
    return shutil.disk_usage(ws.DATA_DIR).free


def head(uid: int, name: str, n: int = 8192) -> bytes:
    with open(ws._path(uid, name), "rb") as f:
        return f.read(n)


def size_of(uid: int, name: str) -> int:
    return os.path.getsize(ws._path(uid, name))


def human(n: int) -> str:
    for unit in ("Б", "КБ", "МБ", "ГБ"):
        if n < 1024 or unit == "ГБ":
            return f"{n:.0f} {unit}" if unit == "Б" else f"{n:.1f} {unit}"
        n /= 1024


# ---------- Скачивание по ссылке ----------

def direct_link(url: str) -> str:
    """Превращает ссылки «для просмотра» в прямые ссылки на скачивание."""
    u = urlsplit(url)
    host = (u.hostname or "").lower()
    if "drive.google.com" in host:
        m = re.search(r"/d/([\w-]+)", u.path) or re.search(r"id=([\w-]+)", u.query)
        if m:
            return f"https://drive.usercontent.google.com/download?id={m.group(1)}&export=download&confirm=t"
    if "dropbox.com" in host:
        return re.sub(r"([?&])dl=0", r"\1dl=1", url) if "dl=0" in url else url + ("&" if "?" in url else "?") + "dl=1"
    if host == "github.com" and "/blob/" in u.path:
        return url.replace("github.com", "raw.githubusercontent.com", 1).replace("/blob/", "/", 1)
    return url


def _name_from(url: str, resp) -> str:
    cd = resp.headers.get("content-disposition", "")
    m = re.search(r"filename\*=UTF-8''([^;]+)", cd) or re.search(r'filename="?([^";]+)"?', cd)
    if m:
        from urllib.parse import unquote
        return unquote(m.group(1)).strip()
    tail = os.path.basename(urlsplit(url).path)
    return tail or "download.bin"


async def download_url(uid: int, url: str, name: str = "", status=None) -> str:
    if not url.startswith(("http://", "https://")):
        raise ValueError("нужна ссылка http(s)://")
    host = urlsplit(url).hostname or ""
    if not await asyncio.to_thread(ws._is_public_host, host):
        raise ValueError("этот адрес недоступен (только публичные сайты)")
    src = direct_link(url)
    async with httpx.AsyncClient(timeout=httpx.Timeout(60, read=300), follow_redirects=True,
                                 headers={"User-Agent": "Mozilla/5.0"}) as c:
        async with c.stream("GET", src) as r:
            if r.status_code != 200:
                raise ValueError(f"сервер ответил {r.status_code}")
            ctype = r.headers.get("content-type", "")
            total = int(r.headers.get("content-length") or 0)
            if "text/html" in ctype and "drive.usercontent.google.com" not in src and total < 2_000_000:
                body = (await r.aread())[:400].decode("utf-8", "replace")
                if "<html" in body.lower():
                    raise ValueError("по ссылке открылась веб-страница, а не файл. Нужна прямая ссылка на "
                                     "скачивание (в Google Drive — «Доступ: все, у кого есть ссылка»)")
            if total > MAX_BIG_FILE:
                raise ValueError(f"файл {human(total)} — больше 2 ГБ")
            if total and total > free_disk() - 200 * 1024 * 1024:
                raise ValueError(f"на сервере мало места: свободно {human(free_disk())}")
            fname = ws.safe_name(name or _name_from(src, r))
            path = ws._path(uid, fname)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tmp = path + ".part"
            got, last = 0, 0.0
            try:
                with open(tmp, "wb") as f:
                    async for chunk in r.aiter_bytes(1024 * 1024):
                        got += len(chunk)
                        if got > MAX_BIG_FILE:
                            raise ValueError("файл больше 2 ГБ — скачивание остановлено")
                        f.write(chunk)
                        if status is not None and time.time() - last > 3:
                            last = time.time()
                            pct = f" ({got * 100 // total}%)" if total else ""
                            status.stage = f"⬇️ Скачиваю {fname}: {human(got)}{pct}"
                            await status.update(force=True)
                os.replace(tmp, path)
                os.chmod(path, 0o600)
            finally:
                if os.path.exists(tmp):
                    os.remove(tmp)
    return fname, got


# ---------- Архивы ----------

def _is_tar(name: str) -> bool:
    n = name.lower()
    return n.endswith((".tar", ".tgz", ".tar.gz", ".tar.bz2", ".tar.xz", ".unitypackage"))


def archive_list(uid: int, name: str, pattern: str = "*", offset: int = 0, limit: int = 300) -> str:
    path = ws._path(uid, name)
    rows = []
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for m in z.infolist():
                if not m.is_dir():
                    rows.append((m.filename, m.file_size, m.compress_size))
    elif _is_tar(name) or tarfile.is_tarfile(path):
        with tarfile.open(path, "r:*") as t:
            for m in t:
                if m.isfile():
                    rows.append((m.name, m.size, None))
    else:
        return f"{name} — не архив zip/tar (или формат не поддерживается: 7z/rar пока нет)."
    unity = name.lower().endswith(".unitypackage")
    if unity:   # в unitypackage имена хранятся в файлах «pathname» — покажем их вместо GUID
        rows = _unity_names(path) or rows
    sel = [r for r in rows if fnmatch.fnmatch(r[0], pattern) or fnmatch.fnmatch(os.path.basename(r[0]), pattern)]
    total_size = sum(r[1] for r in rows)
    exts = {}
    for r in rows:
        e = os.path.splitext(r[0])[1].lower() or "(без расш.)"
        exts[e] = exts.get(e, 0) + 1
    top_ext = ", ".join(f"{e}: {n}" for e, n in sorted(exts.items(), key=lambda x: -x[1])[:15])
    out = [f"Архив {name}: {len(rows)} файлов, распакованный размер {human(total_size)}.",
           f"Типы: {top_ext}"]
    if pattern != "*":
        out.append(f"По маске «{pattern}»: {len(sel)}")
    for fn, sz, csz in sel[offset:offset + limit]:
        out.append(f"{fn}\t{human(sz)}")
    if len(sel) > offset + limit:
        out.append(f"… ещё {len(sel) - offset - limit}, используй offset={offset + limit} или pattern")
    return "\n".join(out)


def _unity_names(path):
    rows, names, sizes = [], {}, {}
    with tarfile.open(path, "r:*") as t:
        for m in t:
            parts = m.name.strip("./").split("/")
            if len(parts) == 2 and parts[1] == "pathname" and m.isfile():
                names[parts[0]] = t.extractfile(m).read().decode("utf-8", "replace").splitlines()[0]
            elif len(parts) == 2 and parts[1] == "asset" and m.isfile():
                sizes[parts[0]] = m.size
    for guid, nm in names.items():
        rows.append((nm, sizes.get(guid, 0), None))
    return rows


def archive_extract(uid: int, name: str, patterns: list, dest: str = "", max_files: int = 2000) -> str:
    """Распаковать из архива только нужное (по маскам) — потоково, по одному файлу."""
    path = ws._path(uid, name)
    pats = patterns or ["*"]
    base = ws.safe_name(dest or os.path.splitext(os.path.basename(name))[0] + "_files")
    saved, skipped, written = [], [], 0
    match = lambda n: any(fnmatch.fnmatch(n, p) or fnmatch.fnmatch(os.path.basename(n), p) for p in pats)

    def put(rel, size, opener):
        nonlocal written
        if any(seg in rel for seg in ("__MACOSX/", ".git/", "node_modules/", "__pycache__/")):
            return
        if size > MAX_BIG_FILE:
            skipped.append(f"{rel} (больше 2 ГБ)"); return
        if size > free_disk() - 200 * 1024 * 1024:
            skipped.append(f"{rel} (мало места на сервере)"); return
        target = ws._path(uid, f"{base}/{rel}")
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with opener() as src, open(target, "wb") as out:
            shutil.copyfileobj(src, out, 1024 * 1024)
        os.chmod(target, 0o600)
        written += size
        saved.append(f"{base}/{ws.safe_name(rel)}")

    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for m in z.infolist():
                if m.is_dir() or not match(m.filename):
                    continue
                if len(saved) >= max_files:
                    skipped.append("… остальное (лимит файлов за раз)"); break
                put(m.filename, m.file_size, lambda m=m: z.open(m))
    elif _is_tar(name) or tarfile.is_tarfile(path):
        unity = name.lower().endswith(".unitypackage")
        names = {}
        if unity:
            with tarfile.open(path, "r:*") as t:
                for m in t:
                    p = m.name.strip("./").split("/")
                    if len(p) == 2 and p[1] == "pathname" and m.isfile():
                        names[p[0]] = t.extractfile(m).read().decode("utf-8", "replace").splitlines()[0]
        with tarfile.open(path, "r:*") as t:
            for m in t:
                if not m.isfile():
                    continue
                rel = m.name
                if unity:
                    p = m.name.strip("./").split("/")
                    if len(p) != 2 or p[1] != "asset" or p[0] not in names:
                        continue
                    rel = names[p[0]]
                if not match(rel):
                    continue
                if len(saved) >= max_files:
                    skipped.append("… остальное (лимит файлов за раз)"); break
                put(rel, m.size, lambda m=m: t.extractfile(m))
    else:
        raise ValueError("это не zip/tar-архив")
    res = f"Распаковано {len(saved)} файлов ({human(written)}) в папку {base}/."
    if skipped:
        res += f" Пропущено {len(skipped)}: " + "; ".join(skipped[:8])
    tree = sorted({"/".join(n.split("/")[:4]) for n in saved})
    return res + "\n" + "\n".join(tree[:120])


# ---------- Просмотр больших файлов ----------

def read_range(uid: int, name: str, offset: int = 0, length: int = 512, mode: str = "hex") -> str:
    size = size_of(uid, name)
    offset = max(0, min(offset, size))
    length = max(1, min(length, 4096 if mode == "hex" else 60_000))
    with open(ws._path(uid, name), "rb") as f:
        f.seek(offset)
        data = f.read(length)
    if mode == "text":
        return f"{name} [{offset}..{offset + len(data)}) из {size} байт:\n" + data.decode("utf-8", "replace")
    lines = [f"{name}: {size} байт, показано {len(data)} с 0x{offset:x}"]
    for i in range(0, len(data), 16):
        row = data[i:i + 16]
        hexs = " ".join(f"{b:02x}" for b in row)
        asc = "".join(chr(b) if 32 <= b < 127 else "." for b in row)
        lines.append(f"{offset + i:08x}  {hexs:<47}  {asc}")
    return "\n".join(lines)


def read_lines_stream(uid: int, name: str, start: int, limit: int, max_chars: int = 60_000):
    """Нужные строки огромного текстового файла — без загрузки файла целиком."""
    out, chars, n = [], 0, 0
    with open(ws._path(uid, name), "rb") as f:
        for n, raw in enumerate(f, 1):
            if n < start:
                continue
            if n >= start + limit or chars > max_chars:
                return out, n, False
            ln = raw.rstrip(b"\r\n").decode("utf-8", "replace")
            ln = ln if len(ln) <= 2000 else ln[:2000] + " …[строка обрезана]"
            out.append(f"{n}→{ln}")
            chars += len(ln)
    return out, n, True


def iter_text_lines(uid: int, name: str):
    """Строки файла по одной — для поиска по большим файлам."""
    with open(ws._path(uid, name), "rb") as f:
        for raw in f:
            yield raw.rstrip(b"\r\n").decode("utf-8", "replace")


# ---------- Инструменты для модели ----------

TOOLS = [
    {"type": "function", "function": {
        "name": "download_url",
        "description": "Скачать файл по ссылке в рабочую папку (до 2 ГБ, потоково): Google Drive, Dropbox, "
                       "GitHub, прямые ссылки. Так пользователь присылает большие проекты — Telegram пропускает "
                       "через чат только до 20 МБ.",
        "parameters": {"type": "object", "properties": {
            "url": {"type": "string"}, "name": {"type": "string", "description": "Имя файла (необязательно)"}},
            "required": ["url"]}}},
    {"type": "function", "function": {
        "name": "archive_list",
        "description": "Содержимое архива БЕЗ распаковки (zip/apk/aab/jar/obb/xapk, tar/tar.gz/unitypackage): "
                       "файлы, размеры, типы. pattern — маска (*.cs, Assets/Scripts/*).",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"}, "pattern": {"type": "string"}, "offset": {"type": "integer"}},
            "required": ["name"]}}},
    {"type": "function", "function": {
        "name": "archive_extract",
        "description": "Распаковать из архива только нужные файлы по маскам (например [\"*.cs\", \"lib/arm64-v8a/*.so\"]) "
                       "в папку рабочей папки. Для больших архивов не распаковывай всё — только нужное.",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"},
            "patterns": {"type": "array", "items": {"type": "string"}},
            "dest": {"type": "string", "description": "Папка назначения"}},
            "required": ["name", "patterns"]}}},
    {"type": "function", "function": {
        "name": "read_range",
        "description": "Посмотреть кусок любого файла по смещению: mode=hex — hex-дамп (до 4 КБ), "
                       "mode=text — как текст (до 60 тыс. символов). Для огромных и двоичных файлов.",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"}, "offset": {"type": "integer"},
            "length": {"type": "integer"}, "mode": {"type": "string", "description": "hex | text"}},
            "required": ["name"]}}},
]
TOOL_NAMES = {t["function"]["name"] for t in TOOLS}

NOTE = ("БОЛЬШИЕ ФАЙЛЫ: через чат Telegram бот получает файлы только до 20 МБ — большие проекты/игры (до 2 ГБ) "
        "пользователь присылает ссылкой, ты скачиваешь их download_url. Архивы смотри archive_list и распаковывай "
        "только нужное archive_extract (не всё подряд). Огромные файлы читай кусками: read_file (start_line/limit), "
        "read_range (hex/text по смещению), search_files, analyze_binary — они работают потоково. "
        "Целиком в ответ гигабайты не помещаются: изучай структуру и нужные места. "
        "Отправить пользователю бот может файл до 50 МБ — большее упаковывай частями или отправляй только изменённые файлы.")


async def run_tool(uid: int, name: str, args: dict, out, status):
    if name == "download_url":
        status.actions.append(f"⬇️ {args.get('url', '')[:60]}")
        await status.update(force=True)
        fname, got = await download_url(uid, args["url"], args.get("name") or "", status)
        status.actions.append(f"💾 {fname} ({human(got)})")
        h = await asyncio.to_thread(head, uid, fname)
        kind = "архив" if fname.lower().endswith(ARCHIVE_EXT) or h[:2] == b"PK" else \
            ("текст" if ws.is_text(h) else "двоичный")
        hint = {"архив": "Посмотри archive_list.", "текст": "Читай read_file/search_files.",
                "двоичный": "Смотри analyze_binary (overview) или read_range."}[kind]
        return f"Скачано: {fname}, {human(got)} ({kind}). {hint}"
    if name == "archive_list":
        return await asyncio.to_thread(archive_list, uid, args["name"], args.get("pattern") or "*",
                                       int(args.get("offset") or 0))
    if name == "archive_extract":
        status.actions.append(f"📦 Распаковываю {args.get('name', '')}")
        await status.update(force=True)
        return await asyncio.to_thread(archive_extract, uid, args["name"], args.get("patterns") or ["*"],
                                       args.get("dest") or "")
    if name == "read_range":
        return await asyncio.to_thread(read_range, uid, args["name"], int(args.get("offset") or 0),
                                       int(args.get("length") or 512), args.get("mode") or "hex")
    return None
