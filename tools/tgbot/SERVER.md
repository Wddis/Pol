# Постоянный бесплатный сервер для бота — Oracle Cloud «Always Free»

Почему Oracle: навсегда бесплатно 4 ядра ARM (Ampere A1), 24 ГБ памяти и 200 ГБ диска — хватает и на расшифровку,
и на рендер 1080p60, и на ИИ-помощника. Другие бесплатные варианты не подходят: засыпают без трафика (Render,
Hugging Face Spaces), слабые (Google e2-micro: 1 ГБ памяти) или запрещают такое использование (GitHub Actions).

## Что сделать владельцу (≈15 минут, один раз)
1. Регистрация: https://signup.oracle.com — email, телефон, банковская карта (только проверка личности, списаний нет;
   «Always Free» ресурсы бесплатны навсегда). Регион выбирай ближе: Frankfurt / Amsterdam / Stockholm.
2. Консоль → **Compute → Instances → Create instance**:
   - Image: **Canonical Ubuntu 24.04**;
   - Shape: **Ampere → VM.Standard.A1.Flex, 4 OCPU, 24 GB** (помечено «Always Free eligible»);
     если пишет «Out of capacity» — попробуй позже или 2 OCPU / 12 GB;
   - Boot volume: **150–200 GB**;
   - SSH keys: «Generate a key pair» → скачай оба ключа (или просто пользуйся Cloud Shell, см. ниже).
3. Открой терминал сервера: в консоли Oracle кнопка **Cloud Shell** (иконка `>_` сверху) →
   `ssh ubuntu@<Public IP инстанса>` (или «Connect» через Instance → Console connection).
4. Выполни ОДНУ команду и введи токен бота, когда спросит (ввод скрыт):
   ```
   curl -fsSL https://raw.githubusercontent.com/Wddis/Pol/ccr-afb88262-upss12/tools/tgbot/deploy.sh | sudo bash
   ```
   Первый раз ставится 10–20 минут (модели распознавания речи, ИИ-модель 2 ГБ, браузер для Studio).
5. В Telegram: `/pair <код>` (если не ввёл свой id) → `/cookies` и пришли файл cookies → `/panel` → `/start`.

## Что получается
- Бот — служба systemd: перезапускается сам при падении и после перезагрузки сервера.
- Каждые 30 минут сервер подтягивает обновления из ветки `ccr-afb88262-upss12` — всё, что Claude улучшит по логам,
  само приезжает на сервер.
- Токен лежит только в `/etc/t2x2bot.env` на сервере (доступ только у root и службы), не в репозитории.
- Копия бота в облачной сессии Claude запущена с `BOT_YIELD=1`: как только сервер начнёт работу, она сама уступит
  (Telegram не даёт двум копиям читать одного бота).

## Обслуживание
- Журнал: `journalctl -u t2x2bot -f` · перезапуск: `sudo systemctl restart t2x2bot`
- Логи для Claude: `/home/t2x2/t2x2work/logs/*.jsonl` или `/logs` в Telegram (переслать в сессию).
- Новый токен: `sudo nano /etc/t2x2bot.env` → `sudo systemctl restart t2x2bot`.
- Риск: Google может попросить подтверждение входа с нового IP сервера — тогда пришли свежие cookies (`/cookies`);
  пароли и капчи бот не вводит.
