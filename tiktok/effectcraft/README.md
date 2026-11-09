# Эдит в EffectCraft (браузерный After Effects)

Монтаж собирается командами API EffectCraft (`window.effectcraft.execute`) —
те же эффекты и инструменты, что в AE: Lumetri Color, Curves, Photo Filter, Glow,
Add Grain, Exposure, Gaussian Blur, Motion Tile, time remap, моушен-блюр слоёв,
текстовые аниматоры (Range Selector по словам, Randomize Order).

Стиль каждого эдита задаётся прикреплённым примером (референсом).

## Файлы
- `build_project.py beats.json steps.json [ТЕКСТ_ТИТРА]` — генерирует список команд проекта (тайминг по битам)
- `beats.json` — биты трека (librosa, ~86 BPM)
- `driver.js config.json` — открывает EffectCraft в headless Chromium, загружает файлы, выполняет команды, рендерит
- `runner.js` — код, исполняемый внутри страницы EffectCraft

## Рендер
В `config.json`: `files` (cut.mp4, music.m4a), `steps`, и одно из:
`stills: [секунды]`, `frames: {from, to}` (покадровый рендер в PNG), `saveProject: true` (.ecproj).

Render Queue в EffectCraft 0.9.2 при headless-рендере теряет анимацию ключей,
поэтому кадры рендерятся через `render.frame`, а собираются ffmpeg-ом:

```bash
ffmpeg -framerate 30 -i frames/%05d.png -i music.m4a -t 74.5 -c:v libx264 -crf 16 -pix_fmt yuv420p -c:a aac -b:a 256k -af afade=t=out:st=72:d=2.5 out.mp4
```
