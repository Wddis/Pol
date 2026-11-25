# Монтаж T2x2

[STYLE_SPEC.md](STYLE_SPEC.md) содержит измеренные параметры эталона. [TOOLS.md](TOOLS.md) сравнивает инструменты и указывает недостающий доступ. [METADATA.md](METADATA.md) содержит готовые поля для YouTube.

## Локальный запуск

Из папки проекта. В текущем окружении зависимости уже установлены в `.venv`. Для переноса нужны Python 3.12, FFmpeg, FFprobe и пакеты из `requirements.txt`. Модель small Faster-Whisper загружается при первом запуске распознавания, если её нет в кеше. Исходный код и ассеты прежней системы находятся в корне этой ветки. Здесь рабочая папка нового конвейера — `pipeline`. См. `RESUME.md`.

```sh
.venv/bin/python clip pick_stream
.venv/bin/python clip map_stream
.venv/bin/python clip cut_event 3
.venv/bin/python clip regenerate --edl final.edl.json --final
.venv/bin/python clip check_video output/final.mp4 --transcribe
```

Нумерованная карта `events.json` сверена с этим эфиром. `map_stream --transcript transcript.txt` строит предварительный список для другого VOD из расшифровки формата старого `stt.py`. Для монтажа нужно проверить границы и подготовить EDL. `cut_range` поддерживает собственный локальный MP4 через `--input` и таймкод начала файла через `--offset`. Интервалы задаются в абсолютном времени VOD, в секундах или как `ЧЧ:ММ:СС`. `retry_last` использует сохранённый запрос на рендер. `new_intro neon|brand|cold` использует старые пресеты цветокоррекции.

`make_shorts` принимает уже проверенное и зацензурированное видео, путь вывода, две строки текста через `|` и интервалы вида `116-135 136-143` в секундах входного файла. Применяется архивная раскладка Clean для правой нижней вебки. `layout` вызывает исторический YuNet-детектор. `set_title` и `replace_cover` сохраняют локальные метаданные. Команды `publish` и `publish_shorts` оставляют загрузку владельцу в YouTube Studio.

## Правки

- `edl.json` — контрольный черновик с историческим пропуском доната. `final.edl.json` — непрерывный диапазон финала. Видеочасти и маски имеют кеш по исходнику и диапазону. Правки аудио в `final.censorship.json` требуют пересборки аудио и контейнера. Правки отдельного видеоинтервала затрагивают соответствующие части.
- `evidence/reference.words.jsonl`, `evidence/vod.words.jsonl` и `output/final.words.jsonl` — пословные расшифровки с вероятностями. Для сырого VOD используется абсолютный таймкод эфира.
- `output/parts/*.masks.json` — отдельная запись для каждого выходного кадра VOD-части при 30 fps. Бокс задан как `[x0, y0, x1, y1]`, правый и нижний края исключены. Поля `left`, `right`, `chat` содержат результаты детекции. Поле `start_in_input` — позиция от начала локального MP4. Для правок скопировать нужный JSON, изменить боксы и указать копию в EDL, например `"mask_overrides":{"2":"edited-mask.json"}`. Часть 0 — интро. Номера остальных частей идут по порядку поля `vod_keep`. Регенерация пересоберёт только изменённую видеочасть.
- `evidence/intro` — покадровый разбор интро. Генератор в `../tools/intro/make_intro_tv.py` принимает лицо PNG с альфой, аватар, шрифты и дорожки реплик. Для кастомного выреза установить `rembg[cpu]` и вызвать `.venv/bin/python portrait.py кадр.png лицо.png`. Если в кадре несколько объектов, передать `--crop LEFT TOP RIGHT BOTTOM` для области вебки. Историческая версия использует оригинальный RGBA-вырез.

Для повторного рендера интро в историческом оформлении убрать переменную `STILLS` в команде:

```sh
STILLS=1.0,3.8,7.8 .venv/bin/python ../tools/intro/make_intro_tv.py \
  ../assets/intro/head_t2x2.png evidence/avatar-channel-2026-11-12.png fonts \
  ../assets/intro/voice_privet.wav ../assets/intro/tv/ph1_poehali.wav - \
  ../assets/intro/tv/ph3_krasota.wav evidence/intro-template.mp4
```
