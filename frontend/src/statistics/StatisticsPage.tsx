import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { nowInZone, timeOf, type Appointment } from "../agenda/model";
import { Delta, RangeBar, SplitBar, StatTile } from "../components/ReportKit";
import {
  daysInRange,
  percentChange,
  previousRange,
  rangeLabel,
  rememberedRange,
  rememberRange,
  shortDate,
  type Range,
} from "../components/reportRange";
import { formatMoney } from "../format";

interface Summary {
  totalSales: number;
  salesByProfessional: { professionalId: string; total: number }[];
  newCustomers: number;
  returningCustomers: number;
  cancellationRate: number;
  noShowRate: number;
  occupancyRate: number;
  occupancyByProfessional: { professionalId: string; rate: number }[];
}

interface Period {
  summary: Summary;
  appointments: Appointment[]; // every status, direct sales included
}

function loadPeriod(range: { from: string; to: string }): Promise<Period> {
  return Promise.all([
    api.get<Summary>(`/statistics/summary?from=${range.from}&to=${range.to}`),
    api.get<Appointment[]>(`/appointments?from=${range.from}&to=${range.to}`),
  ]).then(([summary, appointments]) => ({ summary, appointments }));
}

const completedOf = (p: Period) => p.appointments.filter((a) => a.status === "COMPLETED");
const count = (p: Period, status: Appointment["status"]) => p.appointments.filter((a) => a.status === status).length;
const ticket = (p: Period) => {
  const n = completedOf(p).length;
  return n ? Number(p.summary.totalSales) / n : 0;
};

// Chart tooltip: one per chart, driven by hover and keyboard focus on the
// marks; every value is also in the table view below the chart.
function useTip() {
  const [tip, setTip] = useState<{ x: number; text: string } | null>(null);
  const bind = (index: number, total: number, text: string) => ({
    tabIndex: 0,
    "aria-label": text,
    onMouseEnter: () => setTip({ x: ((index + 0.5) / total) * 100, text }),
    onFocus: () => setTip({ x: ((index + 0.5) / total) * 100, text }),
    onMouseLeave: () => setTip(null),
    onBlur: () => setTip(null),
  });
  const node = tip && (
    <div className="chart-tip" style={{ left: `clamp(60px, ${tip.x}%, calc(100% - 60px))` }} role="status">
      {tip.text}
    </div>
  );
  return { bind, node };
}

function DayChart({ days }: { days: { day: string; total: number }[] }) {
  const { bind, node } = useTip();
  const max = Math.max(0, ...days.map((d) => d.total));
  const every = days.length <= 10 ? 1 : days.length <= 16 ? 2 : days.length <= 45 ? 5 : 10;
  return (
    <>
      <div className="chart-wrap">
        {node}
        <div className="daychart">
          {days.map((d, i) => (
            <div
              key={d.day}
              className={`dcol${d.total ? "" : " zero"}`}
              {...bind(i, days.length, `${shortDate(d.day)}: ${d.total ? formatMoney(d.total) : "sin ventas"}`)}
            >
              <div className="b" style={{ height: `${max ? (d.total / max) * 100 : 0}%` }} />
            </div>
          ))}
        </div>
      </div>
      <div className="daylabs" aria-hidden="true">
        {days.map((d, i) => (
          <span key={d.day}>{i % every === 0 || i === days.length - 1 ? Number(d.day.slice(8)) : ""}</span>
        ))}
      </div>
      <div className="chart-foot">
        <span>{shortDate(days[0].day)}</span>
        <span>máx. {formatMoney(max)}</span>
        <span>{shortDate(days[days.length - 1].day)}</span>
      </div>
      <details className="table-view">
        <summary>Ver como tabla</summary>
        <table>
          <tbody>
            {days.map((d) => (
              <tr key={d.day}>
                <td>{shortDate(d.day)}</td>
                <td className="num">{formatMoney(d.total)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </>
  );
}

function HoursChart({ hours }: { hours: { hour: number; count: number }[] }) {
  const { bind, node } = useTip();
  const max = Math.max(0, ...hours.map((h) => h.count));
  return (
    <>
      <div className="chart-wrap">
        {node}
        <div className="hours">
          {hours.map((h, i) => (
            <div
              key={h.hour}
              className={`h${h.count === max && max > 0 ? " peak" : ""}`}
              {...bind(i, hours.length, `${h.hour}:00 · ${h.count} ${h.count === 1 ? "turno" : "turnos"}`)}
            >
              <div className="b" style={{ height: `${max ? (h.count / max) * 100 : 0}%` }} />
            </div>
          ))}
        </div>
      </div>
      <div className="hourlabs" aria-hidden="true">
        {hours.map((h) => (
          <span key={h.hour}>{h.hour}</span>
        ))}
      </div>
    </>
  );
}

function Bars({ rows }: { rows: { key: string; label: string; value: number; detail: string }[] }) {
  const max = Math.max(0, ...rows.map((r) => r.value));
  return (
    <div className="bars">
      {rows.map((r) => (
        <div key={r.key} className="brow">
          <span className="n" title={r.label}>
            {r.label}
          </span>
          <span className="track" aria-hidden="true">
            <i style={{ width: `${max ? (r.value / max) * 100 : 0}%` }} />
          </span>
          <span className="v">
            {formatMoney(r.value)}
            <small>{r.detail}</small>
          </span>
        </div>
      ))}
    </div>
  );
}

function RateCard({
  title,
  rate,
  part,
  whole,
  tone,
  note,
}: {
  title: string;
  rate: number;
  part: number;
  whole: number;
  tone: "slate" | "amber";
  note: string;
}) {
  const pct = rate * 100;
  return (
    <div className="panel">
      <div className="panel-head">
        <h2>{title}</h2>
      </div>
      <div className="rate">
        <div className="top">
          <span className="big">{pct.toFixed(1)}%</span>
          <span className="note">
            {part} de {whole} turnos
          </span>
        </div>
        <span
          className={`meter ${tone}`}
          role="meter"
          aria-label={title}
          aria-valuenow={Math.round(pct)}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <i style={{ width: `${Math.min(100, pct)}%` }} />
        </span>
        <span className="note">{note}</span>
      </div>
    </div>
  );
}

export default function StatisticsPage() {
  const [meta, setMeta] = useState<{
    timezone: string;
    professionals: Map<string, string>;
    services: Map<string, string>;
  } | null>(null);
  const [range, setRange] = useState<Range | null>(null);
  const [compare, setCompare] = useState(true);
  const [data, setData] = useState<{ key: string; current: Period; previous: Period | null } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api.get<{ timezone: string }>("/establishment"),
      api.get<{ content: { id: string; name: string }[] }>("/professionals?size=100"),
      api.get<{ id: string; name: string }[]>("/services"),
    ])
      .then(([establishment, professionals, services]) => {
        const remembered = rememberedRange(nowInZone(establishment.timezone).date);
        setMeta({
          timezone: establishment.timezone,
          professionals: new Map(professionals.content.map((p) => [p.id, p.name])),
          services: new Map(services.map((s) => [s.id, s.name])),
        });
        setRange(remembered.range);
        setCompare(remembered.compare);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la pantalla."));
  }, []);

  const key = range ? `${range.from}|${range.to}|${compare}` : "";
  useEffect(() => {
    if (!range) return;
    let cancelled = false;
    Promise.all([loadPeriod(range), compare ? loadPeriod(previousRange(range)) : null])
      .then(([current, previous]) => {
        if (cancelled) return;
        setError(null);
        setData({ key, current, previous });
      })
      .catch((err) => !cancelled && setError(err instanceof ApiError ? err.message : "No se pudieron cargar las estadísticas."));
    return () => {
      cancelled = true;
    };
    // key covers range and compare.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  function changeRange(next: Range, nextCompare: boolean) {
    setRange(next);
    setCompare(nextCompare);
    rememberRange(next, nextCompare);
  }

  const header = (
    <div className="page-head">
      <div>
        <h1>Estadísticas</h1>
        {range && <p>Cómo viene el negocio · {rangeLabel(range)}</p>}
      </div>
      {data && (
        <div className="acts">
          <button type="button" className="secondary" onClick={() => window.print()}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 9V3h12v6M6 18H4v-7h16v7h-2M8 14h8v7H8z" />
            </svg>
            Imprimir / PDF
          </button>
        </div>
      )}
    </div>
  );

  if (!meta || !range || !data) {
    return (
      <div className="list-page report-page">
        {header}
        {meta && range && (
          <RangeBar range={range} today={nowInZone(meta.timezone).date} compare={compare} onChange={changeRange} />
        )}
        {error ? <p className="error-text">{error}</p> : <p className="muted">Cargando…</p>}
      </div>
    );
  }

  const { current, previous } = data;
  const s = current.summary;
  const completed = completedOf(current);
  const nameOfPro = (id: string) => meta.professionals.get(id) ?? "Profesional";

  // Day by day, from the same completed appointments the summary sums.
  const perDay = new Map<string, number>();
  for (const a of completed) perDay.set(a.startTime.slice(0, 10), (perDay.get(a.startTime.slice(0, 10)) ?? 0) + Number(a.price));
  const days = daysInRange(range).map((day) => ({ day, total: perDay.get(day) ?? 0 }));

  const byPro = [...new Set([...s.salesByProfessional.map((x) => x.professionalId), ...s.occupancyByProfessional.filter((o) => o.rate > 0).map((o) => o.professionalId)])]
    .map((id) => {
      const n = completed.filter((a) => a.professionalId === id).length;
      const occupancy = s.occupancyByProfessional.find((o) => o.professionalId === id)?.rate ?? 0;
      return {
        key: id,
        label: nameOfPro(id),
        value: Number(s.salesByProfessional.find((x) => x.professionalId === id)?.total ?? 0),
        detail: `${n} ${n === 1 ? "turno" : "turnos"} · ${Math.round(occupancy * 100)}% ocup.`,
      };
    })
    .sort((a, b) => b.value - a.value);

  const totalSales = Number(s.totalSales);
  const bySrv = [...new Set(completed.map((a) => a.serviceId))]
    .map((id) => {
      const list = completed.filter((a) => a.serviceId === id);
      const value = list.reduce((sum, a) => sum + Number(a.price), 0);
      return {
        key: id ?? "otro",
        // null = sales off the catalog, one "Otro" row (ENDPOINTS-PENDIENTES #5).
        label: id ? (meta.services.get(id) ?? "Servicio") : "Otro",
        value,
        detail: `${list.length} ${list.length === 1 ? "vez" : "veces"} · ${totalSales ? Math.round((value / totalSales) * 100) : 0}% del total`,
      };
    })
    .sort((a, b) => b.value - a.value);

  // Demand by start hour: everything booked (not cancelled).
  const booked = current.appointments.filter((a) => a.status !== "CANCELLED");
  const startHours = booked.map((a) => Number(timeOf(a.startTime).slice(0, 2)));
  const firstHour = startHours.length ? Math.min(...startHours) : 9;
  const lastHour = startHours.length ? Math.max(...startHours) : 19;
  const hours = Array.from({ length: lastHour - firstHour + 1 }, (_, i) => ({
    hour: firstHour + i,
    count: startHours.filter((h) => h === firstHour + i).length,
  }));
  const peakCount = Math.max(0, ...hours.map((h) => h.count));
  const peaks = hours.filter((h) => h.count === peakCount).map((h) => `${h.hour}:00`);
  const lowCount = Math.min(...hours.map((h) => h.count));
  const lows = hours.filter((h) => h.count === lowCount).map((h) => `${h.hour}:00`);

  const clients = s.newCustomers + s.returningCustomers;
  const cancelled = count(current, "CANCELLED");
  const noShows = count(current, "NO_SHOW");

  return (
    <div className="list-page report-page">
      {header}
      <RangeBar range={range} today={nowInZone(meta.timezone).date} compare={compare} onChange={changeRange} />
      {error && <p className="error-text">{error}</p>}

      <div className={`report-body${data.key === key ? "" : " stale"}`} aria-busy={data.key !== key}>
        <div className="kpis4">
          <StatTile label="Facturación" value={formatMoney(totalSales)}>
            {compare && previous && <Delta change={percentChange(totalSales, Number(previous.summary.totalSales))} />}
          </StatTile>
          <StatTile label="Turnos hechos" value={completed.length}>
            {compare && previous && <Delta change={percentChange(completed.length, completedOf(previous).length)} />}
          </StatTile>
          <StatTile label="Ticket promedio" value={formatMoney(Math.round(ticket(current)))}>
            {compare && previous && <Delta change={percentChange(ticket(current), ticket(previous))} />}
          </StatTile>
          <StatTile
            label="Ocupación"
            value={`${Math.round(s.occupancyRate * 100)}%`}
            help="Minutos reservados (sin contar cancelados) dividido los minutos disponibles según el horario de cada profesional, descontando feriados."
          >
            {compare && previous && (
              <Delta change={percentChange(s.occupancyRate, previous.summary.occupancyRate)} />
            )}
          </StatTile>
        </div>

        <div className="panel">
          <div className="panel-head">
            <div>
              <h2>Facturación día por día</h2>
              <p>Pasá el mouse o el foco por una barra para ver el detalle</p>
            </div>
          </div>
          <div className="panel-body">
            <DayChart days={days} />
          </div>
        </div>

        <div className="grid2">
          <div className="panel">
            <div className="panel-head">
              <div>
                <h2>Quién facturó más</h2>
                <p>Con su ocupación en el período</p>
              </div>
            </div>
            <div className="panel-body">
              {byPro.length ? <Bars rows={byPro} /> : <p className="muted">Sin actividad en el período.</p>}
            </div>
          </div>
          <div className="panel">
            <div className="panel-head">
              <div>
                <h2>Qué se vende</h2>
                <p>Por plata, no solo por cantidad</p>
              </div>
            </div>
            <div className="panel-body">
              {bySrv.length ? <Bars rows={bySrv} /> : <p className="muted">Sin ventas en el período.</p>}
            </div>
          </div>
        </div>

        <div className="grid2">
          <div className="panel">
            <div className="panel-head">
              <div>
                <h2>Las horas que más te piden</h2>
                <p>Sirve para saber cuándo te conviene tener a todos</p>
              </div>
            </div>
            <div className="panel-body">
              {booked.length === 0 ? (
                <p className="muted">Sin turnos en el período.</p>
              ) : (
                <>
                  <HoursChart hours={hours} />
                  <p className="chart-note">
                    El pico está a las <b>{peaks.join(" y ")}</b>
                    {hours.length > 1 && lowCount < peakCount && (
                      <>
                        ; {lows.length === 1 ? "la hora más floja es" : "las más flojas son"} {lows.slice(0, 3).join(", ")}
                      </>
                    )}
                    .
                  </p>
                </>
              )}
            </div>
          </div>
          <div className="panel">
            <div className="panel-head">
              <div>
                <h2>Clientes del período</h2>
                <p>
                  {clients} {clients === 1 ? "persona distinta completó" : "personas distintas completaron"} un turno
                </p>
              </div>
            </div>
            <div className="panel-body">
              <SplitBar
                label="Clientes del período"
                parts={[
                  { label: `${s.returningCustomers} ya venían antes`, value: s.returningCustomers, color: "var(--pine)" },
                  { label: `${s.newCustomers} nuevos`, value: s.newCustomers, color: "var(--brick)" },
                ]}
              />
              {clients > 0 && (
                <p className="chart-note">
                  {s.returningCustomers >= s.newCustomers
                    ? "Buena señal: la mayoría son clientes que vuelven."
                    : "Entra gente nueva pero vuelve poca. Vale la pena revisar el seguimiento."}
                </p>
              )}
            </div>
          </div>
        </div>

        <div className="grid2">
          <RateCard
            title="Cancelaciones"
            rate={s.cancellationRate}
            part={cancelled}
            whole={completed.length + cancelled + noShows}
            tone="slate"
            note="Debajo del 10% es normal. Arriba de 20% conviene pedir seña o confirmar por WhatsApp el día antes."
          />
          <RateCard
            title="No asistieron"
            rate={s.noShowRate}
            part={noShows}
            whole={completed.length + noShows}
            tone="amber"
            note="Cada no-show es un hueco que no se puede revender. El recordatorio automático suele bajarlo."
          />
        </div>
      </div>
    </div>
  );
}
