import os
import requests
import json
from telegram import (Update, InlineKeyboardButton, InlineKeyboardMarkup,
                      ReplyKeyboardMarkup, BotCommand)
from telegram.ext import (ApplicationBuilder, MessageHandler, CommandHandler,
                           CallbackQueryHandler, filters, ContextTypes)

NVIDIA_API_KEY = os.environ["NVIDIA_API_KEY"]
TELEGRAM_TOKEN = os.environ["TELEGRAM_TOKEN"]
MODEL = os.environ.get("MODEL", "deepseek-ai/deepseek-v4.1-flash")

chat_histories = {}

SYSTEM_PROMPT = """Ты полезный ассистент. Отвечай на русском языке.
Используй Telegram Markdown форматирование редко и уместно:
- *жирный* — только для ключевых терминов или важных выводов
- _курсив_ — для примеров или лёгких акцентов
Не злоупотребляй — большинство текста должно быть обычным."""

def clear_button():
    return InlineKeyboardMarkup([[
        InlineKeyboardButton("🗑 Очистить диалог", callback_data="clear")
    ]])

CLEAR_TEXT = "🗑 Новый диалог"

def main_keyboard():
    # Постоянная кнопка внизу экрана
    return ReplyKeyboardMarkup([[CLEAR_TEXT]], resize_keyboard=True, is_persistent=True)

async def reset_dialog(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat_histories.pop(update.effective_user.id, None)
    await update.effective_message.reply_text(
        "🗑 *Диалог очищен.* Начнём с чистого листа!",
        parse_mode="Markdown",
        reply_markup=main_keyboard()
    )

def ask_nvidia(user_id, user_message):
    if user_id not in chat_histories:
        chat_histories[user_id] = []

    chat_histories[user_id].append({"role": "user", "content": user_message})

    headers = {
        "Authorization": f"Bearer {NVIDIA_API_KEY}",
        "Accept": "text/event-stream"
    }
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            *chat_histories[user_id]
        ],
        "max_tokens": 16384,
        "temperature": 1.00,
        "top_p": 1.00,
        "stream": True,
        "chat_template_kwargs": {"thinking": True},
    }

    response = requests.post(
        "https://integrate.api.nvidia.com/v1/chat/completions",
        headers=headers,
        json=payload,
        stream=True,
        timeout=300
    )
    if response.status_code != 200:
        chat_histories[user_id].pop()
        return f"⚠️ Ошибка API ({response.status_code}): {response.text[:300]}"

    full_answer = ""
    for line in response.iter_lines():
        if line:
            decoded = line.decode("utf-8")
            if decoded.startswith("data: ") and decoded != "data: [DONE]":
                try:
                    data = json.loads(decoded[6:])
                    delta = data["choices"][0]["delta"].get("content", "")
                    if delta:
                        full_answer += delta
                except Exception:
                    pass

    if not full_answer:
        chat_histories[user_id].pop()
        return "⚠️ Модель вернула пустой ответ, попробуй ещё раз."
    chat_histories[user_id].append({"role": "assistant", "content": full_answer})
    return full_answer

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.message.from_user.id
    user_text = update.message.text
    if user_text == CLEAR_TEXT:
        await reset_dialog(update, context)
        return

    thinking = await update.message.reply_text("⏳ Думаю...")
    answer = ask_nvidia(user_id, user_text)
    await thinking.delete()

    try:
        await update.message.reply_text(
            answer,
            parse_mode="Markdown",
            reply_markup=clear_button()
        )
    except Exception:
        # Markdown от модели может быть невалидным для Telegram — шлём как обычный текст
        await update.message.reply_text(answer, reply_markup=clear_button())

async def clear_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    user_id = query.from_user.id
    chat_histories.pop(user_id, None)
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
        "👋 Привет! Я готов отвечать на твои вопросы.\nПросто напиши мне что-нибудь!\n\n"
        "Чтобы начать заново — нажми «🗑 Новый диалог» внизу или отправь /clear.",
        reply_markup=main_keyboard()
    )

async def post_init(application):
    await application.bot.set_my_commands([
        BotCommand("clear", "🗑 Очистить контекст диалога"),
        BotCommand("start", "Начать"),
    ])

app = ApplicationBuilder().token(TELEGRAM_TOKEN).post_init(post_init).build()
app.add_handler(CommandHandler("start", start))
app.add_handler(CommandHandler(["clear", "new", "reset"], reset_dialog))
app.add_handler(CallbackQueryHandler(clear_callback, pattern="clear"))
app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
app.run_polling()