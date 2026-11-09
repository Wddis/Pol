import { BEATS } from "./beats";

const B = (i: number) => BEATS[i];

// Сила эффектов на склейке (аналоги AE: flash-solid, Transform-shake,
// Shift Channels / RGB split, Directional Blur, scale punch)
export type HitLevel = {
  flash: number; // непрозрачность белой вспышки
  punch: number; // зум-удар
  shake: number; // тряска, px
  rgb: number; // RGB-сдвиг, px
  blur: number; // направленный блюр, px
};

export const SOFT: HitLevel = { flash: 0, punch: 0.03, shake: 0, rgb: 0, blur: 0 };
export const MID: HitLevel = { flash: 0.35, punch: 0.08, shake: 10, rgb: 6, blur: 18 };
export const HARD: HitLevel = { flash: 0.25, punch: 0.12, shake: 18, rgb: 12, blur: 16 };
export const DROP: HitLevel = { flash: 0.7, punch: 0.18, shake: 26, rgb: 18, blur: 24 };

export type Segment = {
  from: number; // сек в треке
  to: number;
  src: number; // сек в исходной нарезке
  rate?: number; // скорость (0.5 = слоумо)
  pos?: number; // горизонтальный кроп 0..1 (где лицо)
  hit: HitLevel;
  dark?: boolean; // дополнительное затемнение (как тёмные сцены в примере)
};

export const DROP_AT = B(68); // 58.03 — дроп после паузы
export const BREAK_AT = 55.0; // трек почти затихает
export const OUTRO_AT = B(86); // 70.47
export const END_AT = 74.5;

const seg = (
  fromBeat: number,
  toBeat: number,
  src: number,
  hit: HitLevel,
  opts: Partial<Segment> = {},
): Segment => ({ from: B(fromBeat), to: B(toBeat), src, hit, ...opts });

export const SEGMENTS: Segment[] = [
  // --- Вступление: медленно, длинные планы, текст ---
  { from: 0, to: 5.56, src: 1.3, rate: 0.8, hit: SOFT, pos: 0.5, dark: true },
  { from: 5.56, to: 8.34, src: 14.6, hit: SOFT, pos: 0.5 },
  { from: 8.34, to: B(0), src: 17.85, rate: 0.62, hit: SOFT, pos: 0.4, dark: true },

  // --- Бит вошёл: склейка каждые 4 бита ---
  seg(0, 4, 7.6, DROP, { rate: 0.6, pos: 0.35 }),
  seg(4, 8, 9.4, MID, { pos: 0.5 }),
  seg(8, 12, 12.65, MID, { rate: 0.63, pos: 0.65 }),
  seg(12, 16, 19.6, MID, { rate: 0.6, pos: 0.62 }),
  seg(16, 20, 21.4, MID, { pos: 0.6 }),
  seg(20, 24, 24.72, MID, { rate: 0.6, pos: 0.42 }),
  seg(24, 28, 26.42, MID, { rate: 0.6, pos: 0.42 }),
  seg(28, 32, 28.3, MID, { pos: 0.55 }),

  // --- Разгон: каждые 2 бита ---
  seg(32, 34, 31.6, HARD, { pos: 0.45 }),
  seg(34, 36, 33.3, HARD, { pos: 0.45 }),
  seg(36, 38, 34.9, HARD, { pos: 0.55 }),
  seg(38, 40, 36.8, HARD, { pos: 0.6 }),
  seg(40, 42, 38.5, HARD, { pos: 0.6 }),
  seg(42, 44, 40.2, HARD, { pos: 0.55 }),
  seg(44, 46, 41.9, HARD, { pos: 0.55 }),
  seg(46, 48, 43.6, HARD, { pos: 0.5 }),
  seg(48, 50, 46.2, HARD, { pos: 0.6 }),
  seg(50, 52, 47.2, HARD, { pos: 0.6 }),
  seg(52, 54, 48.7, HARD, { pos: 0.5 }),
  seg(54, 56, 50.5, HARD, { pos: 0.5 }),
  seg(56, 58, 53.0, HARD, { pos: 0.6 }),
  seg(58, 60, 54.8, HARD, { pos: 0.6 }),
  seg(60, 62, 57.3, HARD, { pos: 0.78 }),
  { from: B(62), to: BREAK_AT, src: 59.0, hit: HARD, pos: 0.78 },

  // --- Пауза в треке: слоумо в темноте + текст ---
  { from: BREAK_AT, to: DROP_AT, src: 62.4, rate: 0.5, hit: MID, pos: 0.72, dark: true },

  // --- ДРОП: склейка на каждый бит ---
  seg(68, 69, 60.8, DROP, { pos: 0.3 }),
  seg(69, 70, 65.9, DROP, { pos: 0.5 }),
  seg(70, 71, 67.5, DROP, { pos: 0.6 }),
  seg(71, 72, 68.45, DROP, { pos: 0.35 }),
  seg(72, 73, 69.4, DROP, { pos: 0.65 }),
  seg(73, 74, 70.8, DROP, { pos: 0.65 }),
  seg(74, 75, 71.9, DROP, { pos: 0.45 }),
  seg(75, 76, 72.8, DROP, { pos: 0.45 }),
  seg(76, 77, 73.6, DROP, { pos: 0.5 }),
  seg(77, 78, 74.4, DROP, { pos: 0.75 }),
  seg(78, 79, 76.2, DROP, { pos: 0.75 }),
  seg(79, 80, 77.9, DROP, { pos: 0.4 }),
  seg(80, 81, 79.6, DROP, { pos: 0.5 }),
  seg(81, 82, 81.6, DROP, { pos: 0.45, dark: false }),
  seg(82, 83, 84.7, DROP, { pos: 0.6 }),
  seg(83, 84, 86.4, DROP, { pos: 0.6 }),
  seg(84, 86, 87.2, DROP, { rate: 0.5, pos: 0.5 }),
];

// Текст поверх — в стиле примера: слова проявляются из блюра, с glow
export type Caption = {
  from: number;
  to: number;
  text: string;
  color: string; // цвет свечения
  y?: number; // 0..1 по вертикали
};

export const CAPTIONS: Caption[] = [
  { from: 0.8, to: 5.2, text: "MICHAEL SCOFIELD", color: "#ffffff", y: 0.5 },
  { from: 5.9, to: 8.2, text: "I'M GONNA GET YOU OUT", color: "#ffd60a", y: 0.55 },
  { from: 8.6, to: 11.0, text: "FOX RIVER", color: "#ff2a2a", y: 0.5 },
  { from: BREAK_AT + 0.2, to: DROP_AT - 0.05, text: "ONE WAY OUT", color: "#ff2a2a", y: 0.5 },
];
