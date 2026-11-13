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

def search_files(uid: int, pattern: str, glob: str = "*", regex: bool = False, max_hits: int = 200) -> str:
    try:
        rx = re.compile(pattern if regex else re.escape(pattern), re.I)
    except re.error as e:
        return f"Ошибка в регулярном выражении: {e}"
    hits = []
    for f in ws.list_files(uid):
        if not fnmatch.fnmatch(f["name"], glob) and not fnmatch.fnmatch(os.path.basename(f["name"]), glob):
            continue
        raw = ws.read_bytes(uid, f["name"])
        if not ws.is_text(raw):
            continue
        for i, line in enumerate(ws.decode_text(raw).splitlines(), 1):
            if rx.search(line):
                hits.append(f"{f['name']}:{i}: {line.strip()[:200]}")
                if len(hits) >= max_hits:
                    return "\n".join(hits) + f"\n… (показано {max_hits}, уточни запрос)"
    return "\n".join(hits) or "Ничего не найдено."


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


# ---------- Описания инструментов ----------

TOOLS_ALL = [
    {"type": "function", "function": {
        "name": "search_files",
        "description": "Поиск по содержимому всех текстовых файлов рабочей папки (как grep). "
                       "Возвращает файл:строка: текст.",
        "parameters": {"type": "object", "properties": {
            "pattern": {"type": "string", "description": "Что искать"},
            "glob": {"type": "string", "description": "Маска файлов, например *.py (по умолчанию все)"},
            "regex": {"type": "boolean", "description": "pattern — регулярное выражение"}},
            "required": ["pattern"]}}},
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

NOTE_OWNER = ("ПРОГРАММИРОВАНИЕ: можешь запускать код (run_code: python/bash) и скрипты (run_file) в рабочей папке, "
              "проверять файлы (check_code), искать по проекту (search_files), сравнивать (diff_files), "
              "собирать архивы (zip_files). После правки кода — проверь его (check_code и/или run_file с тестом), "
              "и только потом говори, что готово. Не запускай ничего опасного: сканирование сети, майнинг, "
              "массовые запросы к чужим сайтам — запрещено.")
NOTE_GUEST = ("ПРОГРАММИРОВАНИЕ: можешь проверять файлы (check_code), искать по проекту (search_files), "
              "сравнивать (diff_files), собирать архивы (zip_files). Запуск кода доступен только владельцу бота.")


async def run_tool(uid: int, name: str, args: dict, out, status):
    if name == "search_files":
        status.actions.append(f"🔍 Ищу «{args.get('pattern', '')[:40]}»")
        return await asyncio.to_thread(search_files, uid, args.get("pattern", ""), args.get("glob") or "*",
                                       bool(args.get("regex")))
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
