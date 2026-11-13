import os
import json
import time
import asyncio
import html
from datetime import datetime

import httpx
from bs4 import BeautifulSoup
from ddgs import DDGS
from telegram import (Update, InlineKeyboardButton, InlineKeyboardMarkup,
                      ReplyKeyboardMarkup, BotCommand)
from telegram.ext import (ApplicationBuilder, MessageHandler, CommandHandler,
                          CallbackQueryHandler, filters, ContextTypes)

TELEGRAM_TOKEN = os.environ["TELEGRAM_TOKEN"]

# ---------- Провайдеры ----------
# Подключаются те, для которых задан ключ в переменных окружения.
PROVIDERS = {
    "uno": {"name": "UnoRouter", "url": "https://api.unorouter.com/v1", "env": "UNOROUTER_API_KEY"},
}
for _p in PROVIDERS.values():
    _p["key"] = os.environ.get(_p["env"], "")
if not any(p["key"] for p in PROVIDERS.values()):
    raise SystemExit("Не задан ни один ключ провайдера (UNOROUTER_API_KEY)")

# Модели и цепочки провайдеров: бот идёт по списку, пока кто-то не ответит.
MODELS = {
    # У DeepSeek две бесплатные версии с раздельными лимитами — используем обе по очереди
    "deepseek-v4.1-flash": {
        "label": "🐋 DeepSeek V4.1 Flash", "identity": ("DeepSeek V4.1 Flash", "DeepSeek"),
        "routes": [("uno", "deepseek-v4.1-flash:free"),
                   ("uno", "deepseek-v4.1-flash-1017:free")]},
    "qwen3.5-397b": {
        "label": "🐉 Qwen 3.5 397B", "identity": ("Qwen 3.5 397B", "Alibaba"),
        "routes": [("uno", "qwen3.5-397b-a17b:free")]},
    "mistral-large-3": {
        "label": "🌬 Mistral Large 3", "identity": ("Mistral Large 3", "Mistral AI"),
        "routes": [("uno", "mistral-large-3-675b-instruct-2512:free")]},
    "gemini-3.6-flash": {
        "label": "✨ Gemini 3.6 Flash", "identity": ("Gemini 3.6 Flash", "Google"),
        "routes": [("uno", "gemini-3.6-flash:free")]},
    "glm-5.3-flash": {
        "label": "🧩 GLM 5.3 Flash", "identity": ("GLM 5.3 Flash", "Z.ai"),
        "routes": [("uno", "glm-5.3-flash:free")]},
}
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "deepseek-v4.1-flash")
# Если у выбранной модели все каналы исчерпаны — используем цепочку DeepSeek V4.1

# Провайдер упёрся в лимит → не трогаем его до этого времени (не тратим время на отказы)
cooldown_until = {}      # (provider_id, model) -> unix time
MAX_COOLDOWN = 6 * 3600

user_models = {}         # user_id -> выбранная модель

MAX_TOOL_ROUNDS = 5      # сколько раз подряд модель может искать/открывать страницы
EDIT_INTERVAL = 2.5      # как часто обновлять сообщение «Думаю…» (сек)
TG_LIMIT = 4000          # лимит длины сообщения Telegram (с запасом)

chat_histories = {}      # user_id -> список сообщений (только в памяти)
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
    return ReplyKeyboardMarkup([[CLEAR_TEXT, MODEL_TEXT]], resize_keyboard=True, is_persistent=True)


# ---------- Живой статус ----------

class LiveStatus:
    """Сообщение «Думаю…», которое обновляется по ходу работы модели."""

    def __init__(self, message, show_thoughts: bool):
        self.msg = message
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
            await self.msg.edit_text(text, parse_mode="HTML")
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


MAX_LIMIT_WAIT = 5 * 60   # дольше 5 минут не ждём — сообщаем, когда освободится


async def countdown(status, model_key, wait):
    """Показывает в сообщении статуса обратный отсчёт до снятия лимита."""
    end = time.time() + wait
    label = MODELS[model_key]["label"]
    status.reasoning = ""
    while True:
        left = int(end - time.time() + 0.999)
        if left <= 0:
            break
        status.stage = (f"⏳ Лимит {label}: 1 запрос в минуту. "
                        f"Отвечу через {left // 60}:{left % 60:02d}")
        await status.update(force=True)
        await asyncio.sleep(min(3, max(0.5, end - time.time())))
    status.stage = "⏳ Думаю…"
    await status.update(force=True)


class RetryableError(Exception):
    pass


async def stream_completion(client, route, messages, status: LiveStatus, use_tools: bool):
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
    if use_tools:
        payload["tools"] = TOOLS

    content, reasoning = "", ""
    tool_calls = {}
    async with client.stream("POST", prov["url"] + "/chat/completions", json=payload, headers={
        "Authorization": f"Bearer {prov['key']}",
        "Accept": "text/event-stream",
    }) as resp:
        if resp.status_code != 200:
            body = (await resp.aread()).decode("utf-8", "replace")
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
            raise RetryableError(f"{resp.status_code}: {body[:150]}")
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


async def ask_model(user_id: int, user_message: str, status: LiveStatus):
    history = chat_histories.setdefault(user_id, [])
    use_web = web_enabled.get(user_id, True)
    def system_for(route):
        logical = chosen if route in MODELS[chosen]["routes"] else \
            next((k for k, v in MODELS.items() if route in v["routes"]), chosen)
        name, vendor = MODELS[logical]["identity"]
        return SYSTEM_PROMPT.format(
            model_name=name, vendor=vendor,
            today=datetime.now().strftime("%d.%m.%Y"),
            web_note=WEB_NOTE_ON if use_web else WEB_NOTE_OFF)

    chosen = user_models.get(user_id, DEFAULT_MODEL)
    if chosen not in MODELS:
        chosen = DEFAULT_MODEL
    turn = [{"role": "user", "content": user_message}]
    all_reasoning = []
    timeout = httpx.Timeout(connect=30, read=600, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as client:
        for round_no in range(MAX_TOOL_ROUNDS + 1):
            allow_tools = use_web and round_no < MAX_TOOL_ROUNDS
            # Только выбранная модель — никаких подмен. При лимите ждём с обратным отсчётом.
            routes = [r for r in MODELS[chosen]["routes"] if PROVIDERS[r[0]]["key"]]
            used_route = None
            errors = []
            while used_route is None:
                now = time.time()
                ready = [r for r in routes if cooldown_until.get(r, 0) <= now]
                if not ready:
                    wait = min(cooldown_until[r] for r in routes) - now
                    if wait > MAX_LIMIT_WAIT:
                        free_at = datetime.fromtimestamp(now + wait).strftime("%H:%M")
                        raise RuntimeError(f"Лимит модели {MODELS[chosen]['label']} исчерпан надолго — "
                                           f"освободится примерно в {free_at} (UTC). Можно выбрать другую модель: /model")
                    await countdown(status, chosen, wait)
                    continue
                for route in ready:
                    messages = [{"role": "system", "content": system_for(route)}, *history, *turn]
                    try:
                        content, reasoning, calls = await stream_completion(
                            client, route, messages, status, allow_tools)
                        used_route = route
                        break
                    except httpx.TransportError as e:
                        errors.append(f"{type(e).__name__}")
                        cooldown_until[route] = time.time() + 5     # сетевой сбой — короткая пауза
                    except RetryableError as e:
                        errors.append(str(e)[:150])
                        if route not in cooldown_until or cooldown_until[route] <= time.time():
                            cooldown_until[route] = time.time() + 15  # непонятная ошибка — пауза 15 с
                if len(errors) > 12:
                    raise RuntimeError("Модель не отвечает, попробуй позже.\n" + "\n".join(errors[-3:]))
            if reasoning.strip():
                all_reasoning.append(reasoning.strip())

            if not calls:
                if not content.strip():
                    raise RuntimeError("Модель вернула пустой ответ, попробуй ещё раз.")
                history.extend(turn)
                history.append({"role": "assistant", "content": content})
                return content, "\n\n".join(all_reasoning), used_route

            # Модель хочет воспользоваться интернетом
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
                if c["name"] == "web_search":
                    status.actions.append(f"🔎 Ищу: {args.get('query', '')}")
                elif c["name"] == "open_url":
                    status.actions.append(f"🌐 Открываю: {args.get('url', '')[:80]}")
                status.stage = "🌐 Работаю с интернетом…"
                await status.update(force=True)
                result = await run_tool(c["name"], args)
                turn.append({"role": "tool", "tool_call_id": c["id"], "content": result})
            status.stage = "🧠 Изучаю найденное…"
            status.reasoning = ""
    raise RuntimeError("Слишком много обращений к интернету, попробуй переформулировать вопрос.")


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

    await run_turn(update, user_id, user_text)


async def run_turn(update: Update, user_id: int, user_text: str):
    lock = user_locks.setdefault(user_id, asyncio.Lock())
    if lock.locked():
        await update.message.reply_text("⏳ Ещё отвечаю на прошлое сообщение — это встанет следом.")
    async with lock:
        show = show_thoughts.get(user_id, True)
        status_msg = await update.message.reply_text("⏳ Думаю…")
        status = LiveStatus(status_msg, show)
        ticker = asyncio.create_task(status.ticker())
        try:
            answer, reasoning, used_route = await ask_model(user_id, user_text, status)
        except Exception as e:
            ticker.cancel()
            try:
                await status_msg.edit_text(f"⚠️ {e}")
            except Exception:
                pass
            return
        ticker.cancel()
        seconds = int(time.time() - status.started)
        try:
            await status_msg.delete()
        except Exception:
            pass
        if show and reasoning:
            await send_thoughts(update.message, reasoning, seconds)
        await send_answer(update.message, answer + f"\n\n_— {route_label(used_route)}_")


# ---------- Файлы .txt ----------

MAX_FILE_BYTES = 2 * 1024 * 1024     # Telegram-бот может скачать до 20 МБ, нам хватит 2 МБ
MAX_FILE_CHARS = 60000               # сколько символов файла отдаём модели


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


async def handle_document(update: Update, context: ContextTypes.DEFAULT_TYPE):
    doc = update.message.document
    name = doc.file_name or "file.txt"
    if not (name.lower().endswith(".txt") or doc.mime_type == "text/plain"):
        await update.message.reply_text("📄 Пока я понимаю только текстовые файлы .txt")
        return
    if doc.file_size and doc.file_size > MAX_FILE_BYTES:
        await update.message.reply_text("📄 Файл слишком большой — максимум 2 МБ.")
        return
    tg_file = await doc.get_file()
    data = bytes(await tg_file.download_as_bytearray())
    text = decode_text(data).replace("\r\n", "\n").strip()
    if not text:
        await update.message.reply_text("📄 Файл пустой.")
        return
    note = ""
    if len(text) > MAX_FILE_CHARS:
        note = (f"\n[Файл обрезан: показаны первые {MAX_FILE_CHARS} из {len(text)} символов. "
                f"Если нужно — скажи пользователю, что видишь только начало.]")
        text = text[:MAX_FILE_CHARS]
    question = (update.message.caption or "").strip() or \
        "Кратко перескажи, что в этом файле, и выдели главное."
    prompt = (f"Пользователь прислал файл «{name}» ({len(data)} байт). Его содержимое:\n"
              f"<<<ФАЙЛ\n{text}\nФАЙЛ>>>{note}\n\nЗапрос пользователя: {question}")
    await run_turn(update, update.message.from_user.id, prompt)


async def reset_dialog(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat_histories.pop(update.effective_user.id, None)
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
    chat_histories.pop(query.from_user.id, None)
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
        "📄 Пришли файл .txt (можно с вопросом в подписи) — разберу его",
        reply_markup=main_keyboard()
    )


async def post_init(application):
    await application.bot.set_my_commands([
        BotCommand("model", "🤖 Сменить ИИ"),
        BotCommand("clear", "🗑 Очистить контекст диалога"),
        BotCommand("thoughts", "💭 Показывать/скрывать размышления"),
        BotCommand("web", "🌐 Включить/выключить интернет"),
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
    )
else:
    app.run_polling()
