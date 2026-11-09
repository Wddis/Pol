# Дорожная карта: TikTok-эдиты в EffectCraft (для нового чата с Claude)

> **Как пользоваться.** В новом чате (Claude Code, репозиторий `wddis/pol`) напиши:
> «Прочитай `tiktok/ROADMAP.md` и продолжай работу по нему. Вот мои файлы: …»
> и прикрепи zip с клипами, музыкой, примером и AE-файлами — медиа в git не хранятся.

---

## 1. Кто я и что делаем

- У меня **нет ПК**, работаю с телефона. Всё делает Claude в облачной сессии и присылает готовый mp4.
- Цель — эдиты для TikTok по моему примеру (референсу) из моих нарезок, с моей музыкой.
- Остаёмся на **EffectCraft** (браузерный клон After Effects): бесплатно, без ПК.
  Настоящий AE / облачные ПК / рендер-фермы — платные, отказались.

## 2. Стиль эдита

Стиль каждый раз задаётся **примером, который я прикрепляю** (видео-референс). Повторять стиль
именно этого примера: разобрать его по кадрам, склейкам, ритму, цвету, тексту и переходам.

**База знаний уже собрана — читать в первую очередь: `knowledge/README.md`.**
- `knowledge/tutorial_ae/INSTRUCTION.md` — инструкция по эдиту из урока (AE) + перевод каждого шага в EffectCraft;
- `knowledge/cc_presets/README.md` — 5 готовых ЦК (Bright, Dusky, Night, Raspberry haze, Two sharp),
  применяются одной строкой: `cc_steps("night", layer="$grade")` из `effectcraft/cc/cc_steps.py`;
- `knowledge/reference_edits/ANALYSIS.md` — общая формула 4 эдитов людей (диалог с пословными субтитрами →
  вспышка → склейки на каждый бит → «EDITED BY» аутро).
Повторно смотреть урок/эдиты не нужно — всё извлечено туда.

## 3. Структура репозитория (`tiktok/`)

| Путь | Что это |
|---|---|
| `effectcraft/driver.js` | Headless Chromium (Playwright) открывает EffectCraft, грузит файлы, выполняет команды, рендерит |
| `effectcraft/runner.js` | Код внутри страницы EffectCraft: исполняет `steps`, делает стоп-кадры / покадровый рендер |
| `effectcraft/build_project.py` | Генератор эдита «Scofield» (монтаж по битам, переходы, грейд, текст) |
| `effectcraft/beats.json` | Биты трека (librosa) |
| `effectcraft/aefx/` | **Перенос эффектов AE → EffectCraft без урезаний** (см. раздел 6 и `aefx/README.md`) |
| `effectcraft/aeconv/` | разбор `.aep` (композиции, слои, текст) + старый перенос эффектов (`--legacy`) |
| `effectcraft/aeconv/showcase.py` | 15-сек демо всех сконвертированных эффектов |
| `effectcraft/aeconv/mblooks.py` | декодер Magic Bullet Looks (блок `MBL2` в .ffx/.aep) |
| `effectcraft/cc/` | **готовые ЦК** из урока: `cc_steps.py` (EffectCraft), `cc_presets.json`, `luts/*.cube`, скрипты калибровки |
| `knowledge/` | **база знаний** (урок, ЦК, разбор эдитов) |
| `src/` | Ранний вариант на Remotion (React) — запасной, сейчас не основной |
| `public/` | сюда кладутся `cut.mp4`, `music.m4a` (в git не попадают) |
| `out/` | готовые видео (в git не попадают) |

Ветка разработки: `ccr-8ab5198e-jrk139` (в новом чате — та, что назначит сессия).

## 4. Как запустить пайплайн (для Claude)

```bash
# зависимости
cd tiktok && npm install            # remotion/шрифты — не обязательно для EffectCraft
pip install librosa                 # анализ битов
# Playwright и Chromium уже есть в облачной среде; ffmpeg — /usr/bin/ffmpeg

# медиа: распаковать zip пользователя, положить в tiktok/public/
#   нарезка -> cut.mp4, музыка -> music.m4a
#   ВНИМАНИЕ: «.mp3» пользователя на деле был AAC -> ffmpeg -i music.mp3 -c copy music.m4a

# биты:  ffmpeg -i music.m4a -ac 1 -ar 22050 m.wav  ->  librosa.beat.beat_track -> beats.json
```

**Конфиг драйвера** (`node effectcraft/driver.js cfg.json`, `NODE_PATH=$(npm root -g)`):
```json
{ "files": ["/abs/cut.mp4", "/abs/music.m4a"],
  "steps": "/abs/steps.json",
  "stills": [1.0, 5.5],                    // стоп-кадры (быстрая проверка)
  "frames": {"from": 0, "to": 450},        // ИЛИ покадровый рендер в PNG
  "framesDir": "/abs/frames", "outDir": "/abs/out",
  "evalFile": "/abs/debug.js" }            // доп. код в странице (диагностика)
```
Пути в конфиге — **абсолютные** (относительные считаются от папки конфига — уже ловили ошибку).

**Сборка видео:**
```bash
ffmpeg -framerate 30 -i frames/%05d.png -ss <старт_в_треке> -i music.m4a -t <длина> \
  -map 0:v -map 1:a -c:v libx264 -crf 19 -pix_fmt yuv420p -c:a aac -b:a 256k \
  -af "afade=t=out:st=<длина-1>:d=1" out.mp4
```

## 5. Грабли EffectCraft 0.9.2 (проверено на практике)

1. **Render Queue в headless теряет анимацию ключей** (видео «застывает»). Рендерить только через
   `effectcraft.renderFrame` покадрово (режим `frames`), потом ffmpeg.
2. **Параллельный рендер:** каждый браузер ~5 ГБ RAM. 4 потока → падения (`Target crashed`).
   Безопасно **2 потока** (или 3 для лёгких проектов). Кадры дописываются — упавший кусок перезапустить.
3. Скорость: ~1.5 c/кадр простой план, ~3–4 c/кадр с моушен-блюром/ротобрашем.
4. **Точки эффектов (vec2) — в пикселях слоя**, а не в долях (Motion Tile `tileCenter` = `[640,360]` для 1280×720).
5. Параметры команд валидируются строго: лишний ключ = ошибка (`command.describe {command}` покажет схему).
6. `keys.setEase` — время в **секундах слоя** (не композиции); `prop.addKey` — можно `timeBase:"comp"`.
7. Пресет `.ecpreset` применяется только к эффектам, **уже стоящим** на слое → сначала `effect.apply`, потом preset.
8. Второй текстовый аниматор: путь `text/animators/animator#2/...`.
9. **Свои шрифты не подгружаются.** Доступны: Inter (Regular…Bold), JetBrains Mono, Noto Serif.
   Титр «под serif» = Noto Serif + `vScale 140` + большой tracking.
10. **Плагины не грузятся** (`effect.plugins.list → wasm:false`) — Sapphire/BCC/Deep Glow только эмуляцией.
11. CSP страницы запрещает `new Function` внутри — доп. код передавать через `evalFile` драйвера.
12. Ротобраш работает: `roto.model.select classical` → `roto.stroke` (fg/bg, пиксели слоя) → `roto.propagate {wait:true}`.
13. Пример рабочего грейда (тёплый, из эдита «Scofield»): Lumetri (temperature 55, tint 14, contrast 30, highlights −20,
    shadows −10, blacks −25, sharpen 40, vignette −1.6) + Photo Filter 30 + Curves + Glow (thr 86, r 30, i 0.3) + Add Grain 0.4.
14. **LUT (.cube) не применяются**: `Apply Color LUT` и Lumetri `inputLut=Custom` с путём `/file.cube` молча игнорируются
    (проверено LUT-инверсией). Файлы, добавленные драйвером, лежат в корне ФС (`/name`). ЦК — через `cc/cc_steps.py`.
15. Формулы, проверенные рендером: Unsharp Mask = `x + amount/100·(x − gauss(σ=radius px))`; Levels по каналам (`redInBlack`…)
    и Channel Mixer (`rr`…`bc`, целые %) — как в AE.
16. **`prop.set` молча обрезает значение** по диапазону параметра и возвращает обрезанное (`runner.js` это ловит → `clamped`).
17. EffectCraft сам нумерует одинаковые эффекты («Glow 2»). На имена ссылаются выражения → задавать имя явно (`prop.renameGroup`, uid из `effect.apply`).
18. Выражения: работают `effect("Имя")("Slider")`, `noise`, `wiggle`, `clamp`, `valueAtTime`, `thisLayer.width`; **не работает** `thisProperty.propertyGroup()`.
19. Есть скриптовый слой как в AE (`script.run`: `app.project`, `addProperty("ADBE Glo2")`), но popup там отдаёт индекс с нуля — для значений использовать команды движка.
20. Continuous-bezier ключ связывает скорости входа и выхода: вторая `keys.setEase` перезапишет первую.

## 6. Перенос After Effects → EffectCraft (`effectcraft/aefx/`, подробно — `aefx/README.md`)

EffectCraft не открывает `.aep`/`.ffx`. `aefx` читает бинарный формат AE (RIFX) и пересобирает эффекты командами.

```bash
cd tiktok/effectcraft
python3 aefx_cli.py inspect preset.ffx                 # что в файле
python3 aefx_cli.py convert preset.ffx out/ --strict   # -> out/preset.steps.json + .report.txt + .ir.json
python3 aeconv/aep2ec.py project.aep out.json --footage clip.mp4 --footage-src 0 --roto strokes.json
python3 aefx/tests/site_check.py preset.ffx            # применить на сайте и сверить каждое значение/ключ
```
`steps` из `.steps.json` вставляются в свой проект как есть (слой — `$cur`, счётчик эффектов — `--fx-start`).

- **Стандартные эффекты AE → тот же эффект EffectCraft**, поиск по всему каталогу (306 эффектов), все параметры,
  все ключи (измерения, ease, hold, roving, касательные), выражения. Время AE: тики 1/30720 с.
- **Сторонние плагины** (Sapphire, BCC, Deep Glow, Godrays, Looks) запустить нельзя (`wasm: false`). Каждый их
  параметр ставится на слой контролом «Эффект › Параметр» с ключами 1:1, встроенные эффекты управляются
  формулами от контролов. Это **эмуляция**, не копия плагина; коэффициенты подобраны на глаз.
- **Отчёт обязателен к прочтению.** `LOSS` / `CLAMPED` / `STORED` = не перенесено. Не говорить пользователю
  «перенесено полностью», если в отчёте есть потери или эффект помечен «эмуляция».
- **Калибровки:** Turbulent Displace `amount × size/100` (выражением); CC Block Load `0.6·c + 80`;
  Drop Shadow opacity `×100/255`. Точки эффектов — доли слоя, в пиксели пересчитывает `runner.js` на сайте.
- Тексты из `.aep` (CoS-документ): строки, размер, трекинг, интерлиньяж, baseline, заливка; Arial → Inter.

**Не проверено на настоящих файлах** (в git их нет): ключи не-1D форматов, путь `.aep`, рецепты кроме
S_Sharpen / S_Glow / BCC Unsharp / BCC Film Glow. Получив настоящий `.ffx`/`.aep` — сначала `site_check.py`.

**Что подтянуть дальше:** декодер Curves (нужен `.ffx` с кривыми); инструменты Magic Bullet Looks;
скриншоты тех же эффектов из AE, чтобы откалибровать формулы эмуляций.

## 7. Что уже сделано (история)

1. Remotion-проект (`src/`) — первый эдит, не используется.
2. EffectCraft-эдит «Scofield» 74.5 c: `effectcraft/build_project.py` (монтаж по битам ~86 BPM).
3. Конвертер AE + тесты на фото + демо 15 c (`showcase.py`, трек 55.5–70.5 c).
4. Изучен zip «файлы для Эдита Claude code» (урок AE 13 мин + 5 ЦК + 4 эдита людей): транскрипт через ElevenLabs,
   инструкция, декодирование ЦК (S_Sharpen, BCC Unsharp, Magic Bullet Looks) и эмуляция в EffectCraft → `knowledge/`, `effectcraft/cc/`.

## 8. Чек-лист для нового эдита

1. Получить zip: пример, нарезку, музыку (+ AE-файлы, если есть). Распаковать в отдельную папку.
2. Разобрать пример: раскадровка `ffmpeg … tile`, scene-detect склеек, beat-track его звука.
3. Биты своего трека → `beats.json`; найти тихие места и дроп (RMS по 0.25 c).
4. Scene-detect нарезки → лист шотов с кадрами → выбрать планы и кроп (pos 0..1) под квадрат.
5. Сгенерировать `steps` (по образцу `build_project.py`/`showcase.py`) по формуле из `knowledge/` (анимации
   урока: velocity 200→30→200 %, зум 68→100→68 %, Exposure 0.4→−1, тряска 40→0 за 6 кадров; ЦК — `cc_steps`;
   субтитры по словам — тайминги речи через ElevenLabs Scribe), проверить 8–12 стоп-кадров.
6. Покадровый рендер в 2 потока → ffmpeg + музыка → прислать mp4 (и раскадровку, если надо).
7. Закоммитить скрипты (медиа — нет).
