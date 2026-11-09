import React from "react";
import {
  AbsoluteFill,
  interpolate,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

// Короткое текстовое интро с glitch-эффектом
export const TextIntro: React.FC<{ text: string; subtitle: string }> = ({
  text,
  subtitle,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const scale = spring({ frame, fps, config: { damping: 10 } });
  const glitch = frame % 12 < 2 ? 12 : 0;
  const subOpacity = interpolate(frame, [fps * 0.8, fps * 1.3], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  const base: React.CSSProperties = {
    position: "absolute",
    fontFamily: "Arial Black, Impact, sans-serif",
    fontSize: 260,
    fontWeight: 900,
  };

  return (
    <AbsoluteFill
      style={{ backgroundColor: "#000", justifyContent: "center", alignItems: "center" }}
    >
      <div style={{ transform: `scale(${scale})`, position: "relative" }}>
        <div style={{ ...base, color: "#25f4ee", transform: `translate(${-glitch}px, 0)` }}>
          {text}
        </div>
        <div style={{ ...base, color: "#fe2c55", transform: `translate(${glitch}px, 0)` }}>
          {text}
        </div>
        <div style={{ ...base, position: "relative", color: "white" }}>{text}</div>
      </div>
      <div
        style={{
          position: "absolute",
          bottom: 500,
          opacity: subOpacity,
          color: "white",
          fontFamily: "sans-serif",
          fontSize: 60,
        }}
      >
        {subtitle}
      </div>
    </AbsoluteFill>
  );
};
