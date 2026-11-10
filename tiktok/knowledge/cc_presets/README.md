# Готовые ЦК из урока (папка «готовая ЦК / Edit_CC»)

> **Действующий способ — статичные ЦК:** `effectcraft/cc/apply_cc.py` → `cc("night", layer="$grade")`.
> Набор эффектов каждого ЦК заморожен в `effectcraft/cc/static/`, эталон вида — `reference/fury_grid.jpg` и
> `reference/scofield_grid.jpg`. Всё, что ниже про `cc_steps` и калибровку по кадру, — **старый способ**
> (другая картинка), оставлен для справки.

Пять пресетов автора урока для After Effects: `ffx/Bright CC.ffx`, `Dusky CC.ffx`, `Night CC.ffx`,
`Raspberry haze CC.ffx`, `Two sharp CC.ffx` (в оригинале имя `Bright СС.ffx` было с кириллической «СС»).
В AE они требуют плагинов **Sapphire (S_Sharpen, S_Glow)**, **BCC (Unsharp Mask, Fast Film Glow)**, **Magic Bullet Looks**.
EffectCraft плагины не грузит, поэтому пресеты **разобраны и пересобраны** из встроенных эффектов.

Кадр сравнения из урока (истина, на которую калибровали): `comparison_from_tutorial.jpg`.
Проверка эмуляции в EffectCraft (верх — наш рендер, низ — AE из урока): `effectcraft_vs_ae.jpg`.
Пример на кадре из «Fury» (оригинал + 5 ЦК, рендер EffectCraft): `fury_cc_comparison.jpg`.
Пример на дневном кадре (Скофилд): `scofield_cc_comparison.jpg` — на ярких кадрах пресеты пересвечивают небо/белое (калибровка была по тёмному кадру). Любой кадр: `python3 tiktok/effectcraft/cc/demo_grid.py <img> <out.jpg>`.

## Как применить (для Claude)
```python
import sys; sys.path.insert(0, "tiktok/effectcraft/cc")
from cc_steps import cc_steps
X("layer.newAdjustment", name="GRADE", save="grade")          # adjustment над видео
steps += cc_steps("night", layer="$grade", fx_start=1, comp_h=1080, strength=1.0)
```
Цепочка: **Unsharp Mask** (шарпы) → **Channel Mixer** (насыщенность, с сохранением яркости) → **Levels** (R/G/B).
`strength` 0..1 — смесь с оригиналом (как Strength в Looks). Проверено в EffectCraft: цвет совпадает с моделью
(RMSE 0.004), Unsharp Mask EffectCraft = `x + amount·(x − gauss_σ=radius(x))` — точно.

Для телефона/CapCut/Premiere/DaVinci — LUT-файлы `tiktok/effectcraft/cc/luts/*.cube` (33³).
**В EffectCraft 0.9.2 .cube не работают** (Apply Color LUT и Lumetri Input LUT игнорируют файл — проверено на LUT-инверсии).

## Что внутри каждого пресета (декодировано из .ffx; полный дамп — `ffx_params_dump.txt`, Looks — `looks_decoded.json`)

| Пресет | Шарпы | Looks (цепочка инструментов) | Прочее | Характер |
|---|---|---|---|---|
| **Bright** | S_Sharpen Amp 0.5, Luma 0.6, детали 2.7/1.15/0.54/1.1; BCC Unsharp R 20, A 5 | Looks#1 (90%): Duotone → Mojo II (40) → Hue/Sat (sat 135%) → Colorista → Shoulder → Haze → Lens Vignette; Looks#2: Haze | — | светлый, чистый, нейтрально-холодный фон, высокая чёткость |
| **Dusky** | S_Sharpen Amp 0.5, Luma 1.0, детали 2.5/2/1.5/3; BCC Unsharp R 15, A 12 | Chromatic Aberration → Haze → 2-Strip (30) → Colorista → Channel Mixer → Optical Diffusion | 4-Color Gradient (азур 0,144,255; 8%, Soft Light); Hue/Sat Reds colorize | приглушённый, сумеречный, голубоватые тени, диффузия |
| **Night** | S_Sharpen + BCC Unsharp | Looks#1: Shadows → Mojo II → Warm/Cool Filter → Log; Looks#2: Exposure −0.5 → Contrast → Hue/Sat (106%) → 2-Strip (82) → Haze | — | тёмный, контрастный, тёплые тени |
| **Raspberry haze** | S_Sharpen Amp 0.6, детали 3/1.5/1/2; BCC Unsharp R 20, A 10 | Gradient → Gradient Exposure + Spot Exposure + Haze + 2-Strip (100) → Diffusion (200) | S_Glow (0.9, thr 0.5, width 100), Brightness/Contrast +12/+13, BCC Fast Film Glow (15, r 30) | малиновая дымка, свечение, приподнятые чёрные |
| **Two sharp** | **2×** (S_Sharpen + BCC Unsharp) | Looks (72%): Curves → Diffusion → Haze → 2-Strip (90) → Mojo II → Hue/Sat (115%) → Exposure +0.5 | BCC Film Glow | очень резкий, тёплый/красноватый, светлый |

Общее у всех (по калибровке): **приподнятые/выцветшие чёрные** (Haze), **сжатый контраст в тенях + яркие света**,
сниженная насыщенность фона, сильный локальный контраст (шарпы). Это и есть «эдитовый» ЦК.

## Калибровка (эмуляция EffectCraft) — `tiktok/effectcraft/cc/cc_presets.json`

| Пресет | Насыщ. (Mixer) | Unsharp (amount %, radius@1080) | RMSE эмуляции | RMSE без ЦК | .cube held-out |
|---|---|---|---|---|---|
| bright | 0.67 | 25 %, 9.8 px | 0.047 | 0.121 | 0.048 ✔ |
| dusky | 0.89 | 20 %, 9.8 px | 0.064 | 0.098 | 0.117 ✖ (LUT ненадёжен) |
| night | 0.79 | 65 %, 9.8 px | 0.023 | 0.043 | 0.025 ✔ |
| raspberry_haze | 0.90 | 35 %, 9.8 px | 0.043 | 0.080 | 0.060 ✔ |
| two_sharp | 0.93 | 125 %, 9.8 px | 0.053 | 0.136 | 0.062 ✔ |

Ограничения: калибровка по одному кадру 480p (плитки ~165 px), поэтому избирательные эффекты Looks
(Mojo: тёплая кожа/холодный фон; виньетки, свечения S_Glow/Film Glow, диффузия) переданы не полностью.
Свечения/диффузию при необходимости добавлять отдельно: Glow (threshold ~70–85, radius 30–60, intensity 0.2–0.5),
виньетка — Lumetri vignette / CC Vignette. Dusky — наименее точный (плитка хуже совпадает с оригиналом).

Пересчитать: `python3 fit_cc.py` (резкость) → `fit_native.py` (Mixer+Levels) → `fit_lut.py` (.cube) — см. `tiktok/effectcraft/cc/`.
