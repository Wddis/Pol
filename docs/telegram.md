# Telegram через пульт (17.11.2026)

Владелец входит в Telegram Web (web.telegram.org/k) через пульт `remote_browser.js`. Вход — по QR-коду или номеру телефона,
вводит только владелец. Дальше Claude работает в той же вкладке через CDP: `tools/tg/tg_web.js`.

## Поднять пульт на Telegram
```
S=<scratchpad>; cd $S
: > empty_cookies.txt                       # cookies для Telegram не нужны
python3 -c "import secrets;print(secrets.token_urlsafe(18))" > rb_token
RB_TOKEN=$(cat rb_token) setsid nohup node <repo>/tools/yt/remote_browser.js empty_cookies.txt 8765 9222 \
  "https://web.telegram.org/k/" > rb.log 2>&1 < /dev/null &
curl -s -o qr.png "http://127.0.0.1:8765/shot?t=$(cat rb_token)"   # экран с QR → владельцу
```
QR-код живёт ~30 с: присылать свежий (вырезать квадрат из скриншота). Владелец: Telegram → Настройки → Устройства →
Подключить устройство. Для живого пульта с телефона — Dev Tunnels (docs/publishing-methods.md).

## Сохранить вход (сразу после входа)
`node tools/tg/tg_web.js session-save $S/tg_session.json`. Вход хранится в localStorage (`dc*_auth_key`, `user_auth`).
Файл — это ПОЛНЫЙ доступ к аккаунту: только scratchpad, не в репо, не в лог. После перезапуска контейнера:
поднять пульт (как выше), затем `tg_web.js session-restore $S/tg_session.json` — без нового QR.
Отозвать доступ владелец может в Telegram → Устройства (сессия «Telegram Web, Linux»).

## Команды `tools/tg/tg_web.js [cdp:9222] …`
| Команда | Что делает |
|---|---|
| `me` | id и DC вошедшего аккаунта |
| `chats [N] [--folder Имя]` | список чатов: peer id, 📌, [непрочитано], название, время, последнее сообщение |
| `read <peer> [N]` | последние N сообщений (peer = id, @username или название) |
| `search <текст>` | глобальный поиск (чаты, каналы, сообщения) → peer id |
| `send <peer> <текст> --yes` | отправка — ТОЛЬКО по команде владельца; без `--yes` — пробный прогон |
| `shot [file.png]` | скриншот вкладки Telegram |
| `session-save / session-restore <file>` | сохранить / восстановить вход |

## Опыт
- Escape в Telegram Web открывает режим поиска, а не закрывает окна — инструмент возвращается к списку кнопкой «назад».
- Окно подсказки «Appearance» после входа невидимо перекрывает клики — инструмент закрывает его сам.
- Открыть чат = прочитать его у владельца (как в обычном клиенте). Чужие чаты без нужды не открывать.
- `b.close()` при CDP-подключении только отключается, браузер пульта продолжает работать.
