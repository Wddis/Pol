# TikTok эдиты на Remotion

Видео для TikTok (1080×1920, 30 fps), собранные кодом на React с помощью [Remotion](https://www.remotion.dev/).

## Запуск

```bash
cd tiktok
npm install
npm run dev        # открывает Remotion Studio в браузере — превью и правка параметров
npm run render     # рендер TikTokEdit -> out/tiktok.mp4
npm run render:intro  # рендер TextIntro -> out/intro.mp4
```

Нужен Node.js 18+. Chrome Remotion скачает сам при первом рендере.

## Как сделать свой эдит

1. Положи клипы и музыку в `public/` (например `clip1.mp4`, `clip2.mp4`, `music.mp3`).
2. В `src/Root.tsx` в `defaultProps` композиции `TikTokEdit` пропиши:
   - `clips: ["clip1.mp4", "clip2.mp4"]` — клипы делят длительность поровну, на каждом стыке вспышка и zoom;
   - `music: "music.mp3"`;
   - `captions` — субтитры с таймингом в секундах (`from` / `to`);
   - `title` — плашка сверху, `accentColor` — цвет обводки субтитров.
3. Длительность — `durationInFrames` (сейчас `FPS * 15` = 15 секунд).

Всё это можно менять и прямо в Studio (панель Props справа), без кода.

Параметры можно передать и при рендере:

```bash
npx remotion render TikTokEdit out/my.mp4 --props='{"title":"Мой эдит","clips":["a.mp4"],"music":"","captions":[],"accentColor":"#25f4ee"}'
```

## Структура

- `src/Root.tsx` — список композиций (размеры, fps, длительность, параметры)
- `src/compositions/TikTokEdit.tsx` — нарезка клипов + вспышки + «прыгающие» субтитры + музыка
- `src/compositions/TextIntro.tsx` — короткое glitch-интро с текстом

Лицензия Remotion: бесплатно для частных лиц и команд до 3 человек, см. https://www.remotion.dev/license
