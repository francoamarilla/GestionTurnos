import { expect, test } from "vitest";
import { daysInRange, percentChange, presetRange, previousRange, rangeLabel } from "./reportRange";

// 2026-03-01 is a Sunday, so these also cover the month and week boundaries.
test("presets are inclusive and anchored on today", () => {
  expect(presetRange("hoy", "2026-03-01")).toEqual({ from: "2026-03-01", to: "2026-03-01", preset: "hoy" });
  expect(presetRange("ayer", "2026-03-01")).toEqual({ from: "2026-02-28", to: "2026-02-28", preset: "ayer" });
  expect(presetRange("semana", "2026-03-01")).toEqual({ from: "2026-02-23", to: "2026-03-01", preset: "semana" });
  expect(presetRange("mes", "2026-03-01")).toEqual({ from: "2026-03-01", to: "2026-03-01", preset: "mes" });
  expect(presetRange("d30", "2026-03-01")).toEqual({ from: "2026-01-31", to: "2026-03-01", preset: "d30" });
});

test("preset lengths are 1, 1, 7, and 30 days", () => {
  expect(daysInRange(presetRange("hoy", "2026-03-01"))).toHaveLength(1);
  expect(daysInRange(presetRange("semana", "2026-03-01"))).toHaveLength(7);
  expect(daysInRange(presetRange("d30", "2026-03-01"))).toHaveLength(30);
});

test("daysInRange crosses a leap day and ends empty when from is after to", () => {
  expect(daysInRange({ from: "2024-02-28", to: "2024-03-01" })).toEqual(["2024-02-28", "2024-02-29", "2024-03-01"]);
  expect(daysInRange({ from: "2026-03-02", to: "2026-03-01" })).toEqual([]);
});

test("daysInRange stops at the 800-day cap instead of spinning", () => {
  expect(daysInRange({ from: "2026-01-01", to: "2999-01-01" })).toHaveLength(800);
});

test("previousRange is the same length and ends the day before", () => {
  const week = presetRange("semana", "2026-03-01");
  expect(previousRange(week)).toEqual({ from: "2026-02-16", to: "2026-02-22" });
  expect(daysInRange(previousRange(week))).toHaveLength(7);
});

test("rangeLabel collapses a single day", () => {
  expect(rangeLabel({ from: "2026-03-01", to: "2026-03-01" })).toBe("dom 1 mar");
  expect(rangeLabel({ from: "2026-02-24", to: "2026-03-01" })).toBe("mar 24 feb al dom 1 mar");
});

test("percentChange is null when there is no baseline", () => {
  expect(percentChange(10, 0)).toBeNull();
  expect(percentChange(0, 0)).toBeNull();
  expect(percentChange(150, 100)).toBe(50);
  expect(percentChange(50, 100)).toBe(-50);
});
