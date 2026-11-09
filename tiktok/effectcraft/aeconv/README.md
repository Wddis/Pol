# Конвертер After Effects → EffectCraft

EffectCraft не открывает `.aep`/`.ffx` и не грузит плагины AE (веб-сборка: `effect.plugins.list → wasm: false`).
Этот конвертер читает бинарный формат AE (RIFX) и пересобирает эффекты командами EffectCraft.

| Файл | Что делает |
|---|---|
| `rifx.py` | парсер контейнера RIFX (.ffx/.aep) |
| `extract_ffx.py` | эффекты/параметры/ключи из `.ffx` |
| `aep_extract.py` | композиции, слои, тайминг, свойства, текст из `.aep` |
| `ae2ec.py` | перенос эффектов: стандартные 1:1, платные — сборки из встроенных |
| `ffx2ec.py file.ffx out/` | `.ffx` → `*.steps.json` + `*.ecpreset` + отчёт |
| `aep2ec.py file.aep out.json [--footage clip.mp4 --roto strokes.json]` | `.aep` → проект EffectCraft |
| `ec_effects.json` | справочник эффектов/параметров EffectCraft 0.9.2 |

Время: тики AE 1/30720 с; ключи переносятся с bezier-ease (speed + influence) как есть.

## Калибровки (поведение EffectCraft отличается от AE)
- точки эффектов в EffectCraft — пиксели слоя (в AE-пресетах — доли кадра);
- Turbulent Displace: в AE смещение растёт с Size → `Amount_ec = Amount_ae × Size/100`;
- CC Block Load: шкала Completion сдвинута → `0.6×c + 80`;
- BCC Lens Blur Iris Scale → Camera Lens Blur radius (1 ед. ≈ 1px на 1080).

## Платные плагины → встроенные
S_Shake → Motion Tile + Transform (ключи по кадрам из кривой амплитуды, моушен-блюр) ·
BCC Lens Blur → Camera Lens Blur · Deep Glow → 3×Glow · Crate's Godrays / BCC Rays Puffy → CC Light Rays ·
S_Emboss → Bevel Alpha · S_WarpChroma → VR Chromatic Aberrations + Radial Blur · S_ScanLines → Grid ·
BCC Halftone → CC Ball Action · Roto Brush → Roto Brush EffectCraft (мазки задаются для своего клипа).
Шрифт Arial → Inter (свои шрифты EffectCraft не подгружает).
