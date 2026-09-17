import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { Dialog } from "./Dialog";
import { initials, type ActiveFilter, type Sort } from "./tableUtils";

// Building blocks for the list screens (Mockups/tablas-mockup.html).

export function Avatar({ name, color, round = false }: { name: string | null; color: string; round?: boolean }) {
  return (
    <span className={`sq${round ? " round" : ""}`} style={{ background: color }} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

export function IdCell({ avatar, title, subtitle }: { avatar: ReactNode; title: ReactNode; subtitle: ReactNode }) {
  return (
    <div className="idcell">
      {avatar}
      <div className="tx">
        <b>{title}</b>
        <span>{subtitle}</span>
      </div>
    </div>
  );
}

export function SearchBox({
  value,
  placeholder,
  onChange,
}: {
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="search">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="11" cy="11" r="7" />
        <path d="M20 20l-4-4" />
      </svg>
      <input
        type="search"
        value={value}
        placeholder={placeholder}
        aria-label={placeholder}
        autoComplete="off"
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}

export function ActiveChips({
  value,
  counts,
  onChange,
}: {
  value: ActiveFilter;
  counts: { active: number; inactive: number };
  onChange: (value: ActiveFilter) => void;
}) {
  const options: [ActiveFilter, string, number][] = [
    ["activos", "Activos", counts.active],
    ["inactivos", "Inactivos", counts.inactive],
    ["todos", "Todos", counts.active + counts.inactive],
  ];
  return (
    <div className="chips" role="group" aria-label="Filtrar por estado">
      {options.map(([key, label, count]) => (
        <button key={key} type="button" aria-pressed={value === key} onClick={() => onChange(key)}>
          {label} <b>{count}</b>
        </button>
      ))}
    </div>
  );
}

export function SortHeader<K extends string>({
  label,
  sortKey,
  sort,
  onSort,
  className = "",
}: {
  label: string;
  sortKey: K;
  sort: Sort<K>;
  onSort: (sort: Sort<K>) => void;
  className?: string;
}) {
  const active = sort?.key === sortKey;
  return (
    <th
      className={`${className}${active ? " sorted" : ""}`}
      aria-sort={active ? (sort.dir === 1 ? "ascending" : "descending") : "none"}
    >
      <button
        type="button"
        onClick={() => onSort(active ? { key: sortKey, dir: sort.dir === 1 ? -1 : 1 } : { key: sortKey, dir: 1 })}
      >
        {label}
        <span className="ar" aria-hidden="true">
          {active && sort.dir === -1 ? "▼" : "▲"}
        </span>
      </button>
    </th>
  );
}

export interface MenuItem {
  label: string;
  icon: keyof typeof MENU_ICONS;
  danger?: boolean;
  onSelect: () => void;
}

const MENU_ICONS = {
  copy: (
    <>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M5 15V5h10" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="3.2" />
      <path d="M5 20c1.2-3.5 4-5 7-5s5.8 1.5 7 5" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  list: <path d="M4 6h16M4 12h16M4 18h10" />,
  cal: (
    <>
      <rect x="3" y="5" width="18" height="16" rx="2" />
      <path d="M3 10h18M8 3v4M16 3v4" />
    </>
  ),
  pen: <path d="M4 20h4l11-11-4-4L4 16v4z" />,
  wa: <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9 9 0 0 1-2.8-.4L4 21l1.5-4.2A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z" />,
  off: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12h8" />
    </>
  ),
};

// Mockup .menu: "more actions" kebab. Items separated by `null`.
export function RowMenu({ items }: { items: (MenuItem | null)[] }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent ? e.key === "Escape" : !ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  return (
    <div className="menuwrap" ref={ref}>
      <button
        type="button"
        className="icon"
        aria-label="Más acciones"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={(e) => {
          e.stopPropagation();
          setOpen((v) => !v);
        }}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" style={{ fill: "currentColor", stroke: "none" }}>
          <circle cx="12" cy="6" r="1.6" />
          <circle cx="12" cy="12" r="1.6" />
          <circle cx="12" cy="18" r="1.6" />
        </svg>
      </button>
      {open && (
        <div className="menu" role="menu">
          {items.map((item, i) =>
            item === null ? (
              <hr key={`sep-${i}`} />
            ) : (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                className={item.danger ? "warn" : ""}
                onClick={(e) => {
                  e.stopPropagation();
                  setOpen(false);
                  item.onSelect();
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  {MENU_ICONS[item.icon]}
                </svg>
                {item.label}
              </button>
            ),
          )}
        </div>
      )}
    </div>
  );
}

export function WarnBar({ icon = "warn", children, action }: { icon?: "warn" | "clock"; children: ReactNode; action?: ReactNode }) {
  return (
    <div className="warnbar">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {icon === "warn" ? (
          <>
            <path d="M12 9v4M12 17h.01" />
            <path d="M10.3 3.9 2.6 17.4A2 2 0 0 0 4.3 20.4h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
          </>
        ) : (
          <>
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v5l3 2" />
          </>
        )}
      </svg>
      <span>{children}</span>
      {action}
    </div>
  );
}

export function TableEmpty({
  icon,
  title,
  text,
  action,
}: {
  icon: "search" | "check" | "plus";
  title: string;
  text: string;
  action?: ReactNode;
}) {
  return (
    <div className="table-empty">
      <div className="sq">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          {icon === "search" ? (
            <>
              <circle cx="11" cy="11" r="7" />
              <path d="M20 20l-4-4" />
            </>
          ) : icon === "check" ? (
            <>
              <circle cx="12" cy="12" r="9" />
              <path d="M8.5 12.5l2.5 2.5 4.5-5" />
            </>
          ) : (
            <path d="M12 5v14M5 12h14" />
          )}
        </svg>
      </div>
      <b>{title}</b>
      <p>{text}</p>
      {action}
    </div>
  );
}

export function Pager({
  page,
  pages,
  from,
  to,
  total,
  onPage,
}: {
  page: number; // 0-based
  pages: number;
  from: number;
  to: number;
  total: number;
  onPage: (page: number) => void;
}) {
  if (pages <= 1) return null;
  return (
    <div className="table-foot">
      <span>
        Mostrando {from} a {to} de {total}
      </span>
      <div className="pg">
        <button type="button" className="secondary sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Anterior
        </button>
        <span>
          {page + 1} / {pages}
        </span>
        <button type="button" className="secondary sm" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>
          Siguiente
        </button>
      </div>
    </div>
  );
}

// There is no way to reactivate from the panel yet, so deactivating is
// never a one-click action.
export function DeactivateDialog({
  question,
  effect,
  onConfirm,
  onClose,
}: {
  question: ReactNode;
  effect: string;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog
      title="Dar de baja"
      width={420}
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              setBusy(true);
              onConfirm().catch((err) => {
                setError(err instanceof ApiError ? err.message : "No se pudo dar de baja.");
                setBusy(false);
              });
            }}
          >
            {busy ? "Dando de baja…" : "Dar de baja"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Volver
          </button>
        </>
      }
    >
      {error && <p className="error-text">{error}</p>}
      <p className="confirm-text">
        {question} {effect}
      </p>
    </Dialog>
  );
}
