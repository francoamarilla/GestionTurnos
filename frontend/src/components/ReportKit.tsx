import { useState, type ReactNode } from "react";
import { PRESETS, presetRange, type Range } from "./reportRange";

// Filter row + stat tiles shared by Ventas and Estadísticas.

export function RangeBar({
  range,
  today,
  compare,
  onChange,
  children,
}: {
  range: Range;
  today: string;
  compare: boolean;
  onChange: (range: Range, compare: boolean) => void;
  children?: ReactNode; // extra filters (e.g. professional)
}) {
  return (
    <div className="rangebar" role="group" aria-label="Período">
      <div className="presets">
        {PRESETS.map((p) => (
          <button
            key={p.key}
            type="button"
            aria-pressed={range.preset === p.key}
            onClick={() => onChange(presetRange(p.key, today), compare)}
          >
            {p.label}
          </button>
        ))}
      </div>
      <div className="dates">
        <input
          type="date"
          aria-label="Desde"
          value={range.from}
          max={range.to}
          onChange={(e) => e.target.value && onChange({ from: e.target.value, to: range.to, preset: "custom" }, compare)}
        />
        <span>a</span>
        <input
          type="date"
          aria-label="Hasta"
          value={range.to}
          min={range.from}
          onChange={(e) => e.target.value && onChange({ from: range.from, to: e.target.value, preset: "custom" }, compare)}
        />
      </div>
      <div className="right">
        {children}
        <label className="cmpsw">
          <span className="sw">
            <input type="checkbox" checked={compare} onChange={(e) => onChange(range, e.target.checked)} />
            <i aria-hidden="true" />
          </span>
          Comparar con período anterior
        </label>
      </div>
    </div>
  );
}

export function Delta({ change, note = "vs período anterior" }: { change: number | null; note?: string }) {
  if (change === null) return <span className="flat">sin dato previo</span>;
  if (Math.abs(change) < 1) {
    return (
      <>
        <span className="flat">igual</span> {note}
      </>
    );
  }
  return (
    <>
      <span className={change > 0 ? "up" : "down"}>
        {change > 0 ? "▲" : "▼"} {Math.abs(change).toFixed(0)}%
      </span>{" "}
      {note}
    </>
  );
}

export function StatTile({
  label,
  value,
  help,
  children,
}: {
  label: string;
  value: ReactNode;
  help?: string;
  children?: ReactNode;
}) {
  const [showHelp, setShowHelp] = useState(false);
  return (
    <div className="panel stat-tile">
      <span className="lab">
        {label}
        {help && (
          <button
            type="button"
            className="q"
            aria-label={`Qué es ${label.toLowerCase()}`}
            aria-expanded={showHelp}
            onClick={() => setShowHelp((v) => !v)}
          >
            ?
          </button>
        )}
      </span>
      <span className="val">{value}</span>
      {showHelp && <span className="help-pop">{help}</span>}
      <span className="cmp">{children ?? " "}</span>
    </div>
  );
}

// Two-part share bar with a legend (identity never by color alone).
export function SplitBar({
  parts,
  label,
}: {
  parts: { label: string; value: number; color: string }[];
  label: string;
}) {
  const total = parts.reduce((sum, p) => sum + p.value, 0);
  return (
    <>
      <div className="stack" role="img" aria-label={`${label}: ${parts.map((p) => `${p.label} ${p.value}`).join(", ")}`}>
        {total === 0 ? (
          <i style={{ width: "100%", background: "var(--slate-soft)" }} />
        ) : (
          parts
            .filter((p) => p.value > 0)
            .map((p) => <i key={p.label} title={`${p.label}: ${p.value}`} style={{ width: `${(p.value / total) * 100}%`, background: p.color }} />)
        )}
      </div>
      <div className="lg">
        {parts.map((p) => (
          <span key={p.label}>
            <em style={{ background: p.color }} />
            {p.label}
          </span>
        ))}
      </div>
    </>
  );
}
