// Date range shared by Ventas and Estadísticas (Mockups/ventas-stats-mockup.html):
// one range, remembered while the app is open, so switching between the two
// screens keeps the same slice.
import { addDays } from "../agenda/model";

export type PresetKey = "hoy" | "ayer" | "semana" | "mes" | "d30";

export interface Range {
  from: string; // ISO dates, inclusive
  to: string;
  preset: PresetKey | "custom";
}

export const PRESETS: { key: PresetKey; label: string }[] = [
  { key: "hoy", label: "Hoy" },
  { key: "ayer", label: "Ayer" },
  { key: "semana", label: "Últimos 7 días" },
  { key: "mes", label: "Este mes" },
  { key: "d30", label: "Últimos 30 días" },
];

export function presetRange(key: PresetKey, today: string): Range {
  switch (key) {
    case "hoy":
      return { from: today, to: today, preset: key };
    case "ayer":
      return { from: addDays(today, -1), to: addDays(today, -1), preset: key };
    case "semana":
      return { from: addDays(today, -6), to: today, preset: key };
    case "mes":
      return { from: `${today.slice(0, 8)}01`, to: today, preset: key };
    case "d30":
      return { from: addDays(today, -29), to: today, preset: key };
  }
}

export function daysInRange(range: { from: string; to: string }): string[] {
  const days: string[] = [];
  // ponytail: capped at ~2 years so a mistyped year can't spin forever.
  for (let d = range.from; d <= range.to && days.length < 800; d = addDays(d, 1)) days.push(d);
  return days;
}

// Same length, immediately before.
export function previousRange(range: { from: string; to: string }): { from: string; to: string } {
  const length = daysInRange(range).length;
  return { from: addDays(range.from, -length), to: addDays(range.from, -1) };
}

let remembered: { range: Range; compare: boolean } | null = null;

export function rememberedRange(today: string): { range: Range; compare: boolean } {
  return remembered ?? { range: presetRange("semana", today), compare: true };
}

export function rememberRange(range: Range, compare: boolean) {
  remembered = { range, compare };
}

const DAYS = ["dom", "lun", "mar", "mié", "jue", "vie", "sáb"];
const MONTHS = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"];

// "mié 16 sep"
export function shortDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  return `${DAYS[date.getDay()]} ${d} ${MONTHS[m - 1]}`;
}

export function rangeLabel(range: { from: string; to: string }): string {
  return range.from === range.to ? shortDate(range.from) : `${shortDate(range.from)} al ${shortDate(range.to)}`;
}

// Percent change, or null when there's nothing to compare against.
export function percentChange(current: number, previous: number): number | null {
  if (!previous) return null;
  return ((current - previous) / previous) * 100;
}
