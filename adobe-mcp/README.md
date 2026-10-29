# Premiere Pro / After Effects через MCP Higgsfield

## Почему это нельзя запустить в облачном контейнере Claude

Проверено фактически (Ubuntu 24.04, Node 24.22.1, пакеты `@higgsfield_org/premiere-mcp@0.1.3`
и `fnf-after-effects-mcp@0.1.6`):

- оба сервера устанавливаются и отвечают на MCP-рукопожатие (`tools/list` работает);
- `fnf-premiere install-bridge` → `Premiere requires macOS or Windows`;
- `fnf-premiere probe` / `pr_document_info` → `BRIDGE_OFFLINE`;
- `fnf-after-effects doctor` → `platform: linux` — fail; `ae_project_info` → `AE_NOT_FOUND`;
- в контейнере нет `/dev/kvm`, поэтому поднять Windows-VM внутри него нельзя.

Оба сервера работают **только по stdio** и **только на том же компьютере, где запущен Adobe**:
Premiere общается с сервером через подписанное CEP-расширение и локальные файлы
(`~/.higgsfield-adobe/premiere-native`), After Effects — через запуск `AfterFX.exe -r <bootstrap.jsx>`
и локальный «почтовый ящик» `%USERPROFILE%\.higgsfield\after-effects\runtime`.
Значит, MCP-клиент (Claude Desktop или Claude Code) тоже должен быть на этом же Windows-ПК.

## Минимальный рабочий вариант для Android

1. Облачный Windows-ПК с GPU (например, Shadow PC, Azure NV-серия, AWS G4/G5 с Windows,
   Paperspace) или свой домашний Windows-компьютер.
2. Подключение с Android через приложение **Windows App** (Microsoft Remote Desktop) или
   клиент провайдера (Shadow и т. п.).
3. На этом ПК: Creative Cloud + Premiere Pro + After Effects (нужна подписка Adobe),
   Claude Desktop (вход в ваш аккаунт).
4. Скачать этот репозиторий и выполнить в PowerShell:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\adobe-mcp\setup-windows.ps1
   ```

   Скрипт ставит Node.js 24 (через winget), оба пакета, выполняет `doctor`, `install-bridge`,
   `config`, находит `AfterFX.exe` (в т. ч. в нестандартной папке → `AE_MCP_EXE`) и добавляет
   записи в `%APPDATA%\Claude\claude_desktop_config.json`, сохраняя существующие (с резервной копией).
   Если установлен Claude Code CLI — регистрирует серверы и там (`claude mcp add --scope user`).
5. Перезапустить Premiere (мост), открыть проекты, выполнить `fnf-premiere probe`,
   перезапустить Claude Desktop и попросить вызвать `pr_document_info` и `ae_project_info`.

Сервер не нужно открывать в интернет: клиент и Adobe находятся на одной машине.

## Windows‑VM прямо в облачной сессии Claude (без своего ПК)

`adobe-mcp/vm/start-vm.sh` поднимает в контейнере сессии Windows 11 IoT Enterprise LTSC 2024
Evaluation (официальный образ Microsoft; `WIN_VERSION=11e` — обычная Enterprise, на ~5 ГБ
больше) через
[dockur/windows](https://github.com/dockur/windows), автоматически ставит туда Node 24, оба
MCP‑пакета и OpenSSH и регистрирует серверы в Claude Code через `ssh` (`mcp-ssh.sh`).
Экран VM: `http://127.0.0.1:8006`, ssh: порт 2222.

Ограничения: без `/dev/kvm` это чистая эмуляция (примерно в 10 раз медленнее, установка
2–4 часа), видеокарты нет, лимит диска сессии около 30 ГБ. Ознакомительная Windows должна
активироваться онлайн, а через перехват TLS песочницы это не проходит (`slmgr /ato` →
`0x8004FE93`). Поэтому любая ознакомительная сборка здесь показывает «License is expired» и
выключается примерно раз в час (`slmgr /rearm` не помогает); контейнер запущен с
`--restart unless-stopped` и поднимает её снова за несколько минут.

### Сохранение между сессиями

Контейнер сессии временный. `vm-image.sh save|restore` сохраняет диск VM в **приватный**
датасет Hugging Face и восстанавливает его; `start-vm.sh` делает restore сам, если заданы
переменные окружения `HF_TOKEN` (токен с правом записи) и `HF_VM_REPO` (например,
`user/win-adobe-vm`). Их задают в настройках облачной среды, не в чате. Образ содержит сессию
входа Adobe, поэтому скрипт отказывается выгружать его в не приватный репозиторий.

Образ передаётся потоком частями по 2 ГБ (`upload-part.py`), чтобы архив и распакованный
диск не лежали на диске сессии одновременно. Порядок: первая сессия с `HF_TOKEN` ставит
всё и делает `vm-image.sh save`; следующие восстанавливают образ примерно за 10–15 минут.
