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
    Promise.all([
      api.get<Page<NamedEntity>>("/professionals?size=200"),
      api.get<NamedEntity[]>("/services"),
    ])
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
        <div className="dashboard-grid">
          <section className="card">
            <h2>Ventas</h2>
            <p>
              <strong>{formatMoney(summary.totalSales)}</strong>
            </p>
            {summary.salesByProfessional.length === 0 ? (
              <p>Sin ventas en el período.</p>
            ) : (
              <ul>
                {summary.salesByProfessional.map((s) => (
                  <li key={s.professionalId}>
                    {professionals.get(s.professionalId) ?? "Profesional"}: {formatMoney(s.total)}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card">
            <h2>Servicios más realizados</h2>
            {summary.topServices.length === 0 ? (
              <p>Sin turnos completados en el período.</p>
            ) : (
              <ul>
                {summary.topServices.map((s) => (
                  <li key={s.serviceId}>
                    {services.get(s.serviceId) ?? "Servicio"}: {s.count}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card">
            <h2>Clientes</h2>
            <p>Nuevos: {summary.newCustomers}</p>
            <p>Recurrentes: {summary.returningCustomers}</p>
          </section>

          <section className="card">
            <h2>Cancelaciones y ausencias</h2>
            <p>Tasa de cancelación: {formatPercent(summary.cancellationRate)}</p>
            <p>Tasa de no-show: {formatPercent(summary.noShowRate)}</p>
          </section>

          <section className="card">
            <h2>Ocupación de agenda</h2>
            <p>
              <strong>{formatPercent(summary.occupancyRate)}</strong>
            </p>
            {summary.occupancyByProfessional.length === 0 ? (
              <p>Sin profesionales.</p>
            ) : (
              <ul>
                {summary.occupancyByProfessional.map((o) => (
                  <li key={o.professionalId}>
                    {professionals.get(o.professionalId) ?? "Profesional"}: {formatPercent(o.rate)}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
