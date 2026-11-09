import React from "react";
import {
  AbsoluteFill,
  Audio,
  OffthreadVideo,
  Sequence,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { z } from "zod";
import { zColor } from "@remotion/zod-types";

export const tikTokEditSchema = z.object({
  title: z.string(),
  clips: z.array(z.string()),
  music: z.string(),
  captions: z.array(
    z.object({ text: z.string(), from: z.number(), to: z.number() }),
  ),
  accentColor: zColor(),
});

type Props = z.infer<typeof tikTokEditSchema>;

const PLACEHOLDER_COLORS = ["#25f4ee", "#fe2c55", "#7b2ff7", "#ffb800"];

// Один клип: лёгкий zoom-in + вспышка на стыке (классика эдитов)
const Clip: React.FC<{ src?: string; index: number; length: number }> = ({
  src,
  index,
  length,
}) => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, length], [1.15, 1.0], {
    extrapolateRight: "clamp",
  });
  const flash = interpolate(frame, [0, 4], [0.8, 0], {
    extrapolateRight: "clamp",
  });

  return (
    <AbsoluteFill style={{ backgroundColor: "black" }}>
      <AbsoluteFill style={{ transform: `scale(${scale})` }}>
        {src ? (
          <OffthreadVideo
            src={staticFile(src)}
            muted
            style={{ width: "100%", height: "100%", objectFit: "cover" }}
          />
        ) : (
          <AbsoluteFill
            style={{
              background: `linear-gradient(160deg, ${
                PLACEHOLDER_COLORS[index % PLACEHOLDER_COLORS.length]
              }, #000)`,
              justifyContent: "flex-end",
              alignItems: "center",
              paddingBottom: 450,
              color: "rgba(255,255,255,0.5)",
              fontSize: 60,
              fontFamily: "sans-serif",
              opacity: 0.9,
            }}
          >
            Клип {index + 1}
          </AbsoluteFill>
        )}
      </AbsoluteFill>
      <AbsoluteFill style={{ backgroundColor: "white", opacity: flash }} />
    </AbsoluteFill>
  );
};

// Субтитр, который «выпрыгивает» пружиной
const Caption: React.FC<{ text: string; color: string }> = ({ text, color }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 12, stiffness: 200 } });

  return (
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
      <div
        style={{
          transform: `scale(${pop})`,
          fontFamily: "Arial Black, Impact, sans-serif",
          fontSize: 120,
          fontWeight: 900,
          color: "white",
          textTransform: "uppercase",
          textAlign: "center",
          padding: "0 60px",
          WebkitTextStroke: `6px ${color}`,
          paintOrder: "stroke fill",
          textShadow: "0 8px 30px rgba(0,0,0,0.6)",
        }}
      >
        {text}
      </div>
    </AbsoluteFill>
  );
};

export const TikTokEdit: React.FC<Props> = ({
  title,
  clips,
  music,
  captions,
  accentColor,
}) => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const sources = clips.length > 0 ? clips : [undefined, undefined, undefined];
  const clipLength = Math.ceil(durationInFrames / sources.length);

  const titleOpacity = interpolate(frame, [0, 10, fps * 2.5, fps * 3], [0, 1, 1, 0], {
    extrapolateRight: "clamp",
  });

  return (
    <AbsoluteFill style={{ backgroundColor: "black" }}>
      {sources.map((src, i) => (
        <Sequence key={i} from={i * clipLength} durationInFrames={clipLength}>
          <Clip src={src} index={i} length={clipLength} />
        </Sequence>
      ))}

      {captions.map((c, i) => (
        <Sequence
          key={i}
          from={Math.round(c.from * fps)}
          durationInFrames={Math.max(1, Math.round((c.to - c.from) * fps))}
        >
          <Caption text={c.text} color={accentColor} />
        </Sequence>
      ))}

      {/* Заголовок сверху — держим в безопасной зоне TikTok */}
      <AbsoluteFill style={{ alignItems: "center", paddingTop: 220 }}>
        <div
          style={{
            opacity: titleOpacity,
            background: "white",
            color: "black",
            fontFamily: "sans-serif",
            fontWeight: 700,
            fontSize: 54,
            padding: "18px 32px",
            borderRadius: 16,
            maxWidth: 900,
            textAlign: "center",
          }}
        >
          {title}
        </div>
      </AbsoluteFill>

      {music ? <Audio src={staticFile(music)} /> : null}
    </AbsoluteFill>
  );
};
