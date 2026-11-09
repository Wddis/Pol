import React from "react";
import {
  AbsoluteFill,
  Audio,
  OffthreadVideo,
  Sequence,
  interpolate,
  random,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import "@fontsource/montserrat/700.css";
import "@fontsource/montserrat/800.css";
import "@fontsource/playfair-display/600.css";
import { BEATS } from "./beats";
import {
  CAPTIONS,
  Caption,
  DROP_AT,
  END_AT,
  OUTRO_AT,
  SEGMENTS,
  Segment,
} from "./timeline";

export const SCOFIELD_FPS = 30;
export const SCOFIELD_DURATION = Math.round(END_AT * SCOFIELD_FPS);

export type ScofieldProps = {
  video: string;
  music: string;
  outroText: string;
  watermark: string;
};

const decay = (f: number, k: number) => Math.exp(-Math.max(0, f) / k);

// ---------- Один план: кроп, цветокор, эффекты на склейке ----------
const Shot: React.FC<{ s: Segment; index: number; video: string }> = ({
  s,
  index,
  video,
}) => {
  const f = useCurrentFrame();
  const { fps, width } = useVideoConfig();
  const len = Math.round((s.to - s.from) * fps);
  const h = s.hit;

  // Зум-удар на входе + медленный наезд камерой (как в примере)
  const push = interpolate(f, [0, len], [0, 0.06], { extrapolateRight: "clamp" });
  const scale = 1.04 + h.punch * decay(f, 4) + push;

  // Тряска (wiggle) — затухает
  const amp = h.shake * decay(f, 5);
  const sx = (random(`x${index}-${f}`) - 0.5) * 2 * amp;
  const sy = (random(`y${index}-${f}`) - 0.5) * 2 * amp;
  const rot = (random(`r${index}-${f}`) - 0.5) * amp * 0.06;

  const rgb = h.rgb * decay(f, 3);
  const blur = h.blur * decay(f, 1.1);
  const flash = h.flash * decay(f, 2.5);
  const fid = `fx-${index}`;
  const useFilter = rgb > 0.5 || blur > 0.5;

  // кроп 16:9 → квадрат: видео шириной 16/9 от высоты кадра
  const vw = (width * 16) / 9;
  const left = -(vw - width) * (s.pos ?? 0.5);

  return (
    <AbsoluteFill style={{ backgroundColor: "black", overflow: "hidden" }}>
      {useFilter ? (
        <svg width={0} height={0} style={{ position: "absolute" }}>
          <filter id={fid} x="-10%" y="-10%" width="120%" height="120%" colorInterpolationFilters="sRGB">
            {/* Directional Blur */}
            <feGaussianBlur in="SourceGraphic" stdDeviation={`${blur} ${blur * 0.1}`} result="b" />
            {/* Shift Channels / RGB split */}
            <feColorMatrix in="b" type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" result="r" />
            <feOffset in="r" dx={rgb} dy={0} result="r2" />
            <feColorMatrix in="b" type="matrix" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" result="g" />
            <feColorMatrix in="b" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" result="bl" />
            <feOffset in="bl" dx={-rgb} dy={0} result="b2" />
            <feBlend in="r2" in2="g" mode="screen" result="rg" />
            <feBlend in="rg" in2="b2" mode="screen" />
          </filter>
        </svg>
      ) : null}
      <AbsoluteFill
        style={{
          transform: `translate(${sx}px, ${sy}px) rotate(${rot}deg) scale(${scale})`,
          filter: useFilter ? `url(#${fid})` : undefined,
        }}
      >
        <OffthreadVideo
          src={staticFile(video)}
          muted
          trimBefore={Math.round(s.src * fps)}
          playbackRate={s.rate ?? 1}
          style={{
            position: "absolute",
            top: 0,
            left,
            width: vw,
            height: "100%",
            // Цветокор: тёмный, контрастный, тёплый — как в примере
            filter: `contrast(1.4) brightness(${s.dark ? 0.6 : 0.74}) saturate(0.9) sepia(0.22)`,
          }}
        />
      </AbsoluteFill>
      <AbsoluteFill style={{ backgroundColor: "white", opacity: flash }} />
    </AbsoluteFill>
  );
};

// ---------- Слои поверх всего видео ----------
const Grade: React.FC = () => (
  <>
    {/* тёплые тени/свет (Curves/Lumetri) */}
    <AbsoluteFill
      style={{ backgroundColor: "#ff7a2a", mixBlendMode: "soft-light", opacity: 0.32 }}
    />
    {/* виньетка */}
    <AbsoluteFill
      style={{
        background:
          "radial-gradient(ellipse at center, rgba(0,0,0,0) 45%, rgba(0,0,0,0.55) 80%, rgba(0,0,0,0.85) 100%)",
      }}
    />
  </>
);

const Grain: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <AbsoluteFill style={{ mixBlendMode: "overlay", opacity: 0.22 }}>
      <svg width="100%" height="100%">
        <filter id="grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves={2} seed={f % 50} />
          <feColorMatrix type="saturate" values="0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#grain)" />
      </svg>
    </AbsoluteFill>
  );
};

// Пульс на каждый бит внутри длинных планов (scale-bounce)
const useBeatPulse = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  let last = -1;
  for (const b of BEATS) {
    if (b <= t) last = b;
    else break;
  }
  if (last < 0 || t > OUTRO_AT) return 0;
  const since = (t - last) * fps;
  const strength = t >= DROP_AT ? 0.035 : 0.018;
  return strength * decay(since, 3);
};

// ---------- Текст: слова проявляются из блюра, с glow ----------
const GlowCaption: React.FC<{ c: Caption }> = ({ c }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const len = Math.round((c.to - c.from) * fps);
  const words = c.text.split(" ");
  const out = interpolate(f, [len - 8, len], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  return (
    <AbsoluteFill
      style={{
        justifyContent: "center",
        alignItems: "center",
        top: `${((c.y ?? 0.5) - 0.5) * 100}%`,
      }}
    >
      <div
        style={{
          fontFamily: "Montserrat",
          fontWeight: 800,
          fontSize: 44,
          letterSpacing: 1,
          color: "white",
          opacity: out,
          filter: `blur(${(1 - out) * 6}px)`,
          display: "flex",
          gap: 14,
        }}
      >
        {words.map((w, i) => {
          const start = i * 5;
          const p = interpolate(f, [start, start + 7], [0, 1], {
            extrapolateLeft: "clamp",
            extrapolateRight: "clamp",
          });
          const flicker = f - start < 10 && random(`${c.text}${i}${f}`) < 0.25 ? 0.4 : 1;
          return (
            <span
              key={i}
              style={{
                opacity: p * flicker,
                filter: `blur(${(1 - p) * 8}px)`,
                color: c.color === "#ffffff" ? "#ffffff" : c.color,
                textShadow: `0 0 6px ${c.color}, 0 0 16px ${c.color}, 0 0 34px ${c.color}aa`,
              }}
            >
              {w}
            </span>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};

// ---------- Финальный титр: буквы мерцают и загораются (как в примере) ----------
const Outro: React.FC<{ text: string }> = ({ text }) => {
  const f = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const fadeOut = interpolate(f, [durationInFrames - 15, durationInFrames], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const warm = interpolate(f, [30, 50], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const glow = `rgb(${255}, ${Math.round(255 - warm * 40)}, ${Math.round(255 - warm * 170)})`;

  return (
    <AbsoluteFill style={{ backgroundColor: "black", justifyContent: "center", alignItems: "center" }}>
      <div
        style={{
          fontFamily: "Playfair Display",
          fontWeight: 600,
          fontSize: 92,
          letterSpacing: 18,
          transform: "scaleY(1.35)",
          display: "flex",
          opacity: fadeOut,
        }}
      >
        {text.split("").map((ch, i) => {
          const on = f > 4 + random(`o${i}`) * 22;
          const blink = f < 40 && random(`b${i}-${f}`) < 0.18;
          const vis = on && !blink ? 1 : f > 4 ? 0.15 : 0;
          return (
            <span
              key={i}
              style={{
                color: "#fff",
                opacity: vis,
                textShadow: `0 0 8px ${glow}, 0 0 22px ${glow}, 0 0 48px ${glow}`,
              }}
            >
              {ch === " " ? " " : ch}
            </span>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};

export const ScofieldEdit: React.FC<ScofieldProps> = ({
  video,
  music,
  outroText,
  watermark,
}) => {
  const { fps } = useVideoConfig();
  const pulse = useBeatPulse();
  const fr = (t: number) => Math.round(t * fps);

  return (
    <AbsoluteFill style={{ backgroundColor: "black" }}>
      <AbsoluteFill style={{ transform: `scale(${1 + pulse})` }}>
        {SEGMENTS.map((s, i) => (
          <Sequence key={i} from={fr(s.from)} durationInFrames={fr(s.to) - fr(s.from)}>
            <Shot s={s} index={i} video={video} />
          </Sequence>
        ))}
      </AbsoluteFill>

      <Grade />
      <Grain />

      {CAPTIONS.map((c, i) => (
        <Sequence key={i} from={fr(c.from)} durationInFrames={fr(c.to) - fr(c.from)}>
          <GlowCaption c={c} />
        </Sequence>
      ))}

      {watermark ? (
        <AbsoluteFill style={{ justifyContent: "flex-end", alignItems: "center", paddingBottom: 40 }}>
          <div style={{ fontFamily: "Montserrat", fontWeight: 700, fontSize: 22, letterSpacing: 2, color: "white", opacity: 0.18 }}>
            {watermark}
          </div>
        </AbsoluteFill>
      ) : null}

      <Sequence from={fr(OUTRO_AT)}>
        <Outro text={outroText} />
      </Sequence>

      <Audio
        src={staticFile(music)}
        volume={(f) =>
          interpolate(f, [fr(END_AT - 2.5), fr(END_AT)], [1, 0], {
            extrapolateLeft: "clamp",
            extrapolateRight: "clamp",
          })
        }
      />
    </AbsoluteFill>
  );
};
