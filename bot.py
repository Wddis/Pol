import requests
import json
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import (ApplicationBuilder, MessageHandler, CommandHandler,
                           CallbackQueryHandler, filters, ContextTypes)

NVIDIA_API_KEY = "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"
TELEGRAM_TOKEN = "8751101888:AAG4JOjtY4TfatM2QvSTOOz7j_LWMT4AUm4"

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

def ask_nvidia(user_id, user_message):
    if user_id not in chat_histories:
        chat_histories[user_id] = []

    chat_histories[user_id].append({"role": "user", "content": user_message})

    headers = {
        "Authorization": f"Bearer {NVIDIA_API_KEY}",
        "Accept": "text/event-stream"
    }
    payload = {
        "model": "moonshotai/kimi-k2.5",
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
        stream=True
    )

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

    chat_histories[user_id].append({"role": "assistant", "content": full_answer})
    return full_answer

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.message.from_user.id
    user_text = update.message.text

    thinking = await update.message.reply_text("⏳ Думаю...")
    answer = ask_nvidia(user_id, user_text)
    await thinking.delete()

    await update.message.reply_text(
        answer,
        parse_mode="Markdown",
        reply_markup=clear_button()
    )

async def clear_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    user_id = query.from_user.id
    chat_histories[user_id] = []
    await query.answer("Диалог очищен!")
    await query.edit_message_reply_markup(reply_markup=None)
    await context.bot.send_message(
        chat_id=query.message.chat_id,
        text="🗑 *Диалог очищен.* Начнём с чистого листа!",
        parse_mode="Markdown"
    )

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "👋 Привет! Я готов отвечать на твои вопросы.\nПросто напиши мне что-нибудь!"
    )

app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
app.add_handler(CommandHandler("start", start))
app.add_handler(CallbackQueryHandler(clear_callback, pattern="clear"))
app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
app.run_polling()