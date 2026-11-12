# Интро канала «Двойная резка / T2x2»

Правило (владелец, 12.11.2026): интро ставится в начало КАЖДОГО НОВОГО ОСНОВНОГО видео. Shorts — без интро.

- `intro_t2x2.mp4` — готовое интро, 1920x1080 @ 60 fps, H.264 High yuv420p, AAC 48 kHz stereo, 9,8 с, −14 LUFS.
  Поставить: `tools/add_intro.sh T2x2_X.mp4 T2x2_X_intro.mp4` (без перекодирования, если параметры совпадают —
  у рендера `edit2.py` совпадают).
- `head_t2x2.png` — голова T2x2 с прозрачным фоном (из `head_t2x2_source_owner.png`, фото от владельца,
  фиолетовый фон вырезан по цвету + убрана фиолетовая кайма).
- `voice_privet.wav` — приветствие в начале: «Здравствуйте, дорогие подписчики!» (стрим 10.11, VOD 2924895000,
  303.38–304.96 с), с затуханием: обрезка сразу после слова + короткое эхо (110/230/360 мс), гаснет ~0,4 с — без обрыва.
  (Сменило старую фразу «это не квартира, это будка» по решению владельца 12.11; старое интро удалено.)
- `avatar_channel.png` — аватар канала для карточки «Больше нарезок на канале».

Пересобрать (поменять тексты карточек `CARDS`, фразу, голову):
```
npm pack @fontsource/montserrat@5.2.5 && tar xzf fontsource-montserrat-5.2.5.tgz   # шрифт (package/files)
python3 tools/intro/make_intro.py assets/intro/head_t2x2.png assets/intro/avatar_channel.png package/files intro.mp4 assets/intro/voice_privet.wav
ffmpeg -i intro.mp4 -c:v copy -af loudnorm=I=-14:TP=-1.0:LRA=9 -ar 48000 -c:a aac -b:a 256k intro_t2x2.mp4
```
Структура: голова «говорит» приветствие на чёрном → вжух + бас-удар, круглое раскрытие фона с волнистыми полосами
(цвет плавно идёт по кругу) → карточки печатаются по словам: «T2X2 · АНТОН / реакции, игры и общение с чатом»,
«twitch.tv/T2X2», «Больше нарезок на канале «Двойная резка»» → затемнение. Звук и музыка синтезированы
(без чужих сэмплов — Content ID не сработает).

Голос (как сделан `voice_privet.wav`):
```
python3 tools/vod.py 2924895000 Audio_Only a.mp4 0 900
ffmpeg -ss 303.38 -t 1.58 -i a.mp4 -af "highpass=f=80,afade=t=in:d=0.04,afade=t=out:st=1.46:d=0.12,apad=pad_dur=0.9,\
aecho=0.85:0.75:110|230|360:0.38|0.24|0.13,afade=t=out:st=1.75:d=0.70:curve=qsin,loudnorm=I=-14:TP=-1.5:LRA=9" \
  -ac 2 -ar 48000 -t 2.48 voice_privet.wav
```
