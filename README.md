# Pol

- `bot.py` — Telegram-бот
- `tiktok/` — TikTok эдиты (EffectCraft — основной, Remotion — запасной; см. `tiktok/ROADMAP.md`)

---

## Инструкция: эдит по уроку AE — анимация «точь-в-точь» в EffectCraft

Полный разбор урока — `tiktok/knowledge/tutorial_ae/INSTRUCTION.md`, база знаний — `tiktok/knowledge/README.md`.

- **Полный текст урока по главам** (с таймкодами) — `tiktok/knowledge/tutorial_ae/TRANSCRIPT.md`; субтитры — `tiktok/knowledge/tutorial_ae/subtitles_ru.srt`
- **Структура инструкции** (слои проекта, порядок эффектов, ключи по кадрам, переходы, текст, покадровый разбор итогового эдита) — `tiktok/knowledge/tutorial_ae/STRUCTURE.md`

**Можно ли повторить анимацию урока точь-в-точь?** Да: **ключи, значения, тайминги и кривые переносятся 1:1**.
Отличается только то, что делают платные плагины внутри себя (качество слоумо Twixtor, вид Deep Glow) —
это собирается из встроенных инструментов EffectCraft и выглядит близко, но не пиксель-в-пиксель.

### Анимация на каждый клип (значения из урока)

| Что | Ключи (начало → середина → конец клипа) | Как в EffectCraft | Точность |
|---|---|---|---|
| Скорость (Twixtor Pro) | 200 % → 30 % → 200 %, Easy Ease (F9) + кривая автора: резкий провал в слоумо, плавный разгон | `layer.enableTimeRemap`; кривая скорости интегрируется в ключи `timeRemap` (по кадру), слоумо — `layer.frameBlending pixelMotion` (оптический поток) | ключи 1:1; плавность слоумо близка к Twixtor (без его настроек Motion Vectors) |
| Зум (Sapphire S_BlurMoCurves, Z Dist) | 68 % → 100 % → 68 % от базового масштаба, F9 + плавный пик | `transform/scale` + `keys.easyEase` / `keys.setEase` (speed, influence) | 1:1 |
| Затухание (Exposure) | 0.4 → −1, F9 | эффект **Exposure** (тот же, что в AE) | 1:1 |
| Прозрачность | 0 в начале → 100 через 7 кадров; так же в конце | `transform/opacity` | 1:1 |
| Тряска (Video Copilot Twitch) | 40 → 0 за 6 кадров, так же в конце | Transform-тряска по кадрам (как эмуляция S_Shake в `aeconv`) или `keys.wiggle` с затуханием | амплитуда и тайминг 1:1, характер тряски — близко |
| Перенос на все клипы | Shift — сдвиг ключей к началу, Alt — растяжка без порчи кривых | генератор шагов ставит ключи относительно in/out каждого клипа автоматически | 1:1 |

### Финал и ЦК

| Что | Как в EffectCraft | Точность |
|---|---|---|
| Маркеры битов, нарезка по маркерам | `librosa` → `beats.json` → `layer.timing` по битам | 1:1 (бит вычисляется точнее, чем руками) |
| Стабилизация на нос + Motion Tile | `track.motion` / ключи позиции + **Motion Tile** | близко |
| ЦК (Sharpen + Unsharp + Looks) | `tiktok/effectcraft/cc/cc_steps.py`: 5 готовых пресетов урока | близко (см. `tiktok/knowledge/cc_presets/README.md`) |
| Flicker (Sapphire S_Flicker) | ключи Exposure adjustment-слоя по кадрам | близко |
| Текст: Fade Up по словам, Bevel Alpha, Drop Shadow | текстовые аниматоры + Bevel Alpha, Drop Shadow (1:1) | 1:1 по таймингу; шрифт — Inter/Noto Serif (свои шрифты не грузятся) |
| Deep Glow | 3× Glow | близко |
| RSMB | **Pixel Motion Blur** + моушн-блюр композиции (shutter 360) | близко |
| 60 fps, H.264 | покадровый рендер → ffmpeg (60 fps = ×2 время рендера) | 1:1 |

**Что нельзя повторить:** Topaz (апскейл/интерполяция нейросетью) — только частично (Pixel Motion, Detail-preserving Upscale, Unsharp Mask);
настройки Twixtor/Deep Glow, которые на видео урока в 480p не читаются, подобраны на глаз.

### Как заказать эдит (для пользователя)
1. Новый чат с Claude Code в репозитории `wddis/pol`: «Прочитай `tiktok/ROADMAP.md` и сделай эдит по инструкции урока».
2. Прикрепить zip: нарезка (футаж), музыка, пример-референс (если стиль не из урока), какой ЦК (Bright / Dusky / Night / Raspberry haze / Two sharp).
3. Claude генерирует шаги, присылает стоп-кадры на проверку, затем рендерит mp4.
