import { expect, test } from "vitest";
import { dayHours, toApiWeek, toDraftWeek, WEEK_DAYS, weekProblem, type DraftDay } from "./WeekRanges";

const day = (ranges: [string, string][]): DraftDay => ({
  dayOfWeek: "MONDAY",
  label: "Lunes",
  ranges: ranges.map(([openTime, closeTime]) => ({ openTime, closeTime })),
});

test("toDraftWeek gives all seven days in order, trimming seconds", () => {
  const week = toDraftWeek([
    { dayOfWeek: "MONDAY", ranges: [{ openTime: "09:00:00", closeTime: "13:00:00" }] },
  ]);
  expect(week.map((d) => d.dayOfWeek)).toEqual(WEEK_DAYS.map((d) => d.key));
  expect(week[0].ranges).toEqual([{ openTime: "09:00", closeTime: "13:00" }]);
  // Days the API didn't send come back closed, not missing.
  expect(week.slice(1).every((d) => d.ranges.length === 0)).toBe(true);
});

test("toApiWeek adds the seconds back and keeps closed days as empty lists", () => {
  const api = toApiWeek(toDraftWeek([{ dayOfWeek: "MONDAY", ranges: [{ openTime: "09:00:00", closeTime: "13:00:00" }] }]));
  expect(api[0]).toEqual({ dayOfWeek: "MONDAY", ranges: [{ openTime: "09:00:00", closeTime: "13:00:00" }] });
  expect(api[1]).toEqual({ dayOfWeek: "TUESDAY", ranges: [] });
});

test("weekProblem accepts a closed day, one range, and a split day", () => {
  expect(weekProblem([day([])])).toBeNull();
  expect(weekProblem([day([["09:00", "18:00"]])])).toBeNull();
  expect(weekProblem([day([["09:00", "13:00"], ["16:00", "20:00"]])])).toBeNull();
  // Touching ends don't overlap: the afternoon may start when the morning ends.
  expect(weekProblem([day([["09:00", "13:00"], ["13:00", "20:00"]])])).toBeNull();
});

test("weekProblem rejects a blank, an inverted and a zero-length range", () => {
  expect(weekProblem([day([["", "18:00"]])])).toMatch(/falta completar/);
  expect(weekProblem([day([["18:00", "09:00"]])])).toMatch(/posterior a la apertura/);
  expect(weekProblem([day([["09:00", "09:00"]])])).toMatch(/posterior a la apertura/);
});

test("weekProblem catches overlaps whatever order the ranges come in", () => {
  expect(weekProblem([day([["09:00", "14:00"], ["13:00", "20:00"]])])).toMatch(/se pisan/);
  expect(weekProblem([day([["13:00", "20:00"], ["09:00", "14:00"]])])).toMatch(/se pisan/);
});

test("weekProblem names the offending day", () => {
  const week: DraftDay[] = [day([["09:00", "18:00"]]), { ...day([["18:00", "09:00"]]), label: "Martes" }];
  expect(weekProblem(week)).toBe("Martes: el cierre tiene que ser posterior a la apertura.");
});

test("dayHours sums the ranges of a split day, in comma decimals", () => {
  expect(dayHours(day([]))).toBe("Cerrado");
  expect(dayHours(day([["09:00", "18:00"]]))).toBe("9 h");
  expect(dayHours(day([["09:00", "13:00"], ["16:00", "20:00"]]))).toBe("8 h");
  expect(dayHours(day([["09:00", "12:30"]]))).toBe("3,5 h");
  expect(dayHours(day([["18:00", "09:00"]]))).toBe("Revisar horario");
});
