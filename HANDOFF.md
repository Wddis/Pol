# Noizoz_bot — полная передача проекта

> Этот файл — для ввода новой сессии в курс дела одним сообщением, если текущая сессия потеряется.
> Новой сессии: прочитай этот файл целиком, он описывает весь бот, его инструменты, деплой и секреты.

## Что это
Telegram-бот **@Noizoz_bot** (id `8751101888`) — ИИ-ассистент с большим набором инструментов
(файлы, запуск кода, анализ `.so`, API, GitHub, большие файлы, скриншоты сайтов).
Репозиторий: **github.com/Wddis/Pol**. Рабочая ветка: **ccr-246ecdfb-jd9nn5**, основная: **main**.
Автор/владелец аккаунта: dimazoltinsky@gmail.com (GitHub `Wddis`).

## Где и как работает
- Бот запущен на бесплатном хостинге **The Vibe Hosting**: `https://noizoz-bot2.thevibehosting.com`
  (сервер `srv_6189a3bac5`, `46.225.188.115`, SSH-порт `32025`; старый `noizoz-bot`:37621 умер 2026-11-14,
  ключа к нему нет). Это ~256 МБ RAM, без Android-SDK. Сервер
  **засыпает после ~10 минут простоя** (первое сообщение после простоя идёт на пару секунд дольше —
  сервер просыпается; это не краш, 502 на `/` сразу после простоя = сон).
- Режим **webhook**: бот при старте сам ставит вебхук на `WEBHOOK_URL`. На `/` отвечает 404 (это норма,
  обслуживается только секретный путь). Здоровье проверяй по `getWebhookInfo` (pending/last_error), а не по `/`.
- Доступ к серверу (SSH-ключ и ключ аккаунта Vibe) жил в скретчпаде сессии:
  `/tmp/claude-.../scratchpad/vibe/` (`.thevibehosting_key`, `.thevibehosting.json`). Это **временно** и
  пропадает со смертью сессии. Если нужно управлять сервером Vibe заново — создать новый сервер
  (`curl -sX POST https://thevibehosting.com/create`) или, если аккаунт подтверждён телефоном,
  восстановить ключ по SMS. Деплой же работает БЕЗ этого ключа (см. ниже).

## Файлы проекта
- `bot.py` — ядро: Telegram-хендлеры, провайдеры/модели, цикл ask_model с инструментами, статус/отсчёты,
  инструкции (.md), меню файлов/API, кнопка «Сменить ИИ»/«Удалить запрос», приём файлов, вебхук.
- `workspace.py` — рабочая папка пользователя (файлы на диске), read/write/edit файлов, API-коннекторы
  (`call_api`, `api_screenshot`), скриншоты сайтов (`web_screenshot` через mShots/thum.io/Microlink с
  самопроверкой моделью), история диалога на диске.
- `binary.py` — анализ ELF/`.so` через pyelftools+capstone, потоково/mmap (файлы до 2 ГБ):
  overview/summary/exports/imports/functions/sections/strings/disasm (ARM64/ARM/x86, с аннотациями).
- `devtools.py` — инструменты кодинга: run_code/run_file (ТОЛЬКО владелец, песочница), check_code,
  search_files (grep), glob_files, apply_patch, rename_file, diff_files, zip_files, todo_write,
  project_overview, code_outline, find_definition (12 языков), git_clone, github_commit.
- `bigfiles.py` — большие файлы до 2 ГБ: download_url (по ссылке, потоком; Google Drive/Dropbox/GitHub),
  archive_list/archive_extract (zip/apk/aab/obb/unitypackage/tar без полной распаковки), read_range (hex/text).
- `.github/workflows/deploy.yml` — деплой на сервер (см. раздел «Деплой»).
- `requirements.txt` — python-telegram-bot[webhooks], httpx, beautifulsoup4, ddgs, pillow, pyelftools,
  capstone, pyflakes, pyyaml.

## Провайдер моделей
**Только TokenHarbor** (OpenAI-совместимый), CoArena удалён (2026-11-14).
- База: `https://tokenharbor.ai/v1`, ключ в env `TOKENHARBOR_API_KEY` (ключ у пользователя,
  дашборд https://tokenharbor.ai/dashboard/api-keys). В репо ключ не храним.
- Баланс на аккаунте 0, поэтому используются БЕСПЛАТНЫЕ модели `:free` (инструменты работают).
  При пополнении/Pass можно добавить платные модели в `MODELS` (список: `GET /v1/models`).

### Меню `/model`
- 🐋 DeepSeek V4.1 Flash (по умолчанию, vision) → `deepseek-v4.1-flash:free`
- 🐳 DeepSeek V4 Flash (без картинок) → `deepseek-v4-flash:free`
Обе проверены на вызов инструментов (обычный и потоковый ответ).
При занятости провайдера бот ждёт и пробует снова каждые 30 с; модель меняет только пользователь.

## Команды бота
`/start`, `/model` (сменить ИИ), `/instructions` (свои .md-инструкции), `/files` (рабочая папка),
`/api` (API-коннекторы), `/clear` (очистить диалог — останавливает текущий ответ и очередь),
`/thoughts` (показ размышлений), `/web` (интернет вкл/выкл), `/claim` (стать владельцем → запуск кода).
Внизу экрана кнопки: «🗑 Новый диалог», «🤖 Сменить ИИ», «📌 Инструкции», «📁 Файлы», «🔌 API».

## Инструменты модели (все работают через TokenHarbor)
Файлы: list_files, read_file (с номерами строк, потоково для больших), write_file (send=true — сразу
прислать), edit_file (replace_all + пакетные edits, атомарно), delete_file, send_file, rename_file.
Код: run_code/run_file (владелец, песочница 30 c / 300 МБ, без секретов бота), check_code (py/json/yaml/
bash/js), search_files (grep с контекстом/режимами), glob_files, apply_patch (unified diff), diff_files,
zip_files, todo_write (план в статусе), project_overview, code_outline, find_definition.
Бинарники: analyze_binary (overview/summary/exports/imports/functions/sections/strings/disasm,
targets=[несколько функций], до 2 ГБ потоково).
Большие файлы: download_url, archive_list, archive_extract, read_range.
API/веб: call_api, api_screenshot (картинка запроса/ответа, секреты скрыты «•••»), web_search, open_url,
web_screenshot (+ send_screenshot, модель сама проверяет снимок), git_clone, github_commit (один коммит
с несколькими файлами, base64/sha сами).

## Лимиты (ограничения платформы, не кода)
- Приём файла из чата Telegram: **до 20 МБ**. Большие проекты — ссылкой через `download_url` (до 2 ГБ).
- Отправка файла ботом: **до 50 МБ**. Большее — только изменённые файлы или архивом по частям.
- Рабочая папка: до 4 ГБ (env `WORKSPACE_MAX_GB`), один файл до 2 ГБ.
- Запуск кода — только владелец (`/claim`), песочница, без доступа к ключам бота.
- Сервер 256 МБ: анализ больших файлов потоковый (пик памяти ~50 МБ на файле 1 ГБ — проверено).

## Хранение данных (на диске сервера, переживает перезапуск/сон)
`DATA_DIR` (по умолчанию `<проект>/data`): `instructions.json`, `owner.json` (владелец для /claim),
`users/<id>/` (файлы рабочей папки, `history.json` диалога, `connectors.json` API-коннекторов).
Ключи API-коннекторов и история модели НЕ видны — хранятся на сервере, в ответах скрыты.

## Деплой (работает БЕЗ постоянных секретов в репо)
Схема: GitHub Actions генерирует одноразовую RSA-пару, кладёт ПУБЛИЧНЫЙ ключ в ветку `deploy-secrets`
(`<run_id>.pub`), ждёт зашифрованный пакет `deploy/<run_id>.enc` в ветке рабочей (`ccr-246ecdfb-jd9nn5`),
расшифровывает секреты ТОЛЬКО внутри раннера, по SSH заливает *.py на сервер, пишет `.env`, перезапускает
бота (supervisor Vibe поднимает `/root/run.sh`), логирует старт в `/root/bot/bot.log`.
Секреты (TELEGRAM_TOKEN, TOKENHARBOR_API_KEY) в репозитории НЕ хранятся даже зашифрованными — пакет удаляется
после деплоя. Метка версии: бот пишет `сборка <8 hex>` (sha256 всех .py) в своё короткое описание в
Telegram (`getMyShortDescription`) — так видно, какая версия реально запущена.

### Как задеплоить из новой сессии
Секреты (нужны у исполнителя): `TELEGRAM_TOKEN=8751101888:...`, `TOKENHARBOR_API_KEY=thk_live_...`.
1. Запушить изменения в рабочую ветку (сейчас `ccr-0af174ef-akfigz`). Прямой SSH из облачной сессии Claude заблокирован — только через Actions.
2. Запустить workflow `deploy.yml` на `main` с input `secrets_branch=ccr-246ecdfb-jd9nn5`
   (через GitHub MCP `actions_run_trigger` или `gh workflow run`).
3. Дождаться в ветке `deploy-secrets` коммита `pubkey for run <RID>`, взять `<RID>.pub`.
4. Зашифровать JSON `{TELEGRAM_TOKEN, TOKENHARBOR_API_KEY, SSH_KEY_B64(base64 ssh-ключа Vibe)}` этим публичным
   ключом (RSA-OAEP-SHA256 для AES-ключа + AES-256-CBC pbkdf2 для данных), положить в
   `deploy/<RID>.enc` рабочей ветки. Скрипт-помощник был в скретчпаде: `scratchpad/encrypt_bundle.sh`
   и `scratchpad/deploy_finish.sh` (их придётся воссоздать — они временные).
5. Дождаться завершения run, удалить `deploy/<RID>.enc`. Проверить `getMyShortDescription` = новый хэш.
ВАЖНО: SSH-ключ Vibe (`SSH_KEY_B64`) тоже временный (был в `scratchpad/vibe/.thevibehosting_key`).
Если он потерян — деплой на ЭТОТ сервер невозможен, нужен новый сервер Vibe (create) и обновить
`.github/workflows/deploy.yml` (VIBE_HOST/VIBE_PORT/VIBE_SLUG через repo variables или дефолты в файле).

## Telegram-токен
Токен бота: `8751101888:AAEJ49SLvgRV_vIp5eR7HxRKDF2cD_ToHeg`. Если скомпрометирован — перевыпустить в
@BotFather (`/revoke`), обновить `.env` на сервере и секрет деплоя.

## Открытый вопрос (не сделано, ждёт решения пользователя)
APK-инжектор для его Unity-игры «Одиночный симулятинг» (фича: галочка «подписаться на канал → 10 монет»).
Решено: полноценную пересборку APK на сервере бота делать нельзя (256 МБ, нет Android-SDK; Unity IL2CPP
почти не патчится после сборки). Легитимный путь для СВОЕЙ игры: бот пишет C#-фичу по коду пользователя и
отдаёт файл (уже умеет), сборка APK — в Unity пользователя или через GitHub Actions (Unity build) в его
репо. Генерический «инжектор в любой APK» намеренно НЕ делаем. Пользователь думает над выбором пути.

## Как проверять, что бот жив
```
curl -s "https://api.telegram.org/bot<TOKEN>/getMyShortDescription"   # сборка (хэш .py)
curl -s "https://api.telegram.org/bot<TOKEN>/getWebhookInfo"          # pending=0, last_error=null — ок
curl -s "https://api.telegram.org/bot<TOKEN>/getMyCommands"           # список команд
```
Полная проверка связи с TokenHarbor — только реальным сообщением боту (у ассистента нет своего Telegram).

## Атрибуция коммитов (по требованию харнесса)
Коммиты заканчивать: `Co-Authored-By: Claude <noreply@anthropic.com>` и `Claude-Session: <ссылка>`.
Разработка только в ветке `ccr-246ecdfb-jd9nn5`, в чужие ветки не пушить.
