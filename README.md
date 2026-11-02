# Pol — авто-выдача Playerok

В репозитории два процесса:

| Процесс | Что делает |
|---|---|
| `python -m autodelivery` | **авто-выдача Playerok** + Telegram-админка склада |
| `python bot.py` | старый AI-чат-бот (NVIDIA / Kimi) |

## Схема

```
 Покупатель оплатил товар на Playerok
              │
              ▼
  ┌──────────────────────────┐   WebSocket (секунды)
  │  Engine                  │◄─ + опрос сделок каждые 20 с (страховка)
  │  1. сделка PAID?         │
  │  2. название товара ──►  cards.yaml: ищем карточку по словам / item_ids
  │  3. берём N строк ────►  SQLite-склад (атомарно, сделка = 1 раз)
  │  4. шаблон → чат сделки  │
  │  5. отмечаем «отправлено»│
  └──────────┬───────────────┘
             ▼
  Telegram-админка: ✅ выдано / 📭 склад пуст / ❓ не распознано / 💬 жалоба
```

Что бывает со сделкой:

| Статус | Когда | Что делать |
|---|---|---|
| ✅ `delivered` | товар выдан | ничего |
| 📭 `waiting_stock` | склад пуст; покупателю написали «скоро выдадим» | `/add <склад>` — выдастся сам |
| ✋ `manual` | ручная карточка (Robux); покупателю ушла инструкция | выдать руками в чате |
| ❓ `unmatched` | ни одна карточка не подошла к названию | `/give <deal_id> <карточка>` |
| ⚠️ `failed` | строки списаны, но сообщение не ушло | `/resend <deal_id>` |

## Карточки (`cards.yaml`)

| id | Товар | Тип | Склад, формат строки |
|---|---|---|---|
| `roblox_empty` | Roblox пустой | stock ×1 | `roblox_empty`: `login:password` |
| `roblox_empty_x5` | набор 5 Roblox | stock ×5 | тот же `roblox_empty` |
| `roblox_old` | Roblox старый (год) | stock ×1 | `roblox_old`: `login:password:year` |
| `roblox_full` | Roblox + почта | stock ×1 | `roblox_full`: `login:password:email:email_password` |
| `robux_gamepass` | Robux через геймпасс | manual | — |
| `steam_empty` | Steam пустой + почта | stock ×1 | `steam_empty`: `login:password:email:email_password` |
| `genshin_starter` | Genshin реролл | stock ×1 | `genshin_starter`: `login\|password\|server\|characters` |
| `game_key` | ключ / промокод | stock ×1 | `game_key`: строка целиком |
| `roblox_guide` | гайд (бесконечный) | static | — |

У каждой карточки есть готовый текст объявления (`listing`). В Telegram:
`/listing roblox_empty` — копируете название и описание на Playerok.

**Важно:** название объявления на Playerok должно содержать слова из `match`
карточки (готовые названия из `listing` уже подходят). Новую карточку
добавляете в `cards.yaml` по образцу и отправляете `/reload`.

## Запуск

1. Отдельный бот для админки у @BotFather → `ADMIN_BOT_TOKEN`.
2. Свой Telegram id (@userinfobot) → `ADMIN_IDS`.
3. Токен Playerok: войдите на playerok.com → DevTools (F12) → Application →
   Cookies → `token` → `PLAYEROK_TOKEN`.
4. Переменные — как в `.env.example` (на хостинге — в настройках Variables).
5. `pip install -r requirements.txt && python -m autodelivery`
6. Напишите админ-боту `/start`, затем `/add roblox_empty` и пришлите строки
   или `.txt` файл.

База лежит в `data/autodelivery.db` — на хостинге с «эфемерным» диском
подключите постоянный том (volume) к `data/`, иначе склад обнулится при
перезапуске.

## Команды админки

`/stock` `/add` `/done` `/format` `/clear` · `/cards` `/listing` `/reload` ·
`/sales` `/waiting` `/give` `/resend` · `/pause` `/resume` — подробности в `/help`.

## Ограничения

- Playerok не даёт официального API; используется неофициальная библиотека
  [PlayerokAPI](https://github.com/th1ks/PlayerokAPI). Если площадка поменяет
  схему, что-то может сломаться — бот пришлёт ошибку в Telegram.
- Отметка «отправлено» идёт через `updateDeal(status: SENT)`. Если она не
  сработает, товар всё равно выдан, а бот попросит отметить сделку вручную.
