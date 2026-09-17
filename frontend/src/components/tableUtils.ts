// Pure helpers for the list screens (see TableKit.tsx).

export function initials(name: string | null | undefined): string {
  return (name ?? "?")
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0].toUpperCase())
    .join("");
}

export type ActiveFilter = "activos" | "inactivos" | "todos";

export function matchesActive(active: boolean, filter: ActiveFilter): boolean {
  return filter === "todos" || (filter === "activos") === active;
}

export type Sort<K extends string> = { key: K; dir: 1 | -1 } | null;

export function sortRows<T, K extends string>(rows: T[], sort: Sort<K>, value: (row: T, key: K) => number | string): T[] {
  if (!sort) return rows;
  return [...rows].sort((a, b) => {
    const x = value(a, sort.key);
    const y = value(b, sort.key);
    return (x > y ? 1 : x < y ? -1 : 0) * sort.dir;
  });
}

// Client-side paging for the small catalogs (services, professionals).
export function paginate<T>(rows: T[], page: number, size: number) {
  const pages = Math.max(1, Math.ceil(rows.length / size));
  const current = Math.min(page, pages - 1);
  return {
    rows: rows.slice(current * size, (current + 1) * size),
    page: current,
    pages,
    from: current * size + 1,
    to: Math.min((current + 1) * size, rows.length),
    total: rows.length,
  };
}
