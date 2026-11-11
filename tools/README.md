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
- Зависимости: `pip install yt-dlp faster-whisper`, ffmpeg.
