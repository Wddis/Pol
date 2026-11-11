# Инструменты нарезки

- `rec.py <channel> <format> <outdir> <seconds>` — запись live Twitch по сегментам
  (формат `1080p60__source_` или `1080p60`). Устойчив к обрывам прокси.
- Склейка: `ls $PWD/live/0*.mp4 | sed 's/^/file /' > list.txt`,
  `ffmpeg -f concat -safe 0 -i list.txt -c copy full.ts`,
  `ffmpeg -fflags +genpts -i full.ts -map 0:v -map 0:a -c copy -avoid_negative_ts make_zero full.mp4`
  (ремукс в mp4 обязателен — иначе неточная перемотка).
- `stt.py in.wav out.txt` — расшифровка речи (faster-whisper small, ru).
- `vod.py` — параллельная загрузка VOD по сегментам; `vodcut.py` — сборка отрезка из скачанных сегментов (без полной копии на диске).
- `edit2.py` — быстрый рендер длинных нарезок (зум только на нужных кусках, громкость один раз в конце).
- `edit.py src.mp4 plan.json out.mp4` — монтаж по плану: куски, зум на вебку
  (anchor 0..1, напр. [0,1] = левый нижний угол), блюр-боксы, loudnorm, 1080p60.
- `r2_upload_parts.py` — параллельная заливка частей (64 МБ) по presigned-ссылкам BrightBean.
- `shorts_template.py` + `shorts_render.sh` — шортсы 1080x1920 в стиле шапки канала (заголовок, экран, вебка, «ПОЛНАЯ ЗАПИСЬ НА КАНАЛЕ»).
- `indiepost.py <tool> <json>` — IndiePost через MCP-эндпоинт (ключ подставляет прокси окружения): init_media_upload → PUT → confirm_media_upload → create_post.
- Полный порядок работы: `docs/playbook-vod.md`.
- Зависимости: `pip install yt-dlp faster-whisper`, ffmpeg.
- `yt/yt_upload.py` + `yt/session_token.js` — загрузка на YouTube БЕЗ API и квот через внутренний API Studio
  (пакет youtube-up 0.5.6 в venv; sessionToken берётся через Playwright вместо selenium-wire).
  Нужен cookies.txt (Netscape) аккаунта с доступом к каналу (лучше отдельный аккаунт с ролью «Редактор»).
  Cookies НЕ коммитить и не хранить в репо.
  Установка: `python3 -m venv ytenv && ytenv/bin/pip install youtube-up==0.5.6`
  Запуск: `ytenv/bin/python tools/yt/yt_upload.py cookies.txt meta.json`
