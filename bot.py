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

ORCA_URL = "https://api.orcarouter.ai/v1/chat/completions"
ORCA_KEY = os.environ["ORCAROUTER_API_KEY"]

# Бесплатные модели OrcaRouter, между которыми можно переключаться (/model)
MODELS = {
    "deepseek/deepseek-v4.1-flash-free": "🐋 DeepSeek V4.1 Flash",
    "deepseek/deepseek-v4-flash-free": "🐳 DeepSeek V4 Flash",
    "z-ai/glm-5.3-flash-free": "🧩 GLM 5.3 Flash",
    "tencent/hy4-preview-free": "🐧 Tencent Hy4 (preview)",
}
# Настоящие названия и разработчики — модель получает их в системном промпте
MODEL_IDENTITY = {
    "deepseek/deepseek-v4.1-flash-free": ("DeepSeek V4.1 Flash", "DeepSeek"),
    "deepseek/deepseek-v4-flash-free": ("DeepSeek V4 Flash", "DeepSeek"),
    "z-ai/glm-5.3-flash-free": ("GLM 5.3 Flash", "Z.ai"),
    "tencent/hy4-preview-free": ("Hunyuan Hy4 (preview)", "Tencent"),
}
DEFAULT_MODEL = os.environ.get("ORCA_MODEL", "deepseek/deepseek-v4.1-flash-free")
# Если выбранная модель не отвечает — пробуем запасную
FALLBACKS = {"deepseek/deepseek-v4-flash-free": "deepseek/deepseek-v4.1-flash-free"}
DEFAULT_FALLBACK = "deepseek/deepseek-v4-flash-free"
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

class RetryableError(Exception):
    pass


async def stream_completion(client, model, messages, status: LiveStatus, use_tools: bool):
    """Один запрос к модели со стримингом. Возвращает (content, reasoning, tool_calls)."""
    payload = {
        "model": model,
        "messages": messages,
        "max_tokens": 16384,
        "temperature": 1.0,
        "top_p": 1.0,
        "stream": True,
    }
    if use_tools:
        payload["tools"] = TOOLS

    content, reasoning = "", ""
    tool_calls = {}
    async with client.stream("POST", ORCA_URL, json=payload, headers={
        "Authorization": f"Bearer {ORCA_KEY}",
        "Accept": "text/event-stream",
    }) as resp:
        if resp.status_code in (429, 500, 502, 503, 504):
            raise RetryableError(resp.status_code)
        if resp.status_code != 200:
            body = (await resp.aread()).decode("utf-8", "replace")
            raise RetryableError(f"{resp.status_code}: {body[:200]}")
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
    def system_for(model):
        name, vendor = MODEL_IDENTITY.get(model, (MODELS.get(model, model), "—"))
        return SYSTEM_PROMPT.format(
            model_name=name, vendor=vendor,
            today=datetime.now().strftime("%d.%m.%Y"),
            web_note=WEB_NOTE_ON if use_web else WEB_NOTE_OFF)

    chosen = user_models.get(user_id, DEFAULT_MODEL)
    turn = [{"role": "user", "content": user_message}]
    all_reasoning = []
    timeout = httpx.Timeout(connect=30, read=600, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as client:
        for round_no in range(MAX_TOOL_ROUNDS + 1):
            allow_tools = use_web and round_no < MAX_TOOL_ROUNDS
            errors = []
            attempts = [chosen, chosen, FALLBACKS.get(chosen, DEFAULT_FALLBACK)]
            for n, model in enumerate(attempts):
                messages = [{"role": "system", "content": system_for(model)}, *history, *turn]
                try:
                    content, reasoning, calls = await stream_completion(
                        client, model, messages, status, allow_tools)
                    used_model = model
                    break
                except (httpx.TransportError, RetryableError) as e:
                    errors.append(f"{MODELS.get(model, model)}: {type(e).__name__} {e}"[:200])
                    if n + 1 < len(attempts):
                        nxt = attempts[n + 1]
                        status.stage = ("🔁 Сервер не ответил, повторяю…" if nxt == model
                                        else f"🔁 Модель занята, переключаюсь на {MODELS[nxt]}…")
                        status.reasoning = ""
                        await status.update(force=True)
                        await asyncio.sleep(1)
            else:
                raise RuntimeError("Сервер не ответил, попробуй ещё раз чуть позже.\n"
                                   + "\n".join(errors))
            if reasoning.strip():
                all_reasoning.append(reasoning.strip())

            if not calls:
                if not content.strip():
                    raise RuntimeError("Модель вернула пустой ответ, попробуй ещё раз.")
                history.extend(turn)
                history.append({"role": "assistant", "content": content})
                return content, "\n\n".join(all_reasoning), used_model

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

    lock = user_locks.setdefault(user_id, asyncio.Lock())
    if lock.locked():
        await update.message.reply_text("⏳ Ещё отвечаю на прошлое сообщение — это встанет следом.")
    async with lock:
        show = show_thoughts.get(user_id, True)
        status_msg = await update.message.reply_text("⏳ Думаю…")
        status = LiveStatus(status_msg, show)
        ticker = asyncio.create_task(status.ticker())
        try:
            answer, reasoning, used_model = await ask_model(user_id, user_text, status)
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
        await send_answer(update.message, answer + f"\n\n_— {MODELS.get(used_model, used_model)}_")


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
            for mid, name in MODELS.items()]
    return InlineKeyboardMarkup(rows)


async def choose_model(update: Update, context: ContextTypes.DEFAULT_TYPE):
    current = user_models.get(update.effective_user.id, DEFAULT_MODEL)
    await update.effective_message.reply_text(
        f"🤖 Сейчас: *{MODELS.get(current, current)}*\nВыбери модель:",
        parse_mode="Markdown", reply_markup=models_keyboard(current))


async def model_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    mid = query.data.split(":", 1)[1]
    if mid not in MODELS:
        await query.answer("Неизвестная модель")
        return
    user_models[query.from_user.id] = mid
    await query.answer(f"Выбрано: {MODELS[mid]}")
    await query.edit_message_text(
        f"🤖 Теперь отвечает *{MODELS[mid]}*.\nКонтекст диалога сохранён.",
        parse_mode="Markdown")


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "👋 Привет! Я ИИ-ассистент с доступом к интернету (по умолчанию DeepSeek V4.1 Flash).\n"
        "Просто напиши мне что-нибудь!\n\n"
        "🗑 «Новый диалог» внизу или /clear — начать заново\n"
        "💭 /thoughts — показывать/скрывать размышления\n"
        "🌐 /web — включить/выключить интернет\n"
        "🤖 /model — сменить ИИ",
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
