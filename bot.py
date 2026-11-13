import os
import json
import time
import asyncio
import html
import secrets
import base64
from datetime import datetime

import httpx
from bs4 import BeautifulSoup
import workspace as ws
import devtools
import bigfiles
from ddgs import DDGS
from telegram import (Update, InlineKeyboardButton, InlineKeyboardMarkup,
                      ReplyKeyboardMarkup, BotCommand)
from telegram.ext import (ApplicationBuilder, MessageHandler, CommandHandler,
                          CallbackQueryHandler, filters, ContextTypes)

TELEGRAM_TOKEN = os.environ["TELEGRAM_TOKEN"]

# ---------- Провайдеры ----------
# Подключаются те, для которых задан ключ в переменных окружения.
PROVIDERS = {
    "coa": {"name": "CoArena", "url": "https://coarena.ai/api/v1", "env": "COARENA_API_KEY"},
}
for _p in PROVIDERS.values():
    _p["key"] = os.environ.get(_p["env"], "")
if not any(p["key"] for p in PROVIDERS.values()):
    raise SystemExit("Не задан ключ провайдера (COARENA_API_KEY)")

# Модели и цепочки провайдеров: бот идёт по списку маршрутов, пока кто-то не ответит.
# vision=True — модель видит картинки (нужно для самопроверки скриншотов сайтов).
MODELS = {
    "deepseek-v4.1-flash": {
        "label": "🐋 DeepSeek V4.1 Flash", "identity": ("DeepSeek V4.1 Flash", "DeepSeek"),
        "vision": True, "routes": [("coa", "deepseek-v4-1-flash-1017")]},
    "gemini-flash": {
        "label": "✨ Gemini 3.8 Flash", "identity": ("Gemini 3.8 Flash", "Google"),
        "vision": True, "routes": [("coa", "gemini-3-8-flash")]},
    "qwen3.8": {
        "label": "🐉 Qwen 3.8", "identity": ("Qwen 3.8", "Alibaba"),
        "vision": True, "routes": [("coa", "qwen3-8-2-4t-a95b")]},
    "glm-5.3-flash": {
        "label": "🧩 GLM 5.3 Flash", "identity": ("GLM 5.3 Flash", "Z.ai"),
        "vision": True, "routes": [("coa", "glm-5-3-flash")]},
    "kimi-k3": {
        "label": "🌙 Kimi K3", "identity": ("Kimi K3", "Moonshot AI"),
        "routes": [("coa", "kimi-k3")]},
    "gpt-6-luna": {
        "label": "🟢 GPT-6 Luna", "identity": ("GPT-6 Luna", "OpenAI"),
        "routes": [("coa", "gpt-6-luna")]},
    "grok-4.7": {
        "label": "⚡ Grok 4.7", "identity": ("Grok 4.7", "xAI"),
        "routes": [("coa", "grok-4-7")]},
}
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "deepseek-v4.1-flash")
# Если у выбранной модели все каналы исчерпаны — используем цепочку DeepSeek V4.1

# Провайдер упёрся в лимит → не трогаем его до этого времени (не тратим время на отказы)
cooldown_until = {}      # (provider_id, model) -> unix time
MAX_COOLDOWN = 6 * 3600
busy_strikes = {}        # route -> сколько раз подряд поставщики были заняты
cooldown_reason = {}     # route -> "limit" | "busy"
BUSY_BASE = 30           # пауза между попытками при перегрузке/ошибке поставщика — всегда 30 с

user_models = {}         # user_id -> выбранная модель

MAX_TOOL_ROUNDS = 12     # сколько шагов с инструментами (поиск, файлы, API) за один ответ
EDIT_INTERVAL = 2.5      # как часто обновлять сообщение «Думаю…» (сек)
TG_LIMIT = 4000          # лимит длины сообщения Telegram (с запасом)

chat_histories = {}      # user_id -> история диалога (кэш; на диске — data/users/<id>/history.json)


def get_history(user_id: int) -> list:
    if user_id not in chat_histories:
        chat_histories[user_id] = ws.load_history(user_id)
    return chat_histories[user_id]


def forget_history(user_id: int):
    chat_histories.pop(user_id, None)
    ws.clear_history(user_id)

# Инструкции хранятся на диске, чтобы пережить перезапуск/сон сервера
DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
INSTRUCTIONS_FILE = os.path.join(DATA_DIR, "instructions.json")
MAX_INSTRUCTIONS = 10
MAX_INSTRUCTION_CHARS = 300000   # одна инструкция (проверено: 116 тыс. символов DeepSeek читает целиком)
MAX_INSTRUCTIONS_TOTAL = 400000  # все инструкции вместе — они уходят с каждым сообщением
pending_files = {}       # token -> присланный .md, ждущий выбора


def load_instructions():
    try:
        with open(INSTRUCTIONS_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_instructions():
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = INSTRUCTIONS_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(instructions, f, ensure_ascii=False)
    os.chmod(tmp, 0o600)
    os.replace(tmp, INSTRUCTIONS_FILE)


instructions = load_instructions()   # str(user_id) -> [{id, name, text, added}]


def md_escape(t: str) -> str:
    for ch in ("_", "*", "`", "["):
        t = t.replace(ch, "\\" + ch)
    return t
show_thoughts = {}       # user_id -> показывать ли размышления (по умолчанию да)
web_enabled = {}         # user_id -> разрешён ли интернет (по умолчанию да)
user_locks = {}          # user_id -> asyncio.Lock, чтобы ответы шли по очереди

SYSTEM_PROMPT = """Ты — {model_name}. Если спросят, какая ты модель или кто тебя создал, отвечай именно так: {model_name} от компании {vendor}. Не называй себя ChatGPT, Claude или другими моделями.
Ты полезный ассистент. Отвечай на русском языке.
Сегодня {today}.
{web_note}
Используй Telegram Markdown форматирование редко и уместно:
- *жирный* — только для ключевых терминов или важных выводов
- _курсив_ — для примеров или лёгких акцентов
Не злоупотребляй — большинство текста должно быть обычным."""

WEB_NOTE_ON = ("У тебя есть доступ к интернету через инструменты web_search и open_url. "
               "Используй их, когда вопрос касается свежих событий, цен, курсов, погоды, "
               "новостей или фактов, в которых ты не уверен. Указывай источники (ссылки). "
               "Для простых вопросов интернет не нужен.")
WEB_NOTE_OFF = "Доступ к интернету выключен."

TOOLS = [
    {"type": "function", "function": {
        "name": "web_search",
        "description": "Поиск в интернете. Возвращает список результатов: заголовок, ссылка, краткий фрагмент.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "Поисковый запрос"}},
            "required": ["query"]}}},
    {"type": "function", "function": {
        "name": "open_url",
        "description": "Открыть веб-страницу и получить её текст.",
        "parameters": {"type": "object", "properties": {
            "url": {"type": "string", "description": "Адрес страницы (http/https)"}},
            "required": ["url"]}}},
]

CLEAR_TEXT = "🗑 Новый диалог"
MODEL_TEXT = "🤖 Сменить ИИ"
INSTR_TEXT = "📌 Инструкции"
FILES_TEXT = "📁 Файлы"
API_TEXT = "🔌 API"


# ---------- Интернет ----------

def _web_search_sync(query: str) -> str:
    last_err = None
    for backend in ("auto", "google", "duckduckgo", "bing", "brave"):
        try:
            results = DDGS().text(query, max_results=6, backend=backend)
            if results:
                return "\n\n".join(
                    f"{i}. {r.get('title', '')}\n{r.get('href', '')}\n{r.get('body', '')}"
                    for i, r in enumerate(results, 1))
        except Exception as e:
            last_err = e
    return f"Поиск не дал результатов ({last_err or 'пусто'})."


async def web_search(query: str) -> str:
    return await asyncio.to_thread(_web_search_sync, query)


async def open_url(url: str) -> str:
    if not url.startswith(("http://", "https://")):
        return "Некорректный адрес."
    try:
        async with httpx.AsyncClient(follow_redirects=True, timeout=20,
                                     headers={"User-Agent": "Mozilla/5.0"}) as client:
            r = await client.get(url)
        soup = BeautifulSoup(r.text, "html.parser")
        for tag in soup(["script", "style", "noscript", "header", "footer", "nav", "svg"]):
            tag.decompose()
        text = " ".join(soup.get_text(" ").split())
        return text[:8000] or "Страница пустая."
    except Exception as e:
        return f"Не удалось открыть страницу: {e}"


async def run_tool(name: str, args: dict) -> str:
    if name == "web_search":
        return await web_search(args.get("query", ""))
    if name == "open_url":
        return await open_url(args.get("url", ""))
    return "Неизвестный инструмент."


# ---------- Клавиатуры ----------

def clear_button():
    return InlineKeyboardMarkup([[
        InlineKeyboardButton("🗑 Очистить диалог", callback_data="clear")
    ]])


def main_keyboard():
    return ReplyKeyboardMarkup([[CLEAR_TEXT, MODEL_TEXT], [INSTR_TEXT, FILES_TEXT, API_TEXT]], resize_keyboard=True, is_persistent=True)


# ---------- Живой статус ----------

class LiveStatus:
    """Сообщение «Думаю…», которое обновляется по ходу работы модели."""

    def __init__(self, message, show_thoughts: bool):
        self.msg = message
        self.markup = None       # кнопка «🗑 Удалить запрос» во время ожидания лимита
        self.show = show_thoughts
        self.started = time.time()
        self.stage = "⏳ Думаю…"
        self.reasoning = ""
        self.actions = []
        self.last_text = ""
        self.last_edit = 0.0

    def render(self) -> str:
        secs = int(time.time() - self.started)
        parts = [f"<b>{html.escape(self.stage)}</b> · {secs} с"]
        todos = getattr(self, "todos", None)
        if todos:
            icon = {"completed": "✅", "in_progress": "🔄"}
            done = sum(t.get("status") == "completed" for t in todos)
            parts.append(f"📋 План {done}/{len(todos)}:")
            for t in todos[:12]:
                parts.append(f"{icon.get(t.get('status'), '⬜')} {html.escape(str(t.get('content'))[:80])}")
        for a in self.actions[-4:]:
            parts.append(html.escape(a))
        if self.show and self.reasoning.strip():
            tail = self.reasoning.strip()[-1500:]
            parts.append(f"<blockquote>💭 …{html.escape(tail)}</blockquote>")
        return "\n".join(parts)

    async def update(self, force=False):
        now = time.time()
        if not force and now - self.last_edit < EDIT_INTERVAL:
            return
        text = self.render()
        if text == self.last_text:
            return
        self.last_edit = now
        self.last_text = text
        try:
            await self.msg.edit_text(text, parse_mode="HTML", reply_markup=self.markup)
        except Exception:
            pass

    async def ticker(self):
        # Обновляем счётчик секунд, даже пока сервер молчит
        while True:
            await asyncio.sleep(EDIT_INTERVAL)
            await self.update()


# ---------- Модель ----------

def route_label(route):
    return next((v["label"] for v in MODELS.values() if route in v["routes"]), route[1])




def _fmt_left(sec: int) -> str:
    h, rem = divmod(max(0, sec), 3600)
    return f"{h}:{rem // 60:02d}:{rem % 60:02d}" if h else f"{rem // 60}:{rem % 60:02d}"


async def countdown(status, model_key, wait, reason="limit", user_id=None):
    """Обратный отсчёт до следующей попытки. Прерывается, если пользователь сменил модель."""
    end = time.time() + wait
    label = MODELS[model_key]["label"]
    status.reasoning = ""
    if getattr(status, "turn_id", None):
        status.markup = turn_kb(status.turn_id)
    while True:
        left = int(end - time.time() + 0.999)
        if left <= 0:
            break
        if user_id is not None and user_models.get(user_id, DEFAULT_MODEL) != model_key:
            break                                   # выбрали другую модель — пробуем её сразу
        t = _fmt_left(left)
        if reason == "busy":
            status.stage = f"⏳ {label} сейчас перегружен у поставщика. Пробую снова через {t}"
        elif reason == "error":
            status.stage = f"⚠️ Поставщик {label} ответил ошибкой. Пробую снова через {t}"
        else:
            status.stage = f"⏳ Лимит {label}: 1 запрос в минуту. Отвечу через {t}"
        await status.update(force=True)
        await asyncio.sleep(min(3, max(0.5, end - time.time())))
    status.stage = "⏳ Думаю…"
    status.markup = None
    await status.update(force=True)


class RetryableError(Exception):
    pass


class FatalError(Exception):
    """Ошибка, которую повтором не исправить: неверный ключ, кончился баланс/квота."""


async def stream_completion(client, route, messages, status: LiveStatus, tools):
    """Один запрос к модели со стримингом. Возвращает (content, reasoning, tool_calls)."""
    pid, model = route
    prov = PROVIDERS[pid]
    payload = {
        "model": model,
        "messages": messages,
        "max_tokens": 16384,
        "temperature": 1.0,
        "top_p": 1.0,
        "stream": True,
        **prov.get("extra", {}),
    }
    if tools:
        payload["tools"] = tools

    content, reasoning = "", ""
    tool_calls = {}
    async with client.stream("POST", prov["url"] + "/chat/completions", json=payload, headers={
        "Authorization": f"Bearer {prov['key']}",
        "Accept": "text/event-stream",
    }) as resp:
        if resp.status_code != 200:
            body = (await resp.aread()).decode("utf-8", "replace")
            low = body.lower()
            if resp.status_code == 401 or any(w in low for w in
                    ("insufficient", "balance", "no credit", "out of credit", "quota exceeded",
                     "недостаточно", "баланс", "payment required", "top up", "пополните")):
                raise FatalError(f"{resp.status_code}: {body[:200]}")
            if resp.status_code in (502, 503) and ("busy" in low or "get_channel_failed" in low
                                                    or "rate limit" in low or "overload" in low):
                # поставщики бесплатной модели перегружены — ждём с растущей паузой
                n = busy_strikes[route] = busy_strikes.get(route, 0) + 1
                cooldown_until[route] = time.time() + BUSY_BASE      # постоянная пауза между попытками
                cooldown_reason[route] = "busy"
                raise RetryableError(f"busy {resp.status_code}")
            if resp.status_code == 403 and "consent" in body.lower():
                cooldown_until[route] = time.time() + 600   # нужно согласие на сайте — проверим снова через 10 мин
            if resp.status_code == 403 and "limit" in body.lower():
                cooldown_until[route] = time.time() + 60
            if resp.status_code in (402, 429):
                # лимит: запоминаем, сколько ждать (Retry-After), но не дольше MAX_COOLDOWN
                try:
                    wait = int(float(resp.headers.get("retry-after", "60")))
                except ValueError:
                    wait = 60
                cooldown_until[route] = time.time() + min(max(wait, 30), MAX_COOLDOWN)
                cooldown_reason[route] = "limit"
            raise RetryableError(f"{resp.status_code}: {body[:150]}")
        busy_strikes.pop(route, None)
        cooldown_reason.pop(route, None)
        async for line in resp.aiter_lines():
            if not line.startswith("data: ") or line == "data: [DONE]":
                continue
            try:
                delta = json.loads(line[6:])["choices"][0]["delta"]
            except Exception:
                continue
            r = delta.get("reasoning_content") or delta.get("reasoning") or ""
            if r:
                reasoning += r
                status.stage = "🧠 Размышляю…"
                status.reasoning = reasoning
            c = delta.get("content") or ""
            if c:
                content += c
                status.stage = "✍️ Пишу ответ…"
            for tc in delta.get("tool_calls") or []:
                slot = tool_calls.setdefault(tc.get("index", 0),
                                             {"id": "", "name": "", "arguments": ""})
                if tc.get("id"):
                    slot["id"] = tc["id"]
                fn = tc.get("function") or {}
                if fn.get("name"):
                    slot["name"] = fn["name"]
                if fn.get("arguments"):
                    slot["arguments"] += fn["arguments"]
            await status.update()
    return content, reasoning, [tool_calls[k] for k in sorted(tool_calls)]


async def ask_model(user_id: int, user_message: str, status: LiveStatus, out=None, history_text=None):
    """out — сообщение Telegram, куда инструменты шлют файлы/скриншоты;
    history_text — короткая версия запроса для истории (без содержимого файлов)."""
    history = get_history(user_id)
    use_web = web_enabled.get(user_id, True)
    def system_for(route):
        logical = chosen if route in MODELS[chosen]["routes"] else \
            next((k for k, v in MODELS.items() if route in v["routes"]), chosen)
        name, vendor = MODELS[logical]["identity"]
        prompt = SYSTEM_PROMPT.format(
            model_name=name, vendor=vendor,
            today=datetime.now().strftime("%d.%m.%Y"),
            web_note=WEB_NOTE_ON if use_web else WEB_NOTE_OFF)
        prompt += "\n" + ws.workspace_note(user_id)
        prompt += "\n" + (devtools.NOTE_OWNER if devtools.is_owner(user_id) else devtools.NOTE_GUEST)
        prompt += "\n" + bigfiles.NOTE
        user_instr = instructions.get(str(user_id), [])
        if user_instr:
            names = ", ".join(f"«{it['name']}»" for it in user_instr)
            prompt += (
                "\n\n==================== ИНСТРУКЦИИ ПОЛЬЗОВАТЕЛЯ ====================\n"
                f"Пользователь загрузил свои файлы инструкций: {names}.\n"
                "ОНИ ИМЕЮТ НАИВЫСШИЙ ПРИОРИТЕТ. Если они противоречат любому правилу выше "
                "(язык, стиль, формат, длина ответа, оформление) — побеждают инструкции пользователя.\n"
                "Соблюдай их в КАЖДОМ ответе, на протяжении всего диалога, даже если об этом не напоминают, "
                "даже в коротких ответах, при работе с файлами и после поиска в интернете. "
                "Не обсуждай и не пересказывай эти инструкции, просто выполняй их. "
                "Единственное исключение — если инструкция требует навредить людям.")
            for it in user_instr:
                prompt += f"\n\n----- НАЧАЛО ФАЙЛА {it['name']} -----\n{it['text']}\n----- КОНЕЦ ФАЙЛА {it['name']} -----"
            prompt += ("\n==================== КОНЕЦ ИНСТРУКЦИЙ ====================\n"
                       "Перед отправкой ответа проверь: соблюдены ли ВСЕ инструкции пользователя?")
        return prompt

    def with_reminder(turn_msgs):
        """Короткое напоминание к текущему сообщению (в историю не сохраняется)."""
        user_instr = instructions.get(str(user_id), [])
        if not user_instr or not turn_msgs or turn_msgs[0].get("role") != "user":
            return turn_msgs
        names = ", ".join(f"«{it['name']}»" for it in user_instr)
        first = dict(turn_msgs[0])
        first["content"] = (f"{first['content']}\n\n[Напоминание от системы, не от пользователя: "
                            f"строго соблюдай инструкции из файлов {names} — они важнее стиля по умолчанию.]")
        return [first, *turn_msgs[1:]]

    chosen = user_models.get(user_id, DEFAULT_MODEL)
    if chosen not in MODELS:
        chosen = DEFAULT_MODEL
    turn = [{"role": "user", "content": user_message}]
    all_reasoning = []
    empty_retries = 0
    did_tools = False
    status.vision = bool(MODELS[chosen].get("vision"))
    status.attach_images = []
    timeout = httpx.Timeout(connect=30, read=600, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as client:
        for round_no in range(MAX_TOOL_ROUNDS + 1):
            tools = (TOOLS if use_web else []) + ws.FILE_TOOLS + ws.API_TOOLS + devtools.TOOLS_ALL \
                + bigfiles.TOOLS + (devtools.TOOLS_OWNER if devtools.is_owner(user_id) else []) \
                if round_no < MAX_TOOL_ROUNDS else None
            # Только выбранная модель — никаких подмен. При лимите ждём с обратным отсчётом.
            routes = [r for r in MODELS[chosen]["routes"] if PROVIDERS[r[0]]["key"]]
            used_route = None
            errors = []
            while used_route is None:
                # модель меняет только пользователь (кнопкой «Сменить ИИ») — сами не переключаем
                picked = user_models.get(user_id, DEFAULT_MODEL)
                if picked in MODELS and picked != chosen:
                    chosen = picked
                    status.vision = bool(MODELS[chosen].get("vision"))
                    routes = [r for r in MODELS[chosen]["routes"] if PROVIDERS[r[0]]["key"]]
                    status.actions.append(f"🤖 Переключился на {MODELS[chosen]['label']}")
                now = time.time()
                ready = [r for r in routes if cooldown_until.get(r, 0) <= now]
                if not ready:
                    # ждём и пробуем снова — сколько угодно раз; отменить можно кнопкой «Удалить запрос»
                    wait = min(cooldown_until[r] for r in routes) - now
                    soon = min(routes, key=lambda r: cooldown_until.get(r, 0))
                    await countdown(status, chosen, wait, cooldown_reason.get(soon, "limit"), user_id)
                    continue
                for route in ready:
                    messages = [{"role": "system", "content": system_for(route)}, *history, *with_reminder(turn)]
                    try:
                        content, reasoning, calls = await stream_completion(
                            client, route, messages, status, tools)
                        used_route = route
                        break
                    except FatalError as e:
                        raise RuntimeError(
                            "⚠️ Провайдер CoArena отклонил запрос: " + str(e)[:200]
                            + "\nПохоже, кончился баланс или неверный ключ. Проверь аккаунт coarena.ai.")
                    except httpx.TransportError as e:
                        errors.append(f"{type(e).__name__}")
                        cooldown_until[route] = time.time() + 5     # сетевой сбой — короткая пауза
                    except RetryableError as e:
                        if str(e).startswith("busy"):
                            continue          # перегрузка поставщика — ждём с отсчётом, это не «ошибка»
                        errors.append(str(e)[:150])
                        if route not in cooldown_until or cooldown_until[route] <= time.time():
                            # непонятная ошибка — растущая пауза, но не сдаёмся
                            busy_strikes[route] = busy_strikes.get(route, 0) + 1
                            cooldown_until[route] = time.time() + BUSY_BASE
                            cooldown_reason[route] = "error"
            if reasoning.strip():
                all_reasoning.append(reasoning.strip())

            if not calls:
                if not content.strip():
                    # Пустой ответ (часто после долгого анализа) — не теряем работу,
                    # а просим модель сформулировать итог. До 2 попыток.
                    if empty_retries < 2:
                        empty_retries += 1
                        status.stage = "🧠 Формулирую ответ…"
                        await status.update(force=True)
                        turn.append({"role": "user", "content":
                            "Данных достаточно. Дай пользователю понятный итоговый ответ на его запрос "
                            "на основе уже собранного (не вызывай больше инструменты, если не обязательно)."})
                        continue
                    if did_tools:
                        content = ("Я изучил файл, но не смог коротко сформулировать вывод. "
                                   "Задай уточняющий вопрос — например, про конкретные функции, "
                                   "строки или участок кода, и я отвечу по нему.")
                    else:
                        raise RuntimeError("Модель вернула пустой ответ, попробуй ещё раз.")
                # в историю — коротко: запрос (без содержимого файлов) и итоговый ответ
                history.append({"role": "user", "content": history_text or user_message})
                done = [a for a in status.actions if a.startswith(("✏️", "📤", "🗑", "📸", "🔌"))]
                history.append({"role": "assistant", "content": content + (
                    "\n\n[Выполнено: " + "; ".join(done) + "]" if done else "")})
                ws.save_history(user_id, history)
                return content, "\n\n".join(all_reasoning), used_route

            # Модель хочет воспользоваться инструментами
            did_tools = True
            for i, c in enumerate(calls):
                c["id"] = c["id"] or f"call_{round_no}_{i}"
            turn.append({
                "role": "assistant",
                "content": content or "",
                "reasoning_content": reasoning,
                "tool_calls": [{"id": c["id"], "type": "function",
                                "function": {"name": c["name"], "arguments": c["arguments"] or "{}"}}
                               for c in calls],
            })
            for c in calls:
                try:
                    args = json.loads(c["arguments"] or "{}")
                except Exception:
                    args = {}
                if c["name"] in ("web_search", "open_url"):
                    if c["name"] == "web_search":
                        status.actions.append(f"🔎 Ищу: {args.get('query', '')}")
                    else:
                        status.actions.append(f"🌐 Открываю: {args.get('url', '')[:80]}")
                    status.stage = "🌐 Работаю с интернетом…"
                    await status.update(force=True)
                    result = await run_tool(c["name"], args)
                else:
                    if c["name"] == "read_file":
                        status.actions.append(f"📄 Читаю {args.get('name', '')}")
                    status.stage = "🛠 Работаю с файлами и API…"
                    await status.update(force=True)
                    if c["name"] in bigfiles.TOOL_NAMES:
                        try:
                            result = await bigfiles.run_tool(user_id, c["name"], args, out, status)
                        except FileNotFoundError:
                            result = f"Файла {args.get('name')} нет. Посмотри list_files."
                        except (ValueError, KeyError) as e:
                            result = f"Ошибка: {e}"
                    elif c["name"] in devtools.TOOL_NAMES:
                        try:
                            result = await devtools.run_tool(user_id, c["name"], args, out, status)
                        except FileNotFoundError:
                            result = f"Файла {args.get('name') or args.get('a')} нет. Посмотри list_files."
                        except (ValueError, KeyError) as e:
                            result = f"Ошибка: {e}"
                    else:
                        result = await ws.run_file_or_api_tool(user_id, c["name"], args, out, status)
                turn.append({"role": "tool", "tool_call_id": c["id"], "content": result})
            if status.attach_images:
                # показываем модели сделанные снимки, чтобы она проверила их перед отправкой
                parts = [{"type": "text", "text": "[Сделанные снимки для проверки: "
                          + ", ".join(i for i, _ in status.attach_images) + "]"}]
                for shot_id, jpeg in status.attach_images:
                    parts.append({"type": "text", "text": f"Снимок {shot_id}:"})
                    parts.append({"type": "image_url", "image_url": {
                        "url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}})
                turn.append({"role": "user", "content": parts})
                status.attach_images = []
                status.actions.append("👀 Проверяю снимок")
            status.stage = "🧠 Думаю дальше…"
            status.reasoning = ""
    # исчерпали лимит шагов — не падаем, а отдаём что есть
    history.append({"role": "user", "content": history_text or user_message})
    tail = content.strip() if content.strip() else \
        "Задача оказалась слишком большой за один раз — задай её по частям, и я продолжу."
    history.append({"role": "assistant", "content": tail})
    ws.save_history(user_id, history)
    return tail, "\n\n".join(all_reasoning), used_route


# ---------- Отправка ----------

def split_text(text: str, limit: int = TG_LIMIT):
    chunks = []
    while len(text) > limit:
        cut = text.rfind("\n", 0, limit)
        if cut < limit // 2:
            cut = limit
        chunks.append(text[:cut])
        text = text[cut:].lstrip("\n")
    if text:
        chunks.append(text)
    return chunks


async def send_answer(message, answer: str):
    # Telegram Markdown понимает *жирный*, а модели часто пишут **жирный**
    answer = answer.replace("**", "*").replace("__", "_")
    chunks = split_text(answer)
    for i, chunk in enumerate(chunks):
        markup = clear_button() if i == len(chunks) - 1 else None
        try:
            await message.reply_text(chunk, parse_mode="Markdown", reply_markup=markup,
                                     disable_web_page_preview=True)
        except Exception:
            # Markdown от модели может быть невалидным для Telegram — шлём как обычный текст
            await message.reply_text(chunk, reply_markup=markup, disable_web_page_preview=True)


async def send_thoughts(message, reasoning: str, seconds: int):
    if len(reasoning) > 3500:
        reasoning = "…" + reasoning[-3500:]
    text = (f"💭 <b>Размышления</b> ({seconds} с):\n"
            f"<blockquote expandable>{html.escape(reasoning)}</blockquote>")
    try:
        await message.reply_text(text, parse_mode="HTML")
    except Exception:
        pass


# ---------- Обработчики ----------

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.message.from_user.id
    user_text = update.message.text
    if user_text == CLEAR_TEXT:
        await reset_dialog(update, context)
        return
    if user_text == MODEL_TEXT:
        await choose_model(update, context)
        return
    if user_text == INSTR_TEXT:
        await show_instructions(update, context)
        return
    if user_text == FILES_TEXT:
        await show_files(update, context)
        return
    if user_text == API_TEXT:
        await api_command(update, context)
        return

    await run_turn(update.message, user_id, user_text)


user_epoch = {}          # user_id -> номер «эпохи»; очистка диалога его увеличивает
active_turns = {}        # user_id -> задачи, которые сейчас отвечают или ждут очереди


def stop_user_turns(user_id: int) -> int:
    """Останавливает текущий ответ и выбрасывает очередь. Возвращает, сколько задач остановлено."""
    user_epoch[user_id] = user_epoch.get(user_id, 0) + 1
    me = asyncio.current_task()
    tasks = [t for t in active_turns.get(user_id, set()) if t is not me and not t.done()]
    for t in tasks:
        t.cancel()
    return len(tasks)


def turn_kb(turn_id: str):
    return InlineKeyboardMarkup([[InlineKeyboardButton("🤖 Сменить ИИ", callback_data=f"swm:{turn_id}"),
                                  InlineKeyboardButton("🗑 Удалить запрос", callback_data=f"rmq:{turn_id}")]])


remove_request_kb = turn_kb


async def switch_model_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Кнопка под отсчётом: показать выбор модели. Ожидающий запрос продолжится на выбранной."""
    query = update.callback_query
    await query.answer()
    current = user_models.get(query.from_user.id, DEFAULT_MODEL)
    await query.message.reply_text(
        f"🤖 Сейчас: *{MODELS.get(current, MODELS[DEFAULT_MODEL])['label']}*\n"
        "Выбери модель — ожидающий запрос сразу продолжится на ней:",
        parse_mode="Markdown", reply_markup=models_keyboard(current))


turns = {}               # turn_id -> {"user", "task", "user_msg", "bot_msgs", "removed"}


async def remove_request_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Кнопка «🗑 Удалить запрос»: останавливает этот запрос и стирает его из чата."""
    query = update.callback_query
    t = turns.get(query.data.split(":", 1)[1])
    if not t or t["user"] != query.from_user.id or t["task"].done():
        await query.answer("Этот запрос уже обработан")
        return
    t["removed"] = True
    t["task"].cancel()
    await query.answer("Запрос удалён")
    for m in [*([t["user_msg"]] if t["user_msg"] else []), *t["bot_msgs"]]:
        try:
            await m.delete()
        except Exception:
            pass


async def run_turn(message, user_id: int, user_text: str, history_text=None):
    epoch = user_epoch.get(user_id, 0)
    task = asyncio.current_task()
    active_turns.setdefault(user_id, set()).add(task)
    turn_id = secrets.token_hex(4)
    own_msg = getattr(getattr(message, "from_user", None), "id", None) == user_id
    turn = turns[turn_id] = {"user": user_id, "task": task, "removed": False, "bot_msgs": [],
                             "user_msg": message if own_msg else None}
    status_msg = ticker = None
    try:
        lock = user_locks.setdefault(user_id, asyncio.Lock())
        if lock.locked():
            notice = await message.reply_text("⏳ Ещё отвечаю на прошлое сообщение — это встанет следом.",
                                              reply_markup=remove_request_kb(turn_id))
            turn["bot_msgs"].append(notice)
        async with lock:
            for m in list(turn["bot_msgs"]):       # очередь дошла — уведомление больше не нужно
                try:
                    await m.delete()
                except Exception:
                    pass
            turn["bot_msgs"].clear()
            if user_epoch.get(user_id, 0) != epoch:
                return                      # пока ждали очереди, диалог очистили — пропускаем
            show = show_thoughts.get(user_id, True)
            status_msg = await message.reply_text("⏳ Думаю…")
            turn["bot_msgs"].append(status_msg)
            status = LiveStatus(status_msg, show)
            status.turn_id = turn_id
            ticker = asyncio.create_task(status.ticker())
            try:
                answer, reasoning, used_route = await ask_model(user_id, user_text, status, out=message, history_text=history_text)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                ticker.cancel()
                try:
                    await status_msg.edit_text(f"⚠️ {e}")
                except Exception:
                    pass
                return
            ticker.cancel()
            if user_epoch.get(user_id, 0) != epoch:
                return                      # ответ пришёл уже после очистки — не показываем
            seconds = int(time.time() - status.started)
            try:
                await status_msg.delete()
            except Exception:
                pass
            if show and reasoning:
                await send_thoughts(message, reasoning, seconds)
            await send_answer(message, answer + f"\n\n_— {route_label(used_route)}_")
    except asyncio.CancelledError:
        # остановлено очисткой диалога или кнопкой «Удалить запрос»
        if ticker:
            ticker.cancel()
        if status_msg and not turn["removed"]:
            try:
                await status_msg.edit_text("🛑 Остановлено — диалог очищен")
            except Exception:
                pass
    finally:
        active_turns.get(user_id, set()).discard(task)
        turns.pop(turn_id, None)
        ws.pending_shots.pop(user_id, None)


# ---------- Файлы .txt ----------

MAX_FILE_BYTES = 2 * 1024 * 1024     # Telegram-бот может скачать до 20 МБ, нам хватит 2 МБ
MAX_FILE_CHARS = 300000              # сколько символов файла отдаём модели


def decode_text(data: bytes) -> str:
    for enc in ("utf-8-sig", "utf-16") if data[:2] in (b"\xff\xfe", b"\xfe\xff") else ("utf-8-sig",):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            pass
    try:
        return data.decode("cp1251")       # русские файлы из Windows
    except UnicodeDecodeError:
        return data.decode("latin-1", errors="replace")


def file_prompt(name: str, size: int, text: str, question: str) -> str:
    note = ""
    if len(text) > MAX_FILE_CHARS:
        note = (f"\n[Файл обрезан: показаны первые {MAX_FILE_CHARS} из {len(text)} символов. "
                f"Если нужно — скажи пользователю, что видишь только начало.]")
        text = text[:MAX_FILE_CHARS]
    question = question or "Кратко перескажи, что в этом файле, и выдели главное."
    return (f"Пользователь прислал файл «{name}» ({size} байт). Его содержимое:\n"
            f"<<<ФАЙЛ\n{text}\nФАЙЛ>>>{note}\n\nЗапрос пользователя: {question}")


async def handle_document(update: Update, context: ContextTypes.DEFAULT_TYPE):
    doc = update.message.document
    name = doc.file_name or "file.txt"
    low = name.lower()
    is_md = low.endswith((".md", ".markdown")) or doc.mime_type == "text/markdown"
    is_zip = low.endswith(".zip")
    limit = 20 * 1024 * 1024                 # предел Telegram для ботов
    if doc.file_size and doc.file_size > limit:
        await update.message.reply_text(f"📄 Файл слишком большой — максимум {limit // 1024 // 1024} МБ.")
        return
    tg_file = await doc.get_file()
    data = bytes(await tg_file.download_as_bytearray())
    user_id = update.message.from_user.id
    caption = (update.message.caption or "").strip()
    if not is_md:
        await add_to_batch(update.message, user_id, name, data, caption, is_zip)
        return
    text = decode_text(data).replace("\r\n", "\n").strip()
    if not text:
        await update.message.reply_text("📄 Файл пустой.")
        return
    # .md — спрашиваем, что сделать
    token = secrets.token_hex(4)
    pending_files[token] = {"user": user_id, "name": name, "text": text,
                            "size": len(data), "caption": caption, "ts": time.time()}
    for t in [t for t, f in pending_files.items() if time.time() - f["ts"] > 3600]:
        pending_files.pop(t, None)          # старые незавершённые выборы
    kb = InlineKeyboardMarkup([
        [InlineKeyboardButton("📌 Добавить в инструкции", callback_data=f"mdadd:{token}")],
        [InlineKeyboardButton("🔍 Проанализировать файл", callback_data=f"mdana:{token}")],
    ])
    await update.message.reply_text(
        f"📄 *{md_escape(name)}* — что с ним сделать?", parse_mode="Markdown", reply_markup=kb)


# ---------- Пакеты файлов (скрипты, архивы) ----------

BATCH_DELAY = 2.5            # ждём, пока придут все файлы, отправленные разом
pending_batches = {}         # user_id -> {"message", "files": [...], "notes": [...], "caption", "task"}


async def add_to_batch(message, user_id, name, data, caption, is_zip):
    b = pending_batches.setdefault(user_id, {"message": message, "files": [], "notes": [], "caption": ""})
    b["message"] = message
    if caption:
        b["caption"] = (b["caption"] + "\n" + caption).strip()
    try:
        if is_zip:
            saved, skipped = ws.extract_zip(user_id, data)
            b["files"] += saved
            b["notes"].append(f"Архив {name}: распаковано {len(saved)} файлов"
                              + (f"; пропущено: {', '.join(skipped[:10])}" if skipped else ""))
        else:
            b["files"].append(ws.save_file(user_id, name, data))     # текст и двоичные (.so и т.п.)
    except (ValueError, Exception) as e:
        b["notes"].append(f"{name}: {e}")
    if b.get("task"):
        b["task"].cancel()
    b["task"] = asyncio.create_task(_flush_batch_later(user_id))


async def _flush_batch_later(user_id):
    try:
        await asyncio.sleep(BATCH_DELAY)
    except asyncio.CancelledError:
        return
    b = pending_batches.pop(user_id, None)
    if not b:
        return
    files, notes, message = b["files"], b["notes"], b["message"]
    if not files:
        await message.reply_text("📄 " + ("\n".join(notes) or "Не получилось сохранить файлы."))
        return
    question = b["caption"] or ("Изучи присланные файлы: кратко опиши, что это и как устроено, "
                                "и выдели главное." if len(files) > 1 else
                                "Кратко перескажи, что в этом файле, и выдели главное.")
    total = 0
    parts = []
    for f in files:
        raw = ws.read_bytes(user_id, f)
        if not ws.is_text(raw):
            # двоичный файл: сразу даём модели сводку, детали — через analyze_binary
            info = await asyncio.to_thread(ws.binary.summary, raw, f)
            parts.append(f"[ДВОИЧНЫЙ ФАЙЛ {f}]\n{info}\n(подробнее — analyze_binary: exports, imports, "
                         f"functions, strings, sections, disasm)")
            continue
        text = ws.decode_text(raw)
        if total + len(text) <= MAX_FILE_CHARS:
            parts.append(f"<<<ФАЙЛ {f}\n{text}\nФАЙЛ {f}>>>")
            total += len(text)
        else:
            parts.append(f"[{f} — {len(text)} символов, не влез в сообщение: прочитай через read_file]")
    prompt = (f"Пользователь прислал {len(files)} файл(ов), они сохранены в рабочей папке:\n"
              + "\n\n".join(parts) + ("\n\nЗаметки: " + "; ".join(notes) if notes else "")
              + f"\n\nЗапрос пользователя: {question}")
    history_text = (f"[Прислал файлы в рабочую папку: {', '.join(files[:30])}"
                    + (f" и ещё {len(files) - 30}" if len(files) > 30 else "") + "]\n" + question)
    await message.reply_text(f"📥 Сохранил в 📁 Файлы: {len(files)} шт." + (f"\n" + "\n".join(notes) if notes else ""))
    await run_turn(message, user_id, prompt, history_text=history_text)


# ---------- Меню «Файлы» ----------

def files_menu(user_id: int, page: int = 0):
    files = ws.list_files(user_id)
    per = 15
    pages = max(1, (len(files) + per - 1) // per)
    page = max(0, min(page, pages - 1))
    rows = []
    for i, f in enumerate(files[page * per:(page + 1) * per], start=page * per):
        rows.append([InlineKeyboardButton(f"📤 {f['name'][-38:]}", callback_data=f"fls:{i}"),
                     InlineKeyboardButton("🗑", callback_data=f"fld:{i}")])
    nav = []
    if page > 0:
        nav.append(InlineKeyboardButton("◀️", callback_data=f"flp:{page - 1}"))
    if page < pages - 1:
        nav.append(InlineKeyboardButton("▶️", callback_data=f"flp:{page + 1}"))
    if nav:
        rows.append(nav)
    if files:
        rows.append([InlineKeyboardButton("🗑 Удалить все файлы", callback_data="fldall")])
        size = sum(f["size"] for f in files)
        text = (f"📁 *Рабочая папка* — {len(files)} файлов, {size // 1024} КБ из "
                f"{ws.MAX_WORKSPACE_BYTES // 1024 // 1024} МБ\n"
                "📤 — прислать файл, 🗑 — удалить.\n"
                "Я могу читать и менять эти файлы — просто попроси в чате.")
    else:
        text = ("📁 *Рабочая папка пуста.*\nПришли файлы скриптов (можно несколько сразу или .zip) — "
                "я сохраню их, изучу и смогу менять и присылать обратно.")
    return text, (InlineKeyboardMarkup(rows) if rows else None)


async def claim_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    ok, owner = devtools.claim_owner(update.effective_user.id)
    if ok:
        await update.message.reply_text(
            "👑 Ты владелец бота. Тебе доступен запуск кода на сервере (python/bash): "
            "просто попроси, например «запусти main.py» или «проверь функцию тестом».")
    else:
        await update.message.reply_text("У бота уже есть владелец. Запуск кода доступен только ему.")


async def show_files(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text, kb = files_menu(update.effective_user.id)
    await update.effective_message.reply_text(text, parse_mode="Markdown", reply_markup=kb)


async def files_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    uid = query.from_user.id
    files = ws.list_files(uid)
    page = 0
    if query.data == "fldall":
        ws.delete_all_files(uid)
        await query.answer("Все файлы удалены")
    elif query.data.startswith("flp:"):
        page = int(query.data[4:])
        await query.answer()
    else:
        action, idx = query.data.split(":", 1)
        idx = int(idx)
        if idx >= len(files):
            await query.answer("Список изменился — открой /files заново")
            return
        f = files[idx]
        page = idx // 15
        if action == "fls":
            await query.answer()
            await query.message.reply_document(document=ws.read_bytes(uid, f["name"]),
                                               filename=os.path.basename(f["name"]))
            return
        ws.delete_file(uid, f["name"])
        await query.answer(f"Удалён: {f['name']}")
    text, kb = files_menu(uid, page)
    try:
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=kb)
    except Exception:
        pass


# ---------- Меню «API» ----------

API_HELP = ("Добавить коннектор:\n"
            "`/api add имя https://адрес [header:Имя=значение] [query:имя=значение]`\n\n"
            "Примеры:\n"
            "`/api add github https://api.github.com header:Authorization=Bearer ghp_XXX`\n"
            "`/api add weather https://api.openweathermap.org/data/2.5 query:appid=КЛЮЧ`\n\n"
            "Ключи хранятся на сервере и *не показываются модели* — она только вызывает API по имени. "
            "Сообщение с ключом я сразу удалю из чата.\n"
            "Потом просто попроси: «вызови github /user и пришли скриншот».")


def api_menu(user_id: int):
    conns = ws.load_connectors(user_id)
    rows = [[InlineKeyboardButton(f"🔌 {n} → {c['base'][8:40]}", callback_data=f"apv:{n}"),
             InlineKeyboardButton("🗑", callback_data=f"apd:{n}")] for n, c in conns.items()]
    text = ("🔌 *API-коннекторы*\n" + ("\n".join(f"• *{md_escape(n)}* — {md_escape(c['base'])}"
                                                for n, c in conns.items()) or "Пока нет.")
            + "\n\n" + API_HELP)
    return text, (InlineKeyboardMarkup(rows) if rows else None)


async def api_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    args = context.args or []
    if args and args[0].lower() == "add":
        try:
            await update.message.delete()          # убираем сообщение с ключом из чата
        except Exception:
            pass
        try:
            name, conn = ws.parse_api_add(args[1:])
        except ValueError as e:
            await update.effective_chat.send_message(f"⚠️ {e}\n\n{API_HELP}", parse_mode="Markdown")
            return
        conns = ws.load_connectors(uid)
        conns[name] = conn
        ws.save_connectors(uid, conns)
        secrets_n = len(conn["headers"]) + len(conn["query"])
        await update.effective_chat.send_message(
            f"🔌 Коннектор *{md_escape(name)}* → {md_escape(conn['base'])} сохранён"
            f" (секретов: {secrets_n}, сообщение с ними удалено).\n"
            f"Попроси: «вызови {name} … и пришли скриншот».", parse_mode="Markdown")
        return
    text, kb = api_menu(uid)
    await update.effective_message.reply_text(text, parse_mode="Markdown", reply_markup=kb)


async def api_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    uid = query.from_user.id
    action, name = query.data.split(":", 1)
    conns = ws.load_connectors(uid)
    if name not in conns:
        await query.answer("Уже удалён")
    elif action == "apd":
        conns.pop(name)
        ws.save_connectors(uid, conns)
        await query.answer(f"Удалён: {name}")
    else:
        c = conns[name]
        await query.answer()
        await query.message.reply_text(
            f"🔌 {name}\nАдрес: {c['base']}\nЗаголовки: {', '.join(c['headers']) or '—'}\n"
            f"Параметры: {', '.join(c['query']) or '—'}\n(значения скрыты) · добавлен {c.get('added', '')}")
        return
    text, kb = api_menu(uid)
    try:
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=kb)
    except Exception:
        pass


async def md_choice_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    action, token = query.data.split(":", 1)
    f = pending_files.get(token)
    if not f or f["user"] != query.from_user.id:
        await query.answer("Файл устарел — пришли его ещё раз")
        return
    pending_files.pop(token, None)
    await query.answer()
    if action == "mdadd":
        if len(f["text"]) > MAX_INSTRUCTION_CHARS:
            await query.edit_message_text(
                f"⚠️ Инструкция слишком длинная: {len(f['text'])} символов, максимум {MAX_INSTRUCTION_CHARS}.")
            return
        items = instructions.setdefault(str(f["user"]), [])
        total = sum(len(x["text"]) for x in items) + len(f["text"])
        if total > MAX_INSTRUCTIONS_TOTAL:
            await query.edit_message_text(
                f"⚠️ Вместе с этим файлом инструкций будет {total} символов, максимум {MAX_INSTRUCTIONS_TOTAL}. "
                f"Удали что-нибудь в меню 📌 Инструкции.")
            return
        if len(items) >= MAX_INSTRUCTIONS:
            await query.edit_message_text(
                f"⚠️ Уже {MAX_INSTRUCTIONS} инструкций — удали лишние в меню 📌 Инструкции.")
            return
        items.append({"id": secrets.token_hex(3), "name": f["name"], "text": f["text"],
                      "added": datetime.now().strftime("%d.%m.%Y")})
        save_instructions()
        await query.edit_message_text(
            f"📌 *{md_escape(f['name'])}* добавлен в инструкции.\n"
            f"Теперь я учитываю его в каждом ответе. Управлять: /instructions",
            parse_mode="Markdown")
    else:
        await query.edit_message_text(f"🔍 Анализирую {f['name']}…")
        await run_turn(query.message, f["user"],
                       file_prompt(f["name"], f["size"], f["text"], f["caption"]))


# ---------- Инструкции (свои .md файлы) ----------

def instructions_menu(user_id: int):
    items = instructions.get(str(user_id), [])
    rows = [[InlineKeyboardButton(f"📄 {it['name'][:40]}", callback_data=f"insv:{it['id']}"),
             InlineKeyboardButton("🗑", callback_data=f"insd:{it['id']}")] for it in items]
    if items:
        rows.append([InlineKeyboardButton("🗑 Удалить все", callback_data="insdall")])
    text = ("📌 *Твои инструкции*\nОни добавляются к каждому моему ответу.\n"
            "Нажми на файл — покажу его, 🗑 — удалю.\n\n"
            f"Всего: {len(items)} из {MAX_INSTRUCTIONS} · "
            f"{sum(len(x['text']) for x in items) // 1000} из {MAX_INSTRUCTIONS_TOTAL // 1000} тыс. символов" if items else
            "📌 *Инструкций пока нет.*\nПришли мне файл .md и выбери «📌 Добавить в инструкции».")
    return text, (InlineKeyboardMarkup(rows) if rows else None)


async def show_instructions(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text, kb = instructions_menu(update.effective_user.id)
    await update.effective_message.reply_text(text, parse_mode="Markdown", reply_markup=kb)


async def instructions_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    uid = query.from_user.id
    items = instructions.get(str(uid), [])
    if query.data == "insdall":
        instructions.pop(str(uid), None)
        save_instructions()
        await query.answer("Все инструкции удалены")
    else:
        action, iid = query.data.split(":", 1)
        it = next((x for x in items if x["id"] == iid), None)
        if not it:
            await query.answer("Уже удалено")
        elif action == "insd":
            items.remove(it)
            save_instructions()
            await query.answer(f"Удалено: {it['name']}")
        else:
            await query.answer()
            preview = it["text"] if len(it["text"]) <= 3500 else it["text"][:3500] + "\n…"
            await query.message.reply_text(
                f"📄 <b>{html.escape(it['name'])}</b> · добавлен {it['added']} · {len(it['text'])} симв.\n"
                f"<blockquote expandable>{html.escape(preview)}</blockquote>", parse_mode="HTML")
            return
    text, kb = instructions_menu(uid)
    try:
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=kb)
    except Exception:
        pass


async def reset_dialog(update: Update, context: ContextTypes.DEFAULT_TYPE):
    stop_user_turns(update.effective_user.id)
    forget_history(update.effective_user.id)
    await update.effective_message.reply_text(
        "🗑 *Диалог очищен.* Начнём с чистого листа!",
        parse_mode="Markdown",
        reply_markup=main_keyboard()
    )


async def toggle_thoughts(update: Update, context: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    show_thoughts[uid] = not show_thoughts.get(uid, True)
    state = "показываю 💭" if show_thoughts[uid] else "скрываю 🙈"
    await update.message.reply_text(f"Размышления модели теперь {state}.")


async def toggle_web(update: Update, context: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    web_enabled[uid] = not web_enabled.get(uid, True)
    state = "включён 🌐" if web_enabled[uid] else "выключен 🚫"
    await update.message.reply_text(f"Доступ к интернету {state}.")


async def clear_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    stop_user_turns(query.from_user.id)
    forget_history(query.from_user.id)
    await query.answer("Диалог очищен!")
    await query.edit_message_reply_markup(reply_markup=None)
    await context.bot.send_message(
        chat_id=query.message.chat_id,
        text="🗑 *Диалог очищен.* Начнём с чистого листа!",
        parse_mode="Markdown",
        reply_markup=main_keyboard()
    )


def models_keyboard(current: str):
    rows = [[InlineKeyboardButton(("✅ " if mid == current else "") + name,
                                  callback_data=f"model:{mid}")]
            for mid, name in ((k, v["label"]) for k, v in MODELS.items())]
    return InlineKeyboardMarkup(rows)


async def choose_model(update: Update, context: ContextTypes.DEFAULT_TYPE):
    current = user_models.get(update.effective_user.id, DEFAULT_MODEL)
    await update.effective_message.reply_text(
        f"🤖 Сейчас: *{MODELS.get(current, MODELS[DEFAULT_MODEL])['label']}*\nВыбери модель:",
        parse_mode="Markdown", reply_markup=models_keyboard(current))


async def model_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    mid = query.data.split(":", 1)[1]
    if mid not in MODELS:
        await query.answer("Неизвестная модель")
        return
    user_models[query.from_user.id] = mid
    await query.answer(f"Выбрано: {MODELS[mid]['label']}")
    await query.edit_message_text(
        f"🤖 Теперь отвечает *{MODELS[mid]['label']}*.\nКонтекст диалога сохранён.",
        parse_mode="Markdown")


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "👋 Привет! Я ИИ-ассистент с доступом к интернету (по умолчанию DeepSeek V4.1 Flash).\n"
        "Просто напиши мне что-нибудь!\n\n"
        "🗑 «Новый диалог» внизу или /clear — начать заново\n"
        "💭 /thoughts — показывать/скрывать размышления\n"
        "🌐 /web — включить/выключить интернет\n"
        "🤖 /model — сменить ИИ\n"
        "📁 Пришли файлы скриптов (можно несколько сразу или .zip) — изучу, изменю и пришлю обратно\n"
        "🔌 /api — подключить своё API; могу вызывать его и присылать скриншоты ответов\n"
        "🧪 Проверяю код на ошибки, ищу по проекту, собираю архивы; владелец (/claim) — ещё и запускаю код\n"
        "📌 Пришли файл .md — добавлю его в свои инструкции или разберу\n"
        "📌 /instructions — список инструкций, удаление",
        reply_markup=main_keyboard()
    )


def _build_id() -> str:
    """Хэш исходников — по нему видно снаружи, какая версия бота реально запущена."""
    import hashlib
    h = hashlib.sha256()
    base = os.path.dirname(os.path.abspath(__file__))
    for f in sorted(x for x in os.listdir(base) if x.endswith(".py")):
        with open(os.path.join(base, f), "rb") as fh:
            h.update(f.encode() + fh.read())
    return h.hexdigest()[:8]


BUILD_ID = _build_id()


async def post_init(application):
    try:
        await application.bot.set_my_short_description(f"DeepSeek-бот · сборка {BUILD_ID}")
    except Exception:
        pass
    await application.bot.set_my_commands([
        BotCommand("model", "🤖 Сменить ИИ"),
        BotCommand("instructions", "📌 Мои инструкции"),
        BotCommand("files", "📁 Рабочая папка с файлами"),
        BotCommand("api", "🔌 API-коннекторы"),
        BotCommand("clear", "🗑 Очистить контекст диалога"),
        BotCommand("thoughts", "💭 Показывать/скрывать размышления"),
        BotCommand("web", "🌐 Включить/выключить интернет"),
        BotCommand("claim", "👑 Стать владельцем (запуск кода)"),
        BotCommand("start", "Начать"),
    ])


app = (ApplicationBuilder().token(TELEGRAM_TOKEN)
       .concurrent_updates(True).post_init(post_init).build())
app.add_handler(CommandHandler("start", start))
app.add_handler(CommandHandler(["clear", "new", "reset"], reset_dialog))
app.add_handler(CommandHandler(["thoughts", "think"], toggle_thoughts))
app.add_handler(CommandHandler("web", toggle_web))
app.add_handler(CommandHandler(["model", "ai"], choose_model))
app.add_handler(CallbackQueryHandler(model_callback, pattern="^model:"))
app.add_handler(CallbackQueryHandler(clear_callback, pattern="^clear$"))
app.add_handler(CallbackQueryHandler(remove_request_callback, pattern="^rmq:"))
app.add_handler(CallbackQueryHandler(switch_model_callback, pattern="^swm:"))
app.add_handler(CommandHandler(["instructions", "instr"], show_instructions))
app.add_handler(CallbackQueryHandler(md_choice_callback, pattern="^md(add|ana):"))
app.add_handler(CallbackQueryHandler(instructions_callback, pattern="^ins(v:|d:|dall$)"))
app.add_handler(CommandHandler("files", show_files))
app.add_handler(CommandHandler("claim", claim_command))
app.add_handler(CallbackQueryHandler(files_callback, pattern="^fl(s:|d:|p:|dall$)"))
app.add_handler(CommandHandler("api", api_command))
app.add_handler(CallbackQueryHandler(api_callback, pattern="^ap(v|d):"))
app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
app.add_handler(MessageHandler(filters.Document.ALL, handle_document))

WEBHOOK_URL = os.environ.get("WEBHOOK_URL")  # напр. https://noizoz-bot.thevibehosting.com
if WEBHOOK_URL:
    # Режим webhook: Telegram сам присылает сообщения на сервер (и будит его, если он уснул)
    import hashlib
    secret = hashlib.sha256(TELEGRAM_TOKEN.encode()).hexdigest()
    app.run_webhook(
        listen="0.0.0.0",
        port=int(os.environ.get("PORT", "80")),
        url_path=secret[:32],
        webhook_url=f"{WEBHOOK_URL.rstrip('/')}/{secret[:32]}",
        secret_token=secret[32:64],
        allowed_updates=Update.ALL_TYPES,
        drop_pending_updates=True,   # после перезапуска не отвечаем на старые сообщения
    )
else:
    app.run_polling(drop_pending_updates=True)
