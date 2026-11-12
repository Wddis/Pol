# work/ — рабочие данные по стримам (чтобы пересобрать ролики после потери scratchpad)

Каждая папка = один VOD Twitch: `<дата>_vod<ID>/`.
- `*_plan_off<N>.json` — план монтажа для `tools/edit2.py`. Время в плане отсчитывается от файла, вырезанного
  `python3 tools/vodcut.py <ID> 1080p60 <segs> <N> <конец> ev.mp4` (N — в имени файла). Сегменты:
  `SEGS_ONLY=1 python3 tools/vod.py <ID> 1080p60 video.mp4 <N> <конец>`.
- `*_studio.json` — метаданные загрузки для `tools/yt/studio_upload.js cdp:9222` (пути `./…` поправить на свои,
  поле `thumb` — обложка владельца).
- `*_meta.md` — название/описание/теги/оригинал в читаемом виде.
- `transcript.txt` — расшифровка всего стрима (VOD-время в секундах).
Статус каждого ролика — `docs/STATUS.md`, сводка и опыт — `docs/HANDOFF.md`.
