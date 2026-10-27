// Ключи ODA и тарифы — как bot/oda_bot/keys.py: формат ключа, срок тарифа, цена с учётом устройств.

// Без похожих символов (0/O, 1/I/L, 5/S, 8/B): ключ переписывают руками с экрана
export const ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ234679";
const GROUPS = 3;
const GROUP_LEN = 4;
const PREFIX = "ODA";
export const MIN_DEVICES = 1;
export const MAX_DEVICES = 5;

export interface Tariff {
  id: string;
  title: string;
  days: number;
}

export const TARIFFS: Record<string, Tariff> = {
  week: { id: "week", title: "1 неделя", days: 7 },
  month: { id: "month", title: "1 месяц", days: 30 },
  q: { id: "q", title: "3 месяца", days: 90 },
};
export const TRIAL: Tariff = { id: "trial", title: "Пробный день", days: 1 };
export const ALL_TARIFFS: Record<string, Tariff> = { ...TARIFFS, [TRIAL.id]: TRIAL };

/** «ODA-7K2Q-M9XD-4TPA»: 12 случайных символов, ~57 бит — перебором не подобрать. */
export function generateKey(): string {
  const chars: string[] = [];
  const limit = 256 - (256 % ALPHABET.length); // без перекоса в пользу первых букв
  while (chars.length < GROUPS * GROUP_LEN) {
    for (const b of crypto.getRandomValues(new Uint8Array(16))) {
      if (b < limit && chars.length < GROUPS * GROUP_LEN) chars.push(ALPHABET[b % ALPHABET.length]);
    }
  }
  const raw = chars.join("");
  return [PREFIX, raw.slice(0, 4), raw.slice(4, 8), raw.slice(8, 12)].join("-");
}

/** Ключ, как его ввели (пробелы, без дефисов, строчные, без «ODA»), — в канонический вид; null — не ключ. */
export function normalize(text: string): string | null {
  let raw = (text || "").replace(/[\s\-_]+/g, "").toUpperCase();
  if (raw.startsWith(PREFIX)) raw = raw.slice(PREFIX.length);
  if (raw.length !== GROUPS * GROUP_LEN || [...raw].some((c) => !ALPHABET.includes(c))) return null;
  return [PREFIX, raw.slice(0, 4), raw.slice(4, 8), raw.slice(8, 12)].join("-");
}

/** Цена тарифа на devices устройств: каждое сверх первого — +extraPct% к базовой (округление как в Python). */
export function price(base: number, devices: number, extraPct: number): number {
  devices = Math.max(MIN_DEVICES, Math.min(MAX_DEVICES, devices));
  const x = base * (1 + ((devices - 1) * extraPct) / 100);
  const f = Math.floor(x);
  const d = x - f;
  if (Math.abs(d - 0.5) < 1e-9) return f % 2 === 0 ? f : f + 1;
  return Math.round(x);
}

/** plural(2, "день", "дня", "дней") → «дня». */
export function plural(n: number, one: string, few: string, many: string): string {
  n = Math.abs(n) % 100;
  if (n >= 11 && n <= 14) return many;
  n %= 10;
  return n === 1 ? one : n >= 2 && n <= 4 ? few : many;
}

// ---------- сроки любой длины (ключи от администратора) ----------

export const MINUTE = 60;
export const HOUR = 3600;
export const DAY_S = 86400;
export const MONTH = 30 * DAY_S;
export const YEAR = 365 * DAY_S;
/** «Бессрочно» — 100 лет: срок и лицензия считаются как обычно, а в тексте — «бессрочно». */
export const FOREVER = 100 * YEAR;

/** Единицы срока в меню администратора: m — минуты 1–60, h — часы 1–24, d — дни 1–30, M — месяцы 1–12,
 *  y — годы 1–10, f — бессрочно. */
export const UNITS: Record<string, { title: string; seconds: number; min: number; max: number; quick: number[] }> = {
  m: { title: "Минуты", seconds: MINUTE, min: 1, max: 60, quick: [5, 10, 15, 30, 60] },
  h: { title: "Часы", seconds: HOUR, min: 1, max: 24, quick: [1, 3, 6, 12, 24] },
  d: { title: "Дни", seconds: DAY_S, min: 1, max: 30, quick: [1, 3, 7, 14, 30] },
  M: { title: "Месяцы", seconds: MONTH, min: 1, max: 12, quick: [1, 2, 3, 6, 12] },
  y: { title: "Годы", seconds: YEAR, min: 1, max: 10, quick: [1, 2, 3, 5, 10] },
  f: { title: "∞", seconds: FOREVER, min: 1, max: 1, quick: [] },
};

export function unitSeconds(unit: string, n: number): number {
  const u = UNITS[unit] ?? UNITS.d;
  if (unit === "f") return FOREVER;
  return u.seconds * Math.max(u.min, Math.min(u.max, n));
}

/** Срок словами: «30 минут», «3 часа», «7 дней», «1 месяц», «2 года», «бессрочно». */
export function durationText(seconds: number): string {
  if (seconds >= FOREVER) return "бессрочно";
  const pick = (n: number, one: string, few: string, many: string) => `${n} ${plural(n, one, few, many)}`;
  if (seconds >= YEAR && seconds % YEAR === 0) return pick(seconds / YEAR, "год", "года", "лет");
  if (seconds >= MONTH && seconds % MONTH === 0) return pick(seconds / MONTH, "месяц", "месяца", "месяцев");
  if (seconds >= DAY_S && seconds % DAY_S === 0) return pick(seconds / DAY_S, "день", "дня", "дней");
  if (seconds >= HOUR && seconds % HOUR === 0) return pick(seconds / HOUR, "час", "часа", "часов");
  return pick(Math.max(1, Math.round(seconds / MINUTE)), "минута", "минуты", "минут");
}

/** Сколько осталось: дни (вверх), меньше суток — часы, меньше часа — минуты. */
export function leftText(seconds: number): string {
  if (seconds >= FOREVER / 2) return "бессрочно";
  if (seconds >= DAY_S) {
    const n = Math.ceil(seconds / DAY_S);
    return `${n} ${plural(n, "день", "дня", "дней")}`;
  }
  if (seconds >= HOUR) {
    const n = Math.ceil(seconds / HOUR);
    return `${n} ${plural(n, "час", "часа", "часов")}`;
  }
  const n = Math.max(1, Math.ceil(seconds / MINUTE));
  return `${n} ${plural(n, "минута", "минуты", "минут")}`;
}

/** Срок в выбранной единице, как его задал администратор: «24 часа», «30 дней», «12 месяцев». */
export function unitText(unit: string, n: number): string {
  if (unit === "f") return "бессрочно";
  const u = UNITS[unit] ?? UNITS.d;
  n = Math.max(u.min, Math.min(u.max, n));
  const forms: Record<string, [string, string, string]> = {
    m: ["минута", "минуты", "минут"], h: ["час", "часа", "часов"], d: ["день", "дня", "дней"],
    M: ["месяц", "месяца", "месяцев"], y: ["год", "года", "лет"],
  };
  const f = forms[unit] ?? forms.d;
  return `${n} ${plural(n, f[0], f[1], f[2])}`;
}
