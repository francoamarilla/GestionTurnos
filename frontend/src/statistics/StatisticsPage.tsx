import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { formatMoney } from "../format";

interface ProfessionalAmount {
  professionalId: string;
  total: number;
}

interface ServiceCount {
  serviceId: string;
  count: number;
}

interface ProfessionalOccupancy {
  professionalId: string;
  rate: number;
}

interface StatisticsSummaryResponse {
  totalSales: number;
  salesByProfessional: ProfessionalAmount[];
  topServices: ServiceCount[];
  newCustomers: number;
  returningCustomers: number;
  cancellationRate: number;
  noShowRate: number;
  occupancyRate: number;
  occupancyByProfessional: ProfessionalOccupancy[];
}

interface NamedEntity {
  id: string;
  name: string;
}

interface Page<T> {
  content: T[];
}

function toIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function firstOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

// Magnitude comparison across a named set (dataviz skill: "compare
// magnitude" -> bar, color job = sequential/one hue, never categorical -
// the *amount* is the subject here, not each professional's identity).
function BarRow({ label, value, max, displayValue }: { label: string; value: number; max: number; displayValue: string }) {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0;
  return (
    <div className="bar-row">
      <span className="bar-row-label" title={label}>
        {label}
      </span>
      <span className="bar-track">
        <span className="bar-fill" style={{ width: `${pct}%` }} />
      </span>
      <span className="bar-row-value">{displayValue}</span>
    </div>
  );
}

// A single ratio against a limit -> meter, not a bar chart (dataviz
// skill). Fill + track are two steps of the SAME hue, so severity reads
// from the bar alone; tone picks which of this app's existing semantic
// ramps that hue is (error/warning/neutral - the same vocabulary the
// status badges already use elsewhere).
function Meter({
  tone,
  label,
  rate,
}: {
  tone: "error" | "warning" | "neutral";
  label: string;
  rate: number;
}) {
  const pct = Math.max(0, Math.min(100, rate * 100));
  return (
    <section className={`card meter-${tone}`}>
      <h2>{label}</h2>
      <p className="meter-value">{formatPercent(rate)}</p>
      <span className="meter-track">
        <span className="meter-fill" style={{ width: `${pct}%` }} />
      </span>
    </section>
  );
}

export default function StatisticsPage() {
  const today = toIsoDate(new Date());
  const [from, setFrom] = useState(toIsoDate(firstOfMonth(new Date())));
  const [to, setTo] = useState(today);
  const [summary, setSummary] = useState<StatisticsSummaryResponse | null>(null);
  const [professionals, setProfessionals] = useState<Map<string, string>>(new Map());
  const [services, setServices] = useState<Map<string, string>>(new Map());
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // ponytail: same size=200 lookup ceiling used elsewhere in this app.
    Promise.all([api.get<Page<NamedEntity>>("/professionals?size=200"), api.get<NamedEntity[]>("/services")])
      .then(([professionalsPage, serviceList]) => {
        setError(null);
        setProfessionals(new Map(professionalsPage.content.map((p) => [p.id, p.name])));
        setServices(new Map(serviceList.map((s) => [s.id, s.name])));
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los datos."));
  }, []);

  useEffect(() => {
    api
      .get<StatisticsSummaryResponse>(`/statistics/summary?from=${from}&to=${to}`)
      .then((result) => {
        setError(null);
        setSummary(result);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar las estadísticas."));
  }, [from, to]);

  return (
    <div>
      <h1>Estadísticas</h1>
      {error && <p className="error-text">{error}</p>}

      <div className="form-actions">
        <label className="field">
          <span>Desde</span>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="field">
          <span>Hasta</span>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </div>

      {!summary ? (
        <p>Cargando...</p>
      ) : (
        <>
          <div className="dashboard-grid">
            <section className="card stats-hero">
              <h2>Ventas totales</h2>
              <p className="hero-figure">{formatMoney(summary.totalSales)}</p>

              {summary.salesByProfessional.length === 0 ? (
                <p>Sin ventas en el período.</p>
              ) : (
                (() => {
                  const rows = [...summary.salesByProfessional].sort((a, b) => b.total - a.total);
                  const max = rows[0]?.total ?? 0;
                  return rows.map((s) => (
                    <BarRow
                      key={s.professionalId}
                      label={professionals.get(s.professionalId) ?? "Profesional"}
                      value={s.total}
                      max={max}
                      displayValue={formatMoney(s.total)}
                    />
                  ));
                })()
              )}
            </section>

            <section className="card">
              <h2>Clientes</h2>
              <div className="kpi-row">
                <div>
                  <div className="kpi-tile-value">{summary.newCustomers}</div>
                  <div className="kpi-tile-label">Nuevos</div>
                </div>
                <div>
                  <div className="kpi-tile-value">{summary.returningCustomers}</div>
                  <div className="kpi-tile-label">Recurrentes</div>
                </div>
              </div>
            </section>

            <section className="card">
              <h2>Servicios más realizados</h2>
              {summary.topServices.length === 0 ? (
                <p>Sin turnos completados en el período.</p>
              ) : (
                (() => {
                  const rows = [...summary.topServices].sort((a, b) => b.count - a.count);
                  const max = rows[0]?.count ?? 0;
                  return rows.map((s) => (
                    <BarRow
                      key={s.serviceId}
                      label={services.get(s.serviceId) ?? "Servicio"}
                      value={s.count}
                      max={max}
                      displayValue={String(s.count)}
                    />
                  ));
                })()
              )}
            </section>
          </div>

          <div className="meter-grid">
            <Meter tone="error" label="Tasa de cancelación" rate={summary.cancellationRate} />
            <Meter tone="warning" label="Tasa de no-show" rate={summary.noShowRate} />
            <section className="card meter-neutral">
              <h2>Ocupación de agenda</h2>
              <p className="meter-value">{formatPercent(summary.occupancyRate)}</p>
              <span className="meter-track">
                <span className="meter-fill" style={{ width: `${Math.max(0, Math.min(100, summary.occupancyRate * 100))}%` }} />
              </span>
              {summary.occupancyByProfessional.length > 0 && (
                <div style={{ marginTop: 16 }}>
                  {[...summary.occupancyByProfessional]
                    .sort((a, b) => b.rate - a.rate)
                    .map((o) => (
                      <BarRow
                        key={o.professionalId}
                        label={professionals.get(o.professionalId) ?? "Profesional"}
                        value={o.rate}
                        max={1}
                        displayValue={formatPercent(o.rate)}
                      />
                    ))}
                </div>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  );
}
