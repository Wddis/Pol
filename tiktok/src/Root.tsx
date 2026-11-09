import { Composition } from "remotion";
import { TikTokEdit, tikTokEditSchema } from "./compositions/TikTokEdit";
import { TextIntro } from "./compositions/TextIntro";
import {
  ScofieldEdit,
  SCOFIELD_DURATION,
  SCOFIELD_FPS,
} from "./compositions/ScofieldEdit";

// TikTok формат: вертикальное видео 9:16
export const WIDTH = 1080;
export const HEIGHT = 1920;
export const FPS = 30;

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="TikTokEdit"
        component={TikTokEdit}
        width={WIDTH}
        height={HEIGHT}
        fps={FPS}
        durationInFrames={FPS * 15}
        schema={tikTokEditSchema}
        defaultProps={{
          title: "POV: ты делаешь эдиты кодом",
          // Положи свои клипы в папку public/ и впиши имена файлов сюда,
          // например: ["clip1.mp4", "clip2.mp4"]. Пусто — будут цветные заглушки.
          clips: [] as string[],
          music: "", // например "music.mp3" из public/
          captions: [
            { text: "Это", from: 0.5, to: 1.2 },
            { text: "сделано", from: 1.2, to: 2.0 },
            { text: "в Remotion", from: 2.0, to: 3.5 },
            { text: "на React", from: 4.0, to: 6.0 },
          ],
          accentColor: "#fe2c55",
        }}
      />
      <Composition
        id="TextIntro"
        component={TextIntro}
        width={WIDTH}
        height={HEIGHT}
        fps={FPS}
        durationInFrames={FPS * 4}
        defaultProps={{ text: "ЭДИТ", subtitle: "@polworker" }}
      />
      {/* Эдит по примеру: квадрат 1:1 как в референсе, нарезка + свой трек */}
      <Composition
        id="ScofieldEdit"
        component={ScofieldEdit}
        width={1080}
        height={1080}
        fps={SCOFIELD_FPS}
        durationInFrames={SCOFIELD_DURATION}
        defaultProps={{
          video: "cut.mp4",
          music: "music.m4a",
          outroText: "SCOFIELD",
          watermark: "",
        }}
      />
    </>
  );
};
