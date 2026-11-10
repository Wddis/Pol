# aeconv — разбор `.aep` (композиции, слои, текст)

Перенос **эффектов** делает `../aefx/` (см. `../aefx/README.md`). Здесь — то, что вокруг эффектов:

| Файл | Что делает |
|---|---|
| `rifx.py` | парсер контейнера RIFX (.ffx/.aep) — общий для aefx |
| `aep_extract.py` | композиции, слои, тайминг, свойства, текст из `.aep` |
| `aep2ec.py file.aep out.json [--footage clip.mp4 --roto strokes.json --strict]` | `.aep` → проект EffectCraft; эффекты слоёв — через aefx, с отчётом |
| `ffx2ec.py file.ffx out/` | то же, что `python3 aefx_cli.py convert` |
| `mblooks.py` | декодер Magic Bullet Looks (блок `MBL2`) |
| `showcase.py` | демо-эдит |

Время AE: тики 1/30720 с. Тексты из `.aep` (CoS-документ): строки, размер, трекинг, интерлиньяж, baseline, заливка;
Arial → Inter (свои шрифты EffectCraft не подгружает).
