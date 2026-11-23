"""Агент бота со своим облачным Linux (23.11.2026) — по схеме OpenHands / SWE-agent / E2B / Open Interpreter:
модель (Gemini, brain.py) в цикле «подумай → сделай шаг инструментом → посмотри результат», пока задача не решена.

Песочница (Sandbox):
- docker  — контейнер t2x2-sandbox (tools/tgbot/sandbox/Dockerfile): Ubuntu + экран Xvfb :1 + fluxbox + xterm + python/ffmpeg/git;
- user    — если Docker нет: отдельный непривилегированный пользователь t2x2agent со своим экраном Xvfb, своей папкой и
            ПУСТЫМ окружением (секреты бота — токен, cookies, ключи — внутри недоступны).
Код бота доступен ТОЛЬКО ДЛЯ ЧТЕНИЯ (/repo); всё рабочее — в /work (папка агента).

Инструменты агента: shell, read_file, write_file, edit_file, list_dir, screenshot (модель сама видит картинку),
send_to_owner (файл в Telegram), think (рассуждение «вслух» — модель обсуждает план и проблемы), finish.
"""
import base64, json, os, re, shutil, subprocess, threading, time
import brain

WORK = os.path.join(os.environ.get("T2X2_WORK", os.path.expanduser("~/t2x2work")), "agent")   # docker mode: mounted as /work
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
USER = "t2x2agent"; DISPLAY = ":77"; IMG = "t2x2-sandbox"; CT = "t2x2-sandbox"
OUT_MAX = 6000

def sh(cmd, **kw): return subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, **kw)

class Sandbox:
    def __init__(self):
        global WORK
        self.mode = "docker" if self._docker_ok() else "user"
        if self.mode == "user":                   # the agent's own home (bot folders above it are closed to it)
            WORK = f"/home/{USER}/work"
        os.makedirs(WORK, exist_ok=True)
        self.start()
    def _docker_ok(self):
        if os.environ.get("AGENT_SANDBOX") == "user": return False
        if not shutil.which("docker") or sh("docker info").returncode: return False
        if sh(f"docker image inspect {IMG}").returncode:
            r = sh(f"docker build -q -t {IMG} {os.path.join(os.path.dirname(__file__), 'sandbox')}", timeout=1800)
            if r.returncode: return False
        return True
    def start(self):
        if self.mode == "docker":
            if sh(f"docker ps -q -f name=^{CT}$").stdout.strip(): return
            sh(f"docker rm -f {CT}")
            r = sh(["docker", "run", "-d", "--name", CT, "--memory", "6g", "--cpus", "3", "-v", f"{WORK}:/work", "-v", f"{REPO}:/repo:ro", IMG])
            if r.returncode: raise RuntimeError("песочница docker: " + r.stderr[-300:])
            time.sleep(2)
        else:
            if sh(["id", USER]).returncode: sh(["useradd", "-m", "-s", "/bin/bash", USER])
            sh(["chown", "-R", f"{USER}:{USER}", f"/home/{USER}"])
            for link, target in (("/work", WORK), ("/repo", REPO)):          # same paths as in the docker sandbox
                if os.path.islink(link) and os.readlink(link) != target: os.remove(link)
                if not os.path.exists(link): os.symlink(target, link)
            sock = f"/tmp/.X11-unix/X{DISPLAY[1:]}"
            if not os.path.exists(sock):
                os.makedirs("/tmp/.X11-unix", exist_ok=True); os.chmod("/tmp/.X11-unix", 0o1777)
                subprocess.Popen(["runuser", "-u", USER, "--", "Xvfb", DISPLAY, "-screen", "0", "1600x900x24", "-nolisten", "tcp"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
                for _ in range(20):
                    time.sleep(0.3)
                    if os.path.exists(sock): break
                if not os.path.exists(sock): raise RuntimeError("экран песочницы (Xvfb) не запустился")
                for wm in ("fluxbox", "openbox"):
                    if shutil.which(wm):
                        subprocess.Popen(["runuser", "-u", USER, "--", "env", f"DISPLAY={DISPLAY}", wm], stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, start_new_session=True); break
    def run(self, cmd, timeout=300):
        if self.mode == "docker":
            p = ["docker", "exec", "-w", "/work", CT, "bash", "-lc", cmd]
        else:                                    # empty environment: no bot secrets reach the agent; the command goes via a
            f = os.path.join(WORK, ".cmd.sh")       # script file — no shell quoting, multi-line commands stay intact
            open(f, "w").write("cd /work\n" + cmd + "\n"); sh(["chown", f"{USER}:{USER}", f])
            p = ["runuser", "-u", USER, "--", "env", "-i", f"HOME=/work", "PATH=/usr/local/bin:/usr/bin:/bin", f"DISPLAY={DISPLAY}",
                 "LANG=C.UTF-8", "TERM=xterm", "bash", f]
        try:
            r = subprocess.run(p, capture_output=True, text=True, timeout=timeout)
            out = (r.stdout + ("\n[stderr]\n" + r.stderr if r.stderr.strip() else "")).strip()
            return f"[exit {r.returncode}]\n" + (out[:OUT_MAX // 2] + "\n…[обрезано]…\n" + out[-OUT_MAX // 2:] if len(out) > OUT_MAX else out)
        except subprocess.TimeoutExpired:
            return f"[timeout {timeout}s] команда не закончилась — запусти её в фоне (nohup … &) или уменьши задачу"
    def path(self, p):
        """agent paths: /work/… (read-write) and /repo/… (read-only); anything else is refused"""
        p = p.strip()
        if p.startswith("/repo"): real = os.path.normpath(os.path.join(REPO, p[5:].lstrip("/"))); base = REPO; ro = True
        else: real = os.path.normpath(os.path.join(WORK, p[5:].lstrip("/") if p.startswith("/work") else p.lstrip("/"))); base = WORK; ro = False
        if not (real == base or real.startswith(base + os.sep)): raise ValueError("путь вне /work и /repo")
        return real, ro
    def screenshot(self):
        f = os.path.join(WORK, f"screen_{int(time.time())}.png")
        if self.mode == "docker":
            self.run(f"import -window root /work/{os.path.basename(f)}", 60)
        else:
            sh(["runuser", "-u", USER, "--", "env", f"DISPLAY={DISPLAY}", "import", "-window", "root", f])
        return f if os.path.exists(f) else ""

TOOLS = [
    ("shell", "Выполнить bash-команду в своём Linux (папка /work; /repo — код бота только для чтения). GUI-программы и долгие процессы запускай "
              "ОТВЯЗАННЫМИ: `setsid xterm -fa Monospace -fs 24 -e '…' >/dev/null 2>&1 < /dev/null &` (иначе закроются вместе с командой); "
              "экран — DISPLAY уже задан. Многострочные команды можно.", {"cmd": {"type": "string"}, "timeout": {"type": "integer"}}),
    ("read_file", "Прочитать файл (строки с номерами)", {"path": {"type": "string"}, "start": {"type": "integer"}, "lines": {"type": "integer"}}),
    ("write_file", "Создать/перезаписать файл в /work", {"path": {"type": "string"}, "content": {"type": "string"}}),
    ("edit_file", "Точечная правка: заменить фрагмент old на new (old должен встречаться ровно 1 раз)", {"path": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}}),
    ("list_dir", "Список файлов в папке", {"path": {"type": "string"}}),
    ("search_code", "Поиск по коду (ripgrep): регулярка по файлам папки, с номерами строк", {"pattern": {"type": "string"}, "path": {"type": "string"}}),
    ("run_tests", "Прогнать тесты pytest (папка или файл), коротко: какие упали и почему", {"path": {"type": "string"}}),
    ("lint", "Линтер ruff: ошибки и подозрительный код (неиспользуемое, необъявленное, опасное)", {"path": {"type": "string"}}),
    ("browse", "Открыть сайт в браузере (Chromium): скриншот страницы (ты его увидишь) + текст. Можно кликнуть по тексту "
               "кнопки/ссылки или ввести текст в поле (CSS-селектор=>текст, затем Enter)", {"url": {"type": "string"}, "full_page": {"type": "boolean"},
               "click": {"type": "string"}, "type": {"type": "string"}}),
    ("screenshot", "Сделать скриншот своего экрана и посмотреть его", {}),
    ("view_image", "Посмотреть картинку из /work (результат рендера, график и т.п.)", {"path": {"type": "string"}}),
    ("send_to_owner", "Отправить владельцу файл из /work в Telegram (видео, картинку, документ)", {"path": {"type": "string"}, "caption": {"type": "string"}}),
    ("think", "Подумать вслух: план, гипотезы, почему не работает, что попробовать дальше (владельцу не отправляется)", {"thought": {"type": "string"}}),
    ("finish", "Задача решена (или невозможна): итог для владельца — что сделано, где результат, что проверено", {"summary": {"type": "string"}}),
]
def tool_defs(): return [{"type": "function", "function": {"name": n, "description": d, "parameters": {"type": "object", "properties": p}}} for n, d, p in TOOLS]

SYSTEM = """Ты — опытный инженер-программист и монтажёр, автономный агент со своим облачным Linux. Решаешь задачу владельца
YouTube-канала нарезок сам, шаг за шагом, ИНСТРУМЕНТАМИ (каждый ответ — вызов ровно одного инструмента).
Метод как у сильных инженеров: 0) чужой проект ПЕРЕД первой правкой — `git init -q && git add -A && git commit -qm base` (чтобы потом показать честный git diff); разберись в коде (list_dir, search_code, read_file); 1) think — план и гипотезы; 2) маленький шаг; 3) проверь результат (run_tests, lint, вывод, скриншот,
view_image); 4) ошибка → think: причина → исправь → проверь снова. Не выдумывай результатов: всё проверяй командой.
Пиши код в /work, запускай, тестируй. Для видео — ffmpeg (1920x1080 / 60 fps / H.264 / AAC 48k, если не сказано иное).
Нужны пакеты — ставь (pip install --user …, npm в /work). Долгое и окна — через `setsid … &`, потом проверяй (ps, скриншот). В конце — finish с итогом по-русски
и send_to_owner для готовых файлов. Никаких секретов/паролей не ищи и не трогай; /repo — только читать."""

class Agent:
    def __init__(self, task, say=None, send=None, max_steps=40, max_minutes=40):
        self.task, self.say, self.send = task, say or print, send or (lambda p, c: print("SEND", p, c))
        self.max_steps, self.deadline = max_steps, time.time() + max_minutes * 60
        self.stop = False; self.sb = Sandbox(); self.log = []
    def tool(self, name, a):
        sb = self.sb
        if name == "shell": return sb.run(a.get("cmd", ""), min(int(a.get("timeout") or 300), 1200))
        if name == "read_file":
            real, _ = sb.path(a["path"]); s0 = int(a.get("start") or 1); n = int(a.get("lines") or 200)
            L = open(real, encoding="utf-8", errors="replace").read().splitlines()
            return "\n".join(f"{i}: {l}" for i, l in enumerate(L[s0 - 1:s0 - 1 + n], s0)) + (f"\n… всего строк {len(L)}" if len(L) > s0 - 1 + n else "")
        if name == "write_file":
            real, ro = sb.path(a["path"])
            if ro: return "ошибка: /repo только для чтения — пиши в /work"
            os.makedirs(os.path.dirname(real), exist_ok=True); open(real, "w").write(a.get("content", ""))
            if sb.mode == "user": sh(["chown", f"{USER}:{USER}", real])
            return f"записано {len(a.get('content', ''))} символов в {a['path']}"
        if name == "edit_file":
            real, ro = sb.path(a["path"])
            if ro: return "ошибка: /repo только для чтения — скопируй файл в /work и правь там"
            s = open(real).read(); n = s.count(a.get("old", ""))
            if n != 1: return f"ошибка: фрагмент old найден {n} раз (нужно ровно 1) — уточни"
            open(real, "w").write(s.replace(a["old"], a.get("new", ""), 1)); return "правка применена"
        if name == "list_dir":
            real, _ = sb.path(a.get("path") or "/work")
            return "\n".join(sorted(f + ("/" if os.path.isdir(os.path.join(real, f)) else f"  ({os.path.getsize(os.path.join(real, f))} Б)") for f in os.listdir(real))[:200])
        if name == "search_code":
            return sb.run(f"rg -n --no-heading -S {json.dumps(a.get('pattern', ''))} {json.dumps(a.get('path') or '/work')} | head -80", 60)
        if name == "run_tests":
            return sb.run(f"cd {json.dumps(a.get('path') or '/work')} 2>/dev/null || true; python3 -m pytest -q -x --no-header -p no:cacheprovider "
                          f"{json.dumps(a.get('path') or '.')} 2>&1 | tail -60", 600)
        if name == "lint":
            return sb.run(f"python3 -m ruff check --no-cache --output-format concise {json.dumps(a.get('path') or '/work')} 2>&1 | tail -60", 120)
        if name == "browse":
            url = a.get("url", "")
            if not re.match(r"^https?://", url): return "нужен полный адрес: https://…"
            f = os.path.join(WORK, f"web_{int(time.time())}.png")
            cmd = ["node", os.path.join(REPO, "tools", "yt", "browse.js"), url, f] + (["--full"] if a.get("full_page") else []) + \
                  (["--click", a["click"]] if a.get("click") else []) + (["--type", a["type"]] if a.get("type") else [])
            r = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=150)
            try: d = json.loads(r.stdout.strip().splitlines()[-1])
            except Exception: return "браузер: " + (r.stderr or r.stdout)[-400:]
            if os.path.exists(f) and sb.mode == "user": sh(["chown", f"{USER}:{USER}", f])
            info = f"адрес: {d['url']}\nзаголовок: {d['title']}\n{('ошибка: ' + d['error']) if d.get('error') else ''}\nтекст страницы:\n{d['text'][:4000]}"
            return {"image": f, "text": info} if os.path.exists(f) else info
        if name in ("screenshot", "view_image"):
            f = sb.screenshot() if name == "screenshot" else sb.path(a["path"])[0]
            if not f or not os.path.exists(f): return "не получилось (экран не запущен или файла нет)"
            return {"image": f}
        if name == "send_to_owner":
            real, _ = sb.path(a["path"]); self.send(real, a.get("caption", "")); return "отправлено владельцу"
        if name == "think": return "ок"
        return "нет такого инструмента"
    def run(self):
        msgs = [{"role": "system", "content": SYSTEM + f"\nПесочница: {self.sb.mode}. Время на задачу: {int((self.deadline - time.time()) / 60)} мин."},
                {"role": "user", "content": self.task}]
        self.say(f"🧑‍💻 Агент взялся за задачу (Linux: {self.sb.mode}). Буду присылать ход работы; «стоп» — остановить.")
        for step in range(1, self.max_steps + 1):
            if self.stop or time.time() > self.deadline:
                self.say("⏹ Агент остановлен (стоп/время)."); return "stopped"
            msg = None
            for wait in (0, 20, 40, 60, 90, 120):               # free-tier per-minute limits: background work can wait
                time.sleep(wait)
                try:
                    msg = brain.ask(msgs, tools=tool_defs(), temperature=0.2, timeout=120,
                                    models=["gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3.5-flash", "gemma-4-31b-it"]); break
                except RuntimeError as e:
                    if wait == 120: self.say(f"⚠️ Мозг недоступен ({str(e)[:120]}) — агент остановлен, повтори позже."); return "brain"
            time.sleep(1.5)
            calls = msg.get("tool_calls") or []
            if not calls:                                         # the model answered in words → nudge it to act or finish
                msgs.append({"role": "assistant", "content": msg.get("content") or ""})
                msgs.append({"role": "user", "content": "Действуй инструментами. Если всё готово и проверено — вызови finish."}); continue
            c = calls[0]; name = c["function"]["name"]
            try: args = json.loads(c["function"].get("arguments") or "{}")
            except Exception: args = {}
            msgs.append({"role": "assistant", "content": None, "tool_calls": [c]})
            if name == "finish":
                self.say("✅ " + args.get("summary", "готово")); self.log.append(("finish", args)); return args.get("summary", "")
            try: res = self.tool(name, args)
            except Exception as e: res = f"ошибка инструмента: {e}"
            self.log.append((name, args, res if isinstance(res, str) else res))
            if isinstance(res, dict) and res.get("image"):               # let the model SEE the screen / image
                b64 = base64.b64encode(open(res["image"], "rb").read()).decode()
                msgs.append({"role": "tool", "tool_call_id": c.get("id", f"c{step}"), "content": (res.get("text", "") + "\n" if res.get("text") else "") + f"картинка {res['image'].replace(WORK, '/work')} — смотри следующий кадр"})
                msgs.append({"role": "user", "content": [{"type": "text", "text": "Вот изображение:"},
                                                         {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}}]})
            else:
                msgs.append({"role": "tool", "tool_call_id": c.get("id", f"c{step}"), "content": str(res)})
            if name == "think": self.say(f"💭 {args.get('thought', '')[:400]}") if step % 4 == 1 else None
            elif step % 5 == 0: self.say(f"🔧 шаг {step}: {name} {json.dumps(args, ensure_ascii=False)[:150]}")
            if len(msgs) > 60: msgs = msgs[:2] + msgs[-40:]                 # keep the context bounded
            while len(msgs) > 2 and msgs[2]["role"] == "tool": msgs.pop(2)
        self.say("⏹ Агент исчерпал шаги — пришлю, что успел."); return "steps"
