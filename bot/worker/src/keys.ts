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
