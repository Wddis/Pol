# Инструкция «Эдит в After Effects с нуля» — разбор урока (база, без повторного просмотра)

Источник: `инструкции с Ютуба по edits/инструкция по edit в ae.mp4` (13:17, 854×480, русский).
Речь → текст: ElevenLabs Scribe v1 (`transcript.txt`, пословные тайминги `words_1.2x.json` —
аудио было ускорено ×1.2 и без пауз, **время в оригинале ≈ t × 1.2**). Кадры урока: `frames/sheet_0..4.jpg`
(кадр каждые 10 с, 16 на лист, лист N = 0:00 + N×160 с).

Автор строит эдит по фильму «Американский психопат» (Патрик Бейтман), вертикаль, 60 fps,
трек phonk/slowed. Пайплайн автора — 5 этапов:

| # | Этап | Суть |
|---|---|---|
| 1 | Препродакшн | найти футаж (фильм/аниме в лучшем качестве или «сцен-пак») и музыку |
| 2 | Проект и нарезка | композиция, маркеры по битам, нарезка клипов по маркерам, кадрирование, стабилизация, Topaz |
| 3 | Анимации | Twixtor (скорость), Sapphire S_BlurMoCurves (зум с моушен-блюром), Exposure (затухание), Video Copilot Twitch (тряска) |
| 4 | Стилизация (ЦК) | шарпы (Sharpen + Unsharp Mask) → цвет (Magic Bullet Looks) + Flicker (Sapphire S_Flicker), готовые пресеты |
| 5 | Текст и финал | титры по словам героя, Fade Up, Bevel Alpha + Deep Glow + Drop Shadow, RSMB, рендер |

---

## 1. Препродакшн
- Видео: первоисточник в максимальном качестве, сцены резать самому; или сцен-паки (бесплатные/платные).
- Музыка — любая; 10 популярных треков для эдитов (кадр 0:50):
  1. PLAYAPHONK – Phonky Town · 2. INTERWORLD – Metamorphosis · 3. DVRST – Close Eyes (slowed+reverb) ·
  4. GHOST! – phonk.me & KIIXSHI · 5. Gigachad Theme · 6. MC ORSEN – Warning · 7. VØJ, Narvent – Memory Reboot ·
  8. One Chance – Moondeity · 9. Timbaland – Give It To Me (instrumental) · 10. Suicidal-Idol – Ecstacy (super slowed).

## 2. Проект и нарезка
1. **Композиция:** 1170×1560 (любой вертикальный), **60 fps**, длительность любая (`Ctrl+K` — поменять). Имя «Edit».
   Сразу сохранить, включить автосохранение, `Ctrl+S` после каждого этапа.
2. Музыку и футаж — в проект и на таймлайн.
3. **Маркеры битов** (2 способа):
   - ПКМ по треку → Keyframe Assistant → *Convert Audio to Keyframes* → `U` → оба канала → Graph Editor → маркер на каждом скачке;
   - выделить трек → `LL` (вейвформа) → маркеры на пиках. Какой лучше — зависит от трека.
4. **Нарезка:** двойной клик по футажу (свой таймлайн) → найти момент → скобка `[` (in-point) →
   `Shift` + примагнититься к следующему маркеру → `Ctrl+Shift+D` (разрез). Повторять до конца.
   Клипы можно переставлять — главное, чтобы склейки совпадали с маркерами.
5. **Кадрирование:** выделить все клипы → `S` → подогнать под размер; `P` → выровнять героя по центру.
6. **Стабилизация на лице (эффект «лицо в центре»):** Tracker → Stabilize Motion → Track Point на **нос** →
   Analyze → Apply → выровнять Position/Scale, чтобы не было пустых краёв; если пустоты большие — **Motion Tile**.
7. **Topaz Video AI (качество):**
   - Frame Blending плохо работает на быстрых движениях → либо выключать его на проблемных кусках, либо:
     Sharpen + Unsharp Mask на проблемный клип → рендер в родных fps → Topaz: интерполяция до **60 fps** → обратно в AE;
   - вся секвенция 60 fps → рендер QuickTime **ProRes 422 HQ** → Topaz **Enhance** (апскейл) → экспорт ProRes 422 HQ, MOV, без звука →
     обратно в композицию, нарезать как оригинал (старые клипы удалить; композицию продублировать как бэкап).
   - Включить Frame Blending (качество «Pixel Motion»/улучшенное) и Motion Blur.
8. **Плагины-база:** Twixtor, BCC (Boris FX Continuum), Sapphire, Magic Bullet. Дальше — по вкусу (Pixel Sorter, глитчи и т.п.).

## 3. Анимации (на каждый клип, ключи копируются на все клипы)
Клипы предварительно пре-компоновать (`Ctrl+Shift+C`, «Move all attributes»).
Порядок эффектов на клипе (по панели Effect Controls): **Twixtor Pro → S_BlurMoCurves → Exposure → Twitch**, плюс Opacity.
Подробно (ключи по кадрам, переходы, слои проекта, разбор итогового эдита) — `STRUCTURE.md`; полный текст урока — `TRANSCRIPT.md`, субтитры — `subtitles_ru.srt`.

| Эффект | Ключи (начало → середина → конец клипа) | Кривая |
|---|---|---|
| **Twixtor Pro** (скорость) | Input Frame Rate = fps исходника; Speed **200% → 30% → 200%** | `F9` (Easy Ease) + Graph Editor: резкое падение скорости в слоумо в начале, плавный разгон к концу (кадр ~6:50, `frames/sheet_2`) |
| **S_BlurMoCurves** (Sapphire) → Z Dist (зум) | **0.68 → 1.0 → 0.68** (отъезд-наезд-отъезд; вместо него можно обычный Scale) | `F9` + кривая «как у автора» (плавный пик в середине) |
| **Exposure** (затухание) | в начале **0.4**, в конце **−1** | `F9`; плюс Opacity: через **7 кадров** от начала — ключ, в начале **0**; так же в конце (вход/выход из черного) |
| **Twitch** (Video Copilot; Amount) (тряска) | в начале **40**, через **6 кадров** **0**, так же в конце | удар-тряска на склейке |

Перенос: скопировать эффекты и ключи на другие клипы; `Shift` — сдвинуть ключи к началу клипа,
`Alt`-растяжка — подогнать под длину клипа, не ломая кривые. Регулярно просматривать эдит и править сразу.
На интро автор дополнительно повесил Twitch (отдельные настройки).

## 4. Стилизация (ЦК) — «из чего состоят платные паки»
Результат напрямую зависит от исходника (хороший исходник + Topaz). ЦК эдита = 3 этапа:
1. **Первичка** (не всегда) — баланс картинки, чтобы следующие этапы работали лучше.
2. **Шарпы** — то, что отличает ЦК эдита от обычного: эффект сверхчёткости/детализации.
   - Sharpen: **S_Sharpen** (Sapphire), BCC Magic Sharp, Sharpen;
   - Unsharp Mask: **BCC Unsharp Mask**, Unsharp Mask.
   Любимчики автора: S_Sharpen + BCC Unsharp.
3. **Цвет** — почти всегда **Magic Bullet Looks** (+ встроенные эффекты цветокора).

Рецепт: Sharp + Unsharp Mask → Looks → поиграть с пресетами Looks. По этой схеме сделаны почти все топовые паки.
Автор раздаёт 5 своих пресетов — они в `../cc_presets/` (Bright, Dusky, Night, Raspberry haze, Two sharp;
для работы в AE нужны плагины Sapphire, BCC, Magic Bullet).

В проекте: (продублировать клипы, если нужно) → `Ctrl+Alt+Y` ×2 → два **adjustment layer**: **CC** (ЦК) и **Flicker** (эффект Sapphire S_Flicker)
(Flicker можно с дефолтом; автор анимировал его под интро). Можно ещё adjustment под **Shake** (автор убрал — не подошло).

## 5. Текст
1. `Ctrl+Alt+Shift+T` — текстовый слой; выписать слова героя (интро-фраза из фильма), расставить.
   `Alt+D` — дублировать (отдельный слой под каждое слово/фразу).
2. Анимация: пресет **Fade Up** → `U` → **0%** когда герой начинает говорить, **100%** когда заканчивает
   (слова появляются синхронно с речью). Затухание в конце — `T` (Opacity) или эффект **CC Burn**.
3. Эффекты на текст: **Bevel Alpha + Deep Glow + Drop Shadow** (настройки автора — на кадре ~11:50), копировать на весь текст;
   можно менять цвет для разнообразия. **Twitch** с интро скопирован на текст — трясётся вместе с картинкой.
   Шрифт в примере: жирный гротеск (Montserrat-подобный), белый, по центру нижней трети («TWO STONEY ON THE ROCKS»).

## 6. Финал и рендер
1. Всё, кроме музыки, — в пре-композицию → **RSMB** (ReelSmart Motion Blur) для плавности.
2. Лайфхак: `Shift` + Preferences → General (не отпуская Shift) → «секретный» раздел → значения автора (ускоряет рендер).
3. `Ctrl+M`: ProRes 422 HQ (качество) или **H.264, 15 Мбит/с** (быстро). **Caps Lock** во время рендера — без превью, +10–20% скорости.

---

## Перевод в наш пайплайн (EffectCraft, без ПК — см. `tiktok/ROADMAP.md`)

| AE (урок) | EffectCraft 0.9.2 (у нас) |
|---|---|
| 60 fps, 1170×1560 | квадрат 1080×1080 / вертикаль 1080×1350–1920; 30 fps (рендер покадровый, 60 fps = ×2 времени) |
| маркеры битов | `librosa.beat.beat_track` → `beats.json` (уже в пайплайне) |
| нарезка `Ctrl+Shift+D` | `layer.addItem` + `layer.timing` по битам (`build_project.py`) |
| Stabilize Motion на нос + Motion Tile | Warp Stabilizer / ручной `transform/position` по ключам; **Motion Tile** есть (vec2 в пикселях) |
| Topaz (60 fps, Enhance) | нет; частично: `layer.frameBlending frameMix`, Detail-preserving Upscale, Unsharp Mask |
| **Twixtor** 200→30→200 | `layer.enableTimeRemap` + ключи `timeRemap` с bezier-ease (velocity: быстро → слоумо → быстро) |
| S_BlurMoCurves Z Dist 0.68→1→0.68 | `transform/scale` ключи (68%→100%→68% от базового) + `keys.setEase` |
| Exposure 0.4 → −1, Opacity 0 за 7 кадров | эффект **Exposure** (1:1) + `transform/opacity` |
| Twitch 40 → 0 за 6 кадров | Transform/Motion Tile shake по кадрам (как эмуляция S_Shake в `aeconv`) или выражение `wiggle` |
| S_Sharpen + BCC Unsharp + Looks | **`effectcraft/cc/cc_steps.py`** — готовые 5 ЦК (Unsharp Mask + Channel Mixer + Levels), откалиброваны по кадру урока |
| Flicker | ключи Exposure/opacity adjustment-слоя по кадрам (случайные ±) |
| Fade Up по словам | текстовый аниматор (opacity/position по символам), `text/animators/...` |
| Bevel Alpha + Deep Glow + Drop Shadow | Bevel Alpha, Drop Shadow — 1:1; Deep Glow → 3×Glow (уже в `aeconv`) |
| CC Burn | CC Burn Film (есть) |
| RSMB | `comp.setSwitch motionBlur` + `layer.setSwitch motionBlur`, shutter 360, 16 samples |
| рендер H.264 15 Мбит | покадровый `renderFrame` → ffmpeg `libx264 -crf 19` |
