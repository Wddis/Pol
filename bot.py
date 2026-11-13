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

# Провайдеры по порядку: сначала быстрый OrcaRouter, при ошибке/лимите — NVIDIA.
PROVIDERS = []
if os.environ.get("ORCAROUTER_API_KEY"):
    PROVIDERS.append({
        "name": "OrcaRouter",
        "url": "https://api.orcarouter.ai/v1/chat/completions",
        "key": os.environ["ORCAROUTER_API_KEY"],
        "model": os.environ.get("ORCA_MODEL", "deepseek/deepseek-v4.1-flash-free"),
        "extra": {},
    })
if os.environ.get("NVIDIA_API_KEY"):
    PROVIDERS.append({
        "name": "NVIDIA",
        "url": "https://integrate.api.nvidia.com/v1/chat/completions",
        "key": os.environ["NVIDIA_API_KEY"],
        "model": os.environ.get("NVIDIA_MODEL", "deepseek-ai/deepseek-v4.1-flash"),
        "extra": {"chat_template_kwargs": {"thinking": True}},
    })
if not PROVIDERS:
    raise SystemExit("Нужен ORCAROUTER_API_KEY и/или NVIDIA_API_KEY")

MAX_TOOL_ROUNDS = 5      # сколько раз подряд модель может искать/открывать страницы
EDIT_INTERVAL = 2.5      # как часто обновлять сообщение «Думаю…» (сек)
TG_LIMIT = 4000          # лимит длины сообщения Telegram (с запасом)

chat_histories = {}      # user_id -> список сообщений (только в памяти)
show_thoughts = {}       # user_id -> показывать ли размышления (по умолчанию да)
web_enabled = {}         # user_id -> разрешён ли интернет (по умолчанию да)
user_locks = {}          # user_id -> asyncio.Lock, чтобы ответы шли по очереди

SYSTEM_PROMPT = """Ты полезный ассистент. Отвечай на русском языке.
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
    return ReplyKeyboardMarkup([[CLEAR_TEXT]], resize_keyboard=True, is_persistent=True)


# ---------- Живой статус ----------

class LiveStatus:
    """Сообщение «Думаю…», которое обновляется по ходу работы модели."""

    def __init__(self, message, show_thoughts: bool):
        self.msg = message
        self.show = show_thoughts
        self.started = time.time()
        self.stage = "⏳ Жду очереди на сервере…"
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

class RetryableError(Exception):
    pass


async def stream_completion(client, provider, messages, status: LiveStatus, use_tools: bool):
    """Один запрос к модели со стримингом. Возвращает (content, reasoning, tool_calls)."""
    payload = {
        "model": provider["model"],
        "messages": messages,
        "max_tokens": 16384,
        "temperature": 1.0,
        "top_p": 1.0,
        "stream": True,
        **provider["extra"],
    }
    if use_tools:
        payload["tools"] = TOOLS

    content, reasoning = "", ""
    tool_calls = {}
    async with client.stream("POST", provider["url"], json=payload, headers={
        "Authorization": f"Bearer {provider['key']}",
        "Accept": "text/event-stream",
    }) as resp:
        if resp.status_code in (429, 500, 502, 503, 504):
            raise RetryableError(resp.status_code)
        if resp.status_code != 200:
            body = (await resp.aread()).decode("utf-8", "replace")
            raise RetryableError(f"{provider['name']} {resp.status_code}: {body[:200]}")
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
    system = SYSTEM_PROMPT.format(
        today=datetime.now().strftime("%d.%m.%Y"),
        web_note=WEB_NOTE_ON if use_web else WEB_NOTE_OFF)

    turn = [{"role": "user", "content": user_message}]
    all_reasoning = []
    timeout = httpx.Timeout(connect=30, read=600, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as client:
        for round_no in range(MAX_TOOL_ROUNDS + 1):
            allow_tools = use_web and round_no < MAX_TOOL_ROUNDS
            messages = [{"role": "system", "content": system}, *history, *turn]
            errors = []
            for provider in PROVIDERS:
                try:
                    content, reasoning, calls = await stream_completion(
                        client, provider, messages, status, allow_tools)
                    break
                except (httpx.TransportError, RetryableError) as e:
                    # Провайдер перегружен / лимит / обрыв — переключаемся на следующий
                    errors.append(f"{provider['name']}: {type(e).__name__} {e}"[:200])
                    status.stage = f"🔁 {provider['name']} не ответил, пробую другой сервер…"
                    status.reasoning = ""
                    await status.update(force=True)
            else:
                raise RuntimeError("Серверы не ответили, попробуй ещё раз чуть позже.\n"
                                   + "\n".join(errors))
            if reasoning.strip():
                all_reasoning.append(reasoning.strip())

            if not calls:
                if not content.strip():
                    raise RuntimeError("Модель вернула пустой ответ, попробуй ещё раз.")
                history.extend(turn)
                history.append({"role": "assistant", "content": content})
                return content, "\n\n".join(all_reasoning)

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
            status.stage = "⏳ Нашёл, жду очереди на сервере…"
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

    lock = user_locks.setdefault(user_id, asyncio.Lock())
    if lock.locked():
        await update.message.reply_text("⏳ Ещё отвечаю на прошлое сообщение — это встанет следом.")
    async with lock:
        show = show_thoughts.get(user_id, True)
        status_msg = await update.message.reply_text("⏳ Жду очереди на сервере…")
        status = LiveStatus(status_msg, show)
        ticker = asyncio.create_task(status.ticker())
        try:
            answer, reasoning = await ask_model(user_id, user_text, status)
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
        await send_answer(update.message, answer)


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


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "👋 Привет! Я DeepSeek V4.1 Flash с доступом к интернету.\n"
        "Просто напиши мне что-нибудь!\n\n"
        "🗑 «Новый диалог» внизу или /clear — начать заново\n"
        "💭 /thoughts — показывать/скрывать размышления\n"
        "🌐 /web — включить/выключить интернет",
        reply_markup=main_keyboard()
    )


async def post_init(application):
    await application.bot.set_my_commands([
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
app.add_handler(CallbackQueryHandler(clear_callback, pattern="clear"))
app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
app.run_polling()
