import { toMinutes, type DaySchedule, type DayOfWeek } from "../agenda/model";

// Draft shape for the week editors: HH:mm, one entry per range. A day with
// an empty list isn't worked (ENDPOINTS-PENDIENTES.md #7). Shared by
// Configuración (branch) and Profesionales, which edit the same thing.
export interface DraftRange {
  openTime: string; // HH:mm
  closeTime: string;
}

export interface DraftDay {
  dayOfWeek: DayOfWeek;
  label: string;
  ranges: DraftRange[];
}

export const WEEK_DAYS: { key: DayOfWeek; label: string }[] = [
  { key: "MONDAY", label: "Lunes" },
  { key: "TUESDAY", label: "Martes" },
  { key: "WEDNESDAY", label: "Miércoles" },
  { key: "THURSDAY", label: "Jueves" },
  { key: "FRIDAY", label: "Viernes" },
  { key: "SATURDAY", label: "Sábado" },
  { key: "SUNDAY", label: "Domingo" },
];

const DEFAULT_RANGE: DraftRange = { openTime: "09:00", closeTime: "18:00" };

export function toDraftWeek(schedule: DaySchedule[]): DraftDay[] {
  return WEEK_DAYS.map(({ key, label }) => ({
    dayOfWeek: key,
    label,
    ranges: (schedule.find((d) => d.dayOfWeek === key)?.ranges ?? []).map((r) => ({
      openTime: r.openTime.slice(0, 5),
      closeTime: r.closeTime.slice(0, 5),
    })),
  }));
}

export function toApiWeek(week: DraftDay[]) {
  return week.map((day) => ({
    dayOfWeek: day.dayOfWeek,
    ranges: day.ranges.map((r) => ({ openTime: `${r.openTime}:00`, closeTime: `${r.closeTime}:00` })),
  }));
}

// The same rules the API enforces, so the save button can say why first.
export function weekProblem(week: DraftDay[]): string | null {
  for (const day of week) {
    const sorted = [...day.ranges].sort((a, b) => a.openTime.localeCompare(b.openTime));
    for (let i = 0; i < sorted.length; i++) {
      const range = sorted[i];
      if (!range.openTime || !range.closeTime) return `${day.label}: falta completar una franja.`;
      if (toMinutes(range.openTime) >= toMinutes(range.closeTime)) {
        return `${day.label}: el cierre tiene que ser posterior a la apertura.`;
      }
      if (i > 0 && toMinutes(range.openTime) < toMinutes(sorted[i - 1].closeTime)) {
        return `${day.label}: las franjas se pisan.`;
      }
    }
  }
  return null;
}

export function dayHours(day: DraftDay): string {
  if (day.ranges.length === 0) return "Cerrado";
  const minutes = day.ranges.reduce((total, r) => total + toMinutes(r.closeTime) - toMinutes(r.openTime), 0);
  if (minutes <= 0) return "Revisar horario";
  return `${(minutes / 60).toFixed(minutes % 60 ? 1 : 0).replace(".", ",")} h`;
}

// A day starts with one range when switched on; "Agregar franja" opens the
// afternoon of a split day.
export function WeekRanges({
  week,
  onChange,
  closedLabel = "Cerrado",
}: {
  week: DraftDay[];
  onChange: (week: DraftDay[]) => void;
  closedLabel?: string;
}) {
  const patch = (dayOfWeek: DayOfWeek, ranges: DraftRange[]) =>
    onChange(week.map((day) => (day.dayOfWeek === dayOfWeek ? { ...day, ranges } : day)));

  return (
    <div className="week">
      {week.map((day) => {
        const closed = day.ranges.length === 0;
        const last = day.ranges[day.ranges.length - 1];
        return (
          <div key={day.dayOfWeek} className={`week-row${closed ? " off" : ""}`}>
            <label className="sw">
              <input
                type="checkbox"
                checked={!closed}
                aria-label={`${day.label}, abierto`}
                onChange={(e) => patch(day.dayOfWeek, e.target.checked ? [DEFAULT_RANGE] : [])}
              />
              <i aria-hidden="true" />
            </label>
            <span className="dname">
              <b>{day.label}</b>
              <small>{dayHours(day)}</small>
            </span>
            {closed ? (
              <span className="muted">{closedLabel}</span>
            ) : (
              <span className="ranges">
                {day.ranges.map((range, i) => (
                  <span className="times" key={i}>
                    <input
                      type="time"
                      aria-label={`${day.label}, abre${i > 0 ? ` (franja ${i + 1})` : ""}`}
                      value={range.openTime}
                      onChange={(e) =>
                        patch(
                          day.dayOfWeek,
                          day.ranges.map((r, j) => (j === i ? { ...r, openTime: e.target.value } : r)),
                        )
                      }
                    />
                    <span className="muted">a</span>
                    <input
                      type="time"
                      aria-label={`${day.label}, cierra${i > 0 ? ` (franja ${i + 1})` : ""}`}
                      value={range.closeTime}
                      onChange={(e) =>
                        patch(
                          day.dayOfWeek,
                          day.ranges.map((r, j) => (j === i ? { ...r, closeTime: e.target.value } : r)),
                        )
                      }
                    />
                    {day.ranges.length > 1 && (
                      <button
                        type="button"
                        className="ghost sm"
                        aria-label={`Quitar franja ${i + 1} de ${day.label}`}
                        onClick={() => patch(day.dayOfWeek, day.ranges.filter((_, j) => j !== i))}
                      >
                        Quitar
                      </button>
                    )}
                  </span>
                ))}
                {day.ranges.length < 3 && (
                  <button
                    type="button"
                    className="ghost sm"
                    onClick={() =>
                      patch(day.dayOfWeek, [
                        ...day.ranges,
                        // Starts after the last one so it never overlaps.
                        { openTime: last.closeTime, closeTime: "20:00" },
                      ])
                    }
                  >
                    Agregar franja
                  </button>
                )}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
