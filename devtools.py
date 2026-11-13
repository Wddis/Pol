"""Инструменты программирования: запуск кода, проверка, поиск, diff, архивы.

Запуск кода (run_code/run_file) доступен только владельцу бота: бот публичный, а код
выполняется на сервере. Владелец — тот, кто первым отправил /claim (или OWNER_ID в окружении).
"""
import asyncio
import difflib
import fnmatch
import io
import json
import os
import re
import resource
import shutil
import sys
import zipfile
import base64

import workspace as ws

RUN_TIMEOUT = 30              # секунд на запуск
RUN_MEMORY = 300 * 1024 * 1024
RUN_OUTPUT = 20_000           # символов вывода модели
OWNER_FILE = os.path.join(ws.DATA_DIR, "owner.json")


# ---------- Владелец ----------

def owner_id():
    env = os.environ.get("OWNER_ID")
    if env and env.strip().isdigit():
        return int(env)
    try:
        with open(OWNER_FILE, encoding="utf-8") as f:
            return int(json.load(f)["id"])
    except Exception:
        return None


def claim_owner(uid: int):
    """Возвращает (успех, текущий владелец)."""
    cur = owner_id()
    if cur is not None:
        return cur == uid, cur
    os.makedirs(os.path.dirname(OWNER_FILE), exist_ok=True)
    with open(OWNER_FILE, "w", encoding="utf-8") as f:
        json.dump({"id": int(uid)}, f)
    os.chmod(OWNER_FILE, 0o600)
    return True, uid


def is_owner(uid: int) -> bool:
    return owner_id() == uid


# ---------- Запуск кода ----------

def _limits():
    resource.setrlimit(resource.RLIMIT_CPU, (RUN_TIMEOUT, RUN_TIMEOUT + 2))
    resource.setrlimit(resource.RLIMIT_AS, (RUN_MEMORY, RUN_MEMORY))
    resource.setrlimit(resource.RLIMIT_FSIZE, (ws.MAX_BINARY_BYTES, ws.MAX_BINARY_BYTES))
    os.setsid()


def _safe_env(cwd: str) -> dict:
    # никаких ключей бота (TELEGRAM_TOKEN, UNOROUTER_API_KEY …) в окружении запускаемого кода
    return {"PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"), "HOME": cwd,
            "LANG": "C.UTF-8", "PYTHONIOENCODING": "utf-8", "PYTHONDONTWRITEBYTECODE": "1",
            "TMPDIR": cwd}


def _interpreter(language: str):
    lang = (language or "python").lower()
    if lang in ("python", "py", "python3"):
        return [sys.executable], ".py"
    if lang in ("bash", "sh", "shell"):
        return [shutil.which("bash") or "/bin/sh"], ".sh"
    if lang in ("node", "js", "javascript"):
        node = shutil.which("node")
        if not node:
            raise ValueError("Node.js на сервере не установлен — доступны python и bash")
        return [node], ".js"
    raise ValueError("язык: python, bash" + (", node" if shutil.which("node") else ""))


def _snapshot(cwd: str) -> dict:
    snap = {}
    for root, _, names in os.walk(cwd):
        for n in names:
            full = os.path.join(root, n)
            try:
                snap[os.path.relpath(full, cwd).replace(os.sep, "/")] = os.stat(full).st_mtime
            except OSError:
                pass
    return snap


async def _run(cmd: list, cwd: str, stdin: str = None) -> str:
    os.makedirs(cwd, exist_ok=True)
    before = _snapshot(cwd)
    proc = await asyncio.create_subprocess_exec(
        *cmd, cwd=cwd, env=_safe_env(cwd), preexec_fn=_limits,
        stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        out, _ = await asyncio.wait_for(proc.communicate(stdin.encode() if stdin is not None else None),
                                        timeout=RUN_TIMEOUT + 5)
        code = proc.returncode
        timed_out = False
    except asyncio.TimeoutError:
        try:
            os.killpg(proc.pid, 9)
        except Exception:
            proc.kill()
        out, code, timed_out = b"", None, True
    text = (out or b"").decode("utf-8", "replace")
    if len(text) > RUN_OUTPUT:
        text = text[:RUN_OUTPUT // 2] + f"\n… [вывод обрезан, всего {len(text)} символов] …\n" + text[-RUN_OUTPUT // 2:]
    after = _snapshot(cwd)
    changed = [n for n, m in after.items() if before.get(n) != m]
    if code in (-24, -9) and not timed_out:      # SIGXCPU / SIGKILL от лимита CPU
        timed_out = True
    res = ("⏱ Превышено время выполнения (" + str(RUN_TIMEOUT) + " с), процесс остановлен.\n" if timed_out
           else f"Код завершения: {code}\n")
    res += "Вывод:\n" + (text or "(пусто)")
    if changed:
        res += "\nСозданные/изменённые файлы в рабочей папке: " + ", ".join(changed[:30])
    return res


async def run_code(uid: int, language: str, code: str, stdin: str = None) -> str:
    cmd, _ = _interpreter(language)
    if cmd[0] == sys.executable:
        cmd = cmd + ["-c", code]
        return await _run(cmd, ws.files_dir(uid), stdin)
    if "bash" in cmd[0] or cmd[0].endswith("sh"):
        return await _run(cmd + ["-c", code], ws.files_dir(uid), stdin)
    return await _run(cmd + ["-e", code], ws.files_dir(uid), stdin)


async def run_file(uid: int, name: str, args: list = None, stdin: str = None) -> str:
    name = ws.safe_name(name)
    path = ws._path(uid, name)
    if not os.path.exists(path):
        raise ValueError(f"файла {name} нет")
    ext = os.path.splitext(name)[1].lower()
    lang = {".py": "python", ".sh": "bash", ".js": "node", ".mjs": "node"}.get(ext)
    if not lang:
        raise ValueError("запускать можно .py, .sh, .js")
    cmd, _ = _interpreter(lang)
    return await _run(cmd + [name] + [str(a) for a in (args or [])][:20], ws.files_dir(uid), stdin)


# ---------- Проверка кода ----------

def check_code(uid: int, name: str) -> str:
    raw = ws.read_bytes(uid, name)
    text = ws.decode_text(raw)
    ext = os.path.splitext(name)[1].lower()
    if ext == ".py":
        try:
            compile(text, name, "exec")
        except SyntaxError as e:
            return f"❌ Синтаксическая ошибка: строка {e.lineno}, позиция {e.offset}: {e.msg}\n{(e.text or '').rstrip()}"
        try:
            from pyflakes.api import check as flakes
            from pyflakes.reporter import Reporter
            out, err = io.StringIO(), io.StringIO()
            n = flakes(text, name, Reporter(out, err))
            msgs = (out.getvalue() + err.getvalue()).strip()
            return "✅ Синтаксис в порядке." + (f"\nPyflakes, замечаний: {n}\n{msgs[:6000]}" if n else
                                                 " Pyflakes замечаний не нашёл.")
        except ImportError:
            return "✅ Синтаксис в порядке."
    if ext == ".json":
        try:
            json.loads(text)
            return "✅ JSON корректный."
        except json.JSONDecodeError as e:
            return f"❌ JSON: строка {e.lineno}, позиция {e.colno}: {e.msg}"
    if ext in (".yml", ".yaml"):
        try:
            import yaml
            yaml.safe_load(text)
            return "✅ YAML корректный."
        except ImportError:
            return "Проверка YAML недоступна (нет PyYAML)."
        except Exception as e:
            return f"❌ YAML: {e}"
    if ext in (".js", ".mjs", ".cjs") and shutil.which("node"):
        import subprocess
        r = subprocess.run([shutil.which("node"), "--check", ws._path(uid, name)],
                           capture_output=True, text=True, timeout=20)
        return "✅ Синтаксис JS в порядке." if r.returncode == 0 else "❌ " + (r.stderr or r.stdout)[:4000]
    if ext in (".sh", ".bash") and shutil.which("bash"):
        import subprocess
        r = subprocess.run([shutil.which("bash"), "-n", ws._path(uid, name)],
                           capture_output=True, text=True, timeout=20)
        return "✅ Синтаксис bash в порядке." if r.returncode == 0 else "❌ " + r.stderr[:4000]
    return f"Для {ext or 'этого типа'} автоматической проверки нет — проверь по смыслу."


# ---------- Поиск, diff, архив ----------

def _glob_rx(g: str):
    """Маска в регулярку: ** — любая вложенность (в т.ч. ноль папок), * — в пределах папки."""
    out, i = "", 0
    while i < len(g):
        if g.startswith("**/", i):
            out += "(?:.*/)?"; i += 3
        elif g.startswith("**", i):
            out += ".*"; i += 2
        elif g[i] == "*":
            out += "[^/]*"; i += 1
        elif g[i] == "?":
            out += "[^/]"; i += 1
        else:
            out += re.escape(g[i]); i += 1
    return re.compile(out + r"\Z", re.I)


def _match_glob(name: str, glob: str) -> bool:
    pats = [g.strip() for g in (glob or "*").split(",") if g.strip()] or ["*"]
    for g in pats:
        rx = _glob_rx(g)
        if rx.match(name) or ("/" not in g and rx.match(os.path.basename(name))):
            return True
    return False


def search_files(uid: int, pattern: str, glob: str = "*", regex: bool = False, context: int = 0,
                 mode: str = "content", case: bool = False, max_hits: int = 200) -> str:
    try:
        rx = re.compile(pattern if regex else re.escape(pattern), 0 if case else re.I)
    except re.error as e:
        return f"Ошибка в регулярном выражении: {e}"
    context = max(0, min(int(context or 0), 10))
    out, files_hit, counts, hits = [], [], [], 0
    for f in ws.list_files(uid):
        if not _match_glob(f["name"], glob):
            continue
        raw = ws.read_bytes(uid, f["name"])
        if not ws.is_text(raw):
            continue
        lines = ws.decode_text(raw).splitlines()
        idx = [i for i, ln in enumerate(lines) if rx.search(ln)]
        if not idx:
            continue
        files_hit.append(f["name"])
        counts.append(f"{f['name']}: {len(idx)}")
        if mode != "content":
            continue
        shown = set()
        for i in idx:
            for j in range(max(0, i - context), min(len(lines), i + context + 1)):
                if (f["name"], j) in shown:
                    continue
                shown.add((f["name"], j))
                sep = ":" if j == i else "-"
                out.append(f"{f['name']}:{j + 1}{sep} {lines[j][:300]}")
            if context:
                out.append("--")
            hits += 1
            if hits >= max_hits:
                return "\n".join(out) + f"\n… (показано {max_hits} совпадений, уточни запрос или glob)"
    if mode == "files":
        return "\n".join(files_hit) or "Ничего не найдено."
    if mode == "count":
        return "\n".join(counts) or "Ничего не найдено."
    return "\n".join(out) or "Ничего не найдено."


def glob_files(uid: int, pattern: str) -> str:
    found = [f for f in ws.list_files(uid) if _match_glob(f["name"], pattern)]
    if not found:
        return "Ничего не найдено."
    return "\n".join(f"{f['name']}\t{f['size']} байт" for f in found[:500]) + \
        (f"\n… ещё {len(found) - 500}" if len(found) > 500 else "")


def rename_file(uid: int, src: str, dst: str) -> str:
    a, b = ws._path(uid, src), ws._path(uid, dst)
    if not os.path.exists(a):
        raise ValueError(f"файла {src} нет")
    if os.path.exists(b):
        raise ValueError(f"{dst} уже существует")
    os.makedirs(os.path.dirname(b), exist_ok=True)
    os.replace(a, b)
    return f"{src} → {ws.safe_name(dst)}"


# ---------- Патч (unified diff) ----------

def _parse_patch(patch: str):
    files, cur, hunk = [], None, None
    for line in patch.splitlines():
        if line.startswith("--- "):
            cur = {"old": line[4:].split("\t")[0].strip(), "new": None, "hunks": []}
            files.append(cur)
            hunk = None
        elif line.startswith("+++ ") and cur is not None:
            cur["new"] = line[4:].split("\t")[0].strip()
        elif line.startswith("@@") and cur is not None:
            m = re.match(r"@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@", line)
            hunk = {"start": int(m.group(1)) if m else 1, "lines": []}
            cur["hunks"].append(hunk)
        elif hunk is not None and (line[:1] in (" ", "-", "+") or line == ""):
            hunk["lines"].append(line if line else " ")
        elif line.startswith("\\"):
            continue
    clean = lambda p: re.sub(r"^[ab]/", "", p) if p and p != "/dev/null" else None
    for f in files:
        f["old"], f["new"] = clean(f["old"]), clean(f["new"])
    return files


def apply_patch(uid: int, patch: str) -> str:
    files = _parse_patch(patch)
    if not files:
        raise ValueError("не нашёл в патче ни одного файла (нужен unified diff: --- / +++ / @@)")
    results, writes = [], {}
    for f in files:
        target = f["new"] or f["old"]
        if f["new"] is None:                      # удаление файла
            writes[f["old"]] = None
            results.append(f"удалён {f['old']}")
            continue
        if f["old"] is None:                      # новый файл
            text_lines = []
        else:
            text_lines = ws.decode_text(ws.read_bytes(uid, f["old"])).splitlines()
        offset = 0
        for k, h in enumerate(f["hunks"], 1):
            old = [l[1:] for l in h["lines"] if l[:1] in (" ", "-")]
            new = [l[1:] for l in h["lines"] if l[:1] in (" ", "+")]
            want = h["start"] - 1 + offset
            pos = None
            if not old:
                pos = min(max(want, 0), len(text_lines))
            else:
                cands = [i for i in range(len(text_lines) - len(old) + 1) if text_lines[i:i + len(old)] == old]
                if not cands:   # допускаем расхождение пробелов в конце строк
                    strip = [l.rstrip() for l in old]
                    cands = [i for i in range(len(text_lines) - len(old) + 1)
                             if [l.rstrip() for l in text_lines[i:i + len(old)]] == strip]
                if cands:
                    pos = min(cands, key=lambda i: abs(i - want))
            if pos is None:
                raise ValueError(f"{target}: блок {k} не совпал с файлом (контекст отличается). "
                                 f"Ничего не изменено — прочитай файл и пересобери патч или используй edit_file.")
            text_lines[pos:pos + len(old)] = new
            offset += len(new) - len(old)
        writes[target] = "\n".join(text_lines) + "\n"
        if f["old"] and f["new"] and f["old"] != f["new"]:
            writes[f["old"]] = None
        results.append(f"{target}: применено блоков {len(f['hunks'])}")
    for name, content in writes.items():             # всё совпало — записываем
        if content is None:
            try:
                ws.delete_file(uid, name)
            except FileNotFoundError:
                pass
        else:
            ws.save_file(uid, name, content.encode("utf-8"))
    return "Патч применён: " + "; ".join(results)


def diff_files(uid: int, a: str, b: str) -> str:
    ta = ws.decode_text(ws.read_bytes(uid, a)).splitlines(keepends=True)
    tb = ws.decode_text(ws.read_bytes(uid, b)).splitlines(keepends=True)
    d = "".join(difflib.unified_diff(ta, tb, a, b, n=2))
    return (d[:20000] + "\n… (diff обрезан)" if len(d) > 20000 else d) or "Файлы одинаковые."


def make_zip(uid: int, names: list, archive: str) -> str:
    files = ws.list_files(uid)
    pats = names or ["*"]
    chosen = [f["name"] for f in files if any(fnmatch.fnmatch(f["name"], p) for p in pats)]
    archive = ws.safe_name(archive if archive.endswith(".zip") else archive + ".zip")
    chosen = [n for n in chosen if n != archive]
    if not chosen:
        raise ValueError("нет подходящих файлов")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for n in chosen:
            z.writestr(n, ws.read_bytes(uid, n))
    ws.save_file(uid, archive, buf.getvalue())
    return archive, len(chosen)


# ---------- GitHub ----------

import httpx


def _github_token(uid: int):
    for c in ws.load_connectors(uid).values():
        if c.get("base", "").startswith("https://api.github.com"):
            for k, v in (c.get("headers") or {}).items():
                if k.lower() == "authorization":
                    return v
    return None


def _repo(repo: str):
    m = re.search(r"(?:github\.com[/:])?([\w.-]+)/([\w.-]+?)(?:\.git)?/?$", (repo or "").strip())
    if not m:
        raise ValueError("укажи репозиторий как владелец/имя, например Wddis/Pol")
    return m.group(1), m.group(2)


async def git_clone(uid: int, repo: str, branch: str = "", path: str = "") -> str:
    owner, name = _repo(repo)
    headers = {"User-Agent": "NoizozBot/1.0", "Accept": "application/vnd.github+json"}
    tok = _github_token(uid)
    if tok:
        headers["Authorization"] = tok
    url = f"https://api.github.com/repos/{owner}/{name}/zipball/{branch}".rstrip("/")
    async with httpx.AsyncClient(timeout=120, follow_redirects=True, headers=headers) as c:
        r = await c.get(url)
    if r.status_code == 404:
        raise ValueError("репозиторий/ветка не найдены (или он приватный — подключи токен: /api add github …)")
    if r.status_code != 200:
        raise ValueError(f"GitHub ответил {r.status_code}: {r.text[:200]}")
    if len(r.content) > 50 * 1024 * 1024:
        raise ValueError("архив репозитория больше 50 МБ")
    dest = ws.safe_name(path or name)
    saved, skipped = [], []
    with zipfile.ZipFile(io.BytesIO(r.content)) as z:
        for m in z.infolist():
            if m.is_dir():
                continue
            rel = m.filename.split("/", 1)[1] if "/" in m.filename else m.filename
            if not rel or any(seg in rel for seg in (".git/", "node_modules/", "__pycache__/")):
                continue
            if m.file_size > ws.MAX_BINARY_BYTES:
                skipped.append(rel)
                continue
            try:
                saved.append(ws.save_file(uid, f"{dest}/{rel}", z.read(m)))
            except ValueError as e:
                skipped.append(f"{rel} ({e})")
                if "переполнена" in str(e) or "уже" in str(e):
                    break
    res = f"Скачан {owner}/{name}{'@' + branch if branch else ''} в папку {dest}/: {len(saved)} файлов."
    if skipped:
        res += f" Пропущено {len(skipped)}: " + ", ".join(skipped[:10])
    tree = sorted({"/".join(n.split("/")[:3]) for n in saved})
    return res + "\nСтруктура (до 3 уровней):\n" + "\n".join(tree[:150])


async def github_commit(uid: int, repo: str, branch: str, message: str, files: list,
                        delete: list = None, from_branch: str = "", client=None) -> str:
    """Один коммит с несколькими файлами через Git Data API. files: [{path, name}] —
    path в репозитории, name — файл рабочей папки (по умолчанию = path)."""
    tok = _github_token(uid)
    if not tok:
        raise ValueError("нужен токен GitHub с правом записи: /api add github https://api.github.com "
                         "header:Authorization=Bearer ТОКЕН")
    owner, name = _repo(repo)
    if not branch:
        raise ValueError("укажи ветку (branch)")
    if not (files or delete):
        raise ValueError("нечего коммитить")
    base = f"https://api.github.com/repos/{owner}/{name}"
    hdr = {"Authorization": tok, "User-Agent": "NoizozBot/1.0", "Accept": "application/vnd.github+json"}
    own = client is None
    c = client or httpx.AsyncClient(timeout=60, headers=hdr)
    try:
        async def req(method, url, **kw):
            r = await c.request(method, base + url, headers=hdr, **kw)
            if r.status_code >= 300:
                raise ValueError(f"GitHub {method} {url}: {r.status_code} {r.text[:200]}")
            return r.json()
        try:
            head = (await req("GET", f"/git/ref/heads/{branch}"))["object"]["sha"]
            created = False
        except ValueError:
            src = from_branch or (await req("GET", ""))["default_branch"]
            head = (await req("GET", f"/git/ref/heads/{src}"))["object"]["sha"]
            await req("POST", "/git/refs", json={"ref": f"refs/heads/{branch}", "sha": head})
            created = True
        base_tree = (await req("GET", f"/git/commits/{head}"))["tree"]["sha"]
        tree = []
        for f in files or []:
            path = f.get("path") or f.get("name")
            data = ws.read_bytes(uid, f.get("name") or path)
            blob = await req("POST", "/git/blobs", json={"content": base64.b64encode(data).decode(),
                                                          "encoding": "base64"})
            tree.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        for p in delete or []:
            tree.append({"path": p, "mode": "100644", "type": "blob", "sha": None})
        new_tree = (await req("POST", "/git/trees", json={"base_tree": base_tree, "tree": tree}))["sha"]
        commit = await req("POST", "/git/commits", json={"message": message or "Update via Telegram bot",
                                                         "tree": new_tree, "parents": [head]})
        await req("PATCH", f"/git/refs/heads/{branch}", json={"sha": commit["sha"]})
    finally:
        if own:
            await c.aclose()
    return (f"Коммит {commit['sha'][:7]} в {owner}/{name}@{branch}"
            f"{' (ветка создана)' if created else ''}: файлов {len(files or [])}"
            f"{', удалено ' + str(len(delete)) if delete else ''}. "
            f"https://github.com/{owner}/{name}/commit/{commit['sha']}")


# ---------- Описания инструментов ----------

TOOLS_ALL = [
    {"type": "function", "function": {
        "name": "todo_write",
        "description": "План для многошаговой задачи (3+ шагов): полный список пунктов со статусами "
                       "pending / in_progress / completed. Пользователь видит план в статусе. Обновляй по ходу: "
                       "ровно один пункт in_progress, отмечай completed сразу после выполнения. "
                       "Для простых задач не нужен.",
        "parameters": {"type": "object", "properties": {
            "todos": {"type": "array", "items": {"type": "object", "properties": {
                "content": {"type": "string"}, "status": {"type": "string"}}}}},
            "required": ["todos"]}}},
    {"type": "function", "function": {
        "name": "glob_files",
        "description": "Найти файлы по маске имени: *.py, src/**/*.js, **/test_*.py (несколько масок через запятую).",
        "parameters": {"type": "object", "properties": {"pattern": {"type": "string"}}, "required": ["pattern"]}}},
    {"type": "function", "function": {
        "name": "search_files",
        "description": "Поиск по содержимому файлов (как grep/ripgrep). mode: content — строки «файл:номер: текст» "
                       "(context — сколько строк вокруг показать), files — только имена файлов, count — число "
                       "совпадений. Ищи им, где определена/используется функция, прежде чем менять код.",
        "parameters": {"type": "object", "properties": {
            "pattern": {"type": "string", "description": "Что искать"},
            "glob": {"type": "string", "description": "Маска файлов: *.py, src/**/*.ts (по умолчанию все)"},
            "regex": {"type": "boolean", "description": "pattern — регулярное выражение"},
            "context": {"type": "integer", "description": "Строк контекста вокруг совпадения (0-10)"},
            "mode": {"type": "string", "description": "content | files | count"},
            "case": {"type": "boolean", "description": "Учитывать регистр"}},
            "required": ["pattern"]}}},
    {"type": "function", "function": {
        "name": "apply_patch",
        "description": "Применить патч в формате unified diff (--- a/файл, +++ b/файл, @@ … @@) — правки в "
                       "нескольких местах и файлах за раз; /dev/null — создать или удалить файл. "
                       "Всё или ничего: если блок не совпал, ничего не меняется.",
        "parameters": {"type": "object", "properties": {"patch": {"type": "string"}}, "required": ["patch"]}}},
    {"type": "function", "function": {
        "name": "rename_file",
        "description": "Переименовать или переместить файл в рабочей папке.",
        "parameters": {"type": "object", "properties": {
            "src": {"type": "string"}, "dst": {"type": "string"}}, "required": ["src", "dst"]}}},
    {"type": "function", "function": {
        "name": "git_clone",
        "description": "Скачать репозиторий GitHub целиком в рабочую папку (папка = имя репозитория или path). "
                       "Приватные — если подключён токен (/api add github …). Возвращает структуру проекта.",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string", "description": "владелец/имя или ссылка"},
            "branch": {"type": "string", "description": "Ветка/тег (по умолчанию основная)"},
            "path": {"type": "string", "description": "Папка в рабочей папке"}},
            "required": ["repo"]}}},
    {"type": "function", "function": {
        "name": "github_commit",
        "description": "Закоммитить файлы из рабочей папки в GitHub ОДНИМ коммитом (base64 и sha делаются сами). "
                       "Нужен токен с правом записи. Если ветки нет — она создаётся от from_branch/основной. "
                       "ПЕРЕД вызовом скажи пользователю, какие файлы и в какую ветку пишешь; в основную ветку "
                       "(main/master) — только если пользователь прямо попросил.",
        "parameters": {"type": "object", "properties": {
            "repo": {"type": "string"}, "branch": {"type": "string"}, "message": {"type": "string"},
            "files": {"type": "array", "description": "[{path: путь в репозитории, name: файл рабочей папки}]",
                      "items": {"type": "object", "properties": {
                          "path": {"type": "string"}, "name": {"type": "string"}}}},
            "delete": {"type": "array", "items": {"type": "string"}, "description": "Пути удалить из репозитория"},
            "from_branch": {"type": "string"}},
            "required": ["repo", "branch", "message"]}}},
    {"type": "function", "function": {
        "name": "check_code",
        "description": "Проверить файл на ошибки: Python (синтаксис + pyflakes: неиспользуемое, неопределённые имена), "
                       "JSON, YAML, bash, JS. Используй после изменения кода.",
        "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
    {"type": "function", "function": {
        "name": "diff_files",
        "description": "Показать разницу (unified diff) между двумя файлами рабочей папки.",
        "parameters": {"type": "object", "properties": {
            "a": {"type": "string"}, "b": {"type": "string"}}, "required": ["a", "b"]}}},
    {"type": "function", "function": {
        "name": "zip_files",
        "description": "Собрать файлы рабочей папки в .zip (маски, например [\"src/*\", \"*.py\"]) "
                       "и прислать архив пользователю.",
        "parameters": {"type": "object", "properties": {
            "names": {"type": "array", "items": {"type": "string"}, "description": "Маски; пусто — все файлы"},
            "archive": {"type": "string", "description": "Имя архива"}},
            "required": ["archive"]}}},
]

TOOLS_OWNER = [
    {"type": "function", "function": {
        "name": "run_code",
        "description": f"Выполнить код на сервере в рабочей папке пользователя (cwd = рабочая папка, файлы доступны "
                       f"по именам). Языки: python, bash" + (", node" if shutil.which("node") else "") +
                       f". Лимит {RUN_TIMEOUT} с и {RUN_MEMORY // 1024 // 1024} МБ. Используй, чтобы проверить код, "
                       f"посчитать, прогнать тесты, обработать файлы. Созданные файлы появятся в рабочей папке.",
        "parameters": {"type": "object", "properties": {
            "language": {"type": "string", "description": "python | bash"},
            "code": {"type": "string"},
            "stdin": {"type": "string", "description": "Ввод для программы (необязательно)"}},
            "required": ["code"]}}},
    {"type": "function", "function": {
        "name": "run_file",
        "description": "Запустить скрипт из рабочей папки (.py, .sh, .js) с аргументами и показать вывод.",
        "parameters": {"type": "object", "properties": {
            "name": {"type": "string"},
            "args": {"type": "array", "items": {"type": "string"}},
            "stdin": {"type": "string"}},
            "required": ["name"]}}},
]

_WORKFLOW = ("КАК РАБОТАТЬ С КОДОМ (как опытный разработчик): 1) сначала разберись — glob_files/search_files "
             "найдут нужные места, read_file покажет код с номерами строк (читай нужный диапазон, а не всё); "
             "2) для задач из 3+ шагов веди план todo_write; 3) меняй точечно: edit_file (old ровно как в файле, "
             "без номеров строк; replace_all для переименований; edits — несколько правок сразу) или apply_patch "
             "для правок в нескольких файлах; write_file — только для новых файлов или полной переписи; "
             "4) проверь результат: check_code{run}; 5) кратко скажи, что изменено, со ссылками файл:строка. "
             "Не меняй то, о чём не просили; сохраняй стиль кода. Репозиторий GitHub можно скачать целиком "
             "(git_clone) и закоммитить изменения одним коммитом (github_commit).")
NOTE_OWNER = ("ПРОГРАММИРОВАНИЕ: можешь запускать код (run_code: python/bash) и скрипты (run_file) в рабочей папке, "
              "проверять файлы (check_code), искать по проекту, сравнивать (diff_files), собирать архивы (zip_files). "
              + _WORKFLOW.replace("{run}", " и запусти тесты/скрипт (run_file/run_code) — только потом говори, что готово")
              + " Не запускай ничего опасного: сканирование сети, майнинг, массовые запросы к чужим сайтам — запрещено.")
NOTE_GUEST = ("ПРОГРАММИРОВАНИЕ: можешь проверять файлы (check_code), искать по проекту, сравнивать (diff_files), "
              "собирать архивы (zip_files). Запуск кода доступен только владельцу бота. "
              + _WORKFLOW.replace("{run}", ""))


async def run_tool(uid: int, name: str, args: dict, out, status):
    if name == "todo_write":
        todos = [t for t in (args.get("todos") or []) if isinstance(t, dict) and t.get("content")][:20]
        status.todos = todos
        await status.update(force=True)
        done = sum(t.get("status") == "completed" for t in todos)
        return f"План обновлён: {done}/{len(todos)} выполнено."
    if name == "glob_files":
        return await asyncio.to_thread(glob_files, uid, args.get("pattern") or "*")
    if name == "search_files":
        status.actions.append(f"🔍 Ищу «{args.get('pattern', '')[:40]}»")
        return await asyncio.to_thread(search_files, uid, args.get("pattern", ""), args.get("glob") or "*",
                                       bool(args.get("regex")), args.get("context") or 0,
                                       args.get("mode") or "content", bool(args.get("case")))
    if name == "apply_patch":
        res = await asyncio.to_thread(apply_patch, uid, args.get("patch", ""))
        status.actions.append("✏️ Применил патч")
        return res
    if name == "rename_file":
        res = await asyncio.to_thread(rename_file, uid, args["src"], args["dst"])
        status.actions.append(f"✏️ {res}")
        return f"Переименовано: {res}"
    if name == "git_clone":
        status.actions.append(f"⬇️ Скачиваю {args.get('repo', '')}")
        await status.update(force=True)
        return await git_clone(uid, args["repo"], args.get("branch") or "", args.get("path") or "")
    if name == "github_commit":
        status.actions.append(f"⬆️ Коммит в {args.get('repo', '')}@{args.get('branch', '')}")
        await status.update(force=True)
        return await github_commit(uid, args["repo"], args.get("branch", ""), args.get("message", ""),
                                   args.get("files") or [], args.get("delete") or [], args.get("from_branch") or "")
    if name == "check_code":
        status.actions.append(f"🧪 Проверяю {args.get('name', '')}")
        return await asyncio.to_thread(check_code, uid, args["name"])
    if name == "diff_files":
        return await asyncio.to_thread(diff_files, uid, args["a"], args["b"])
    if name == "zip_files":
        archive, n = await asyncio.to_thread(make_zip, uid, args.get("names") or [], args.get("archive") or "project.zip")
        await out.reply_document(document=ws.read_bytes(uid, archive), filename=os.path.basename(archive),
                                 caption=f"📦 {n} файл(ов)")
        status.actions.append(f"📦 Отправил архив {archive}")
        return f"Архив {archive} ({n} файлов) создан и отправлен пользователю."
    if name in ("run_code", "run_file"):
        if not is_owner(uid):
            return "Запуск кода доступен только владельцу бота (/claim). Скажи пользователю об этом."
        if name == "run_code":
            status.actions.append(f"▶️ Запускаю {args.get('language') or 'python'}-код")
            await status.update(force=True)
            return await run_code(uid, args.get("language") or "python", args.get("code", ""), args.get("stdin"))
        status.actions.append(f"▶️ Запускаю {args.get('name', '')}")
        await status.update(force=True)
        return await run_file(uid, args["name"], args.get("args"), args.get("stdin"))
    return None


TOOL_NAMES = {t["function"]["name"] for t in TOOLS_ALL + TOOLS_OWNER}
