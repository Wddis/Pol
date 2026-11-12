# Интро канала «Двойная резка / T2x2»

Правило (владелец, 12.11.2026): интро ставится в начало КАЖДОГО НОВОГО ОСНОВНОГО видео. Shorts — без интро.

- `intro_t2x2.mp4` — готовое интро, 1920x1080 @ 60 fps, H.264 High yuv420p, AAC 48 kHz stereo, 8,7 с, −14 LUFS.
  Поставить: `tools/add_intro.sh T2x2_X.mp4 T2x2_X_intro.mp4` (без перекодирования, если параметры совпадают —
  у рендера `edit2.py` совпадают).
- `head_t2x2.png` — голова T2x2 с прозрачным фоном (из `head_t2x2_source_owner.png`, фото от владельца,
  фиолетовый фон вырезан по цвету + убрана фиолетовая кайма).
- `voice_budka.wav` — фраза в начале: «это не квартира, это будка» (стрим 08.11, VOD 3328.78–3330.02, без мата).
- `avatar_channel.png` — аватар канала для карточки «Больше нарезок на канале».

Пересобрать (поменять тексты карточек `CARDS`, фразу, голову):
```
npm pack @fontsource/montserrat@5.2.5 && tar xzf fontsource-montserrat-5.2.5.tgz   # шрифт (package/files)
python3 tools/intro/make_intro.py assets/intro/head_t2x2.png assets/intro/avatar_channel.png package/files intro.mp4 assets/intro/voice_budka.wav
ffmpeg -i intro.mp4 -c:v copy -af loudnorm=I=-14:TP=-1.0:LRA=9 -ar 48000 -c:a aac -b:a 256k intro_t2x2.mp4
```
Структура: голова «говорит» фразу на чёрном → вжух + бас-удар, круглое раскрытие фона с волнистыми полосами
(цвет плавно идёт по кругу) → карточки печатаются по словам: «T2X2 · АНТОН / реакции, игры и общение с чатом»,
«twitch.tv/T2X2», «Больше нарезок на канале «Двойная резка»» → затемнение. Звук и музыка синтезированы
(без чужих сэмплов — Content ID не сработает).
