import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import TenantsAdminPage from "../admin/TenantsAdminPage";

interface AppointmentResponse {
  id: string;
  professionalId: string;
  serviceId: string;
  customerId: string | null;
  startTime: string; // OffsetDateTime, already in the tenant's own timezone
  price: number;
  status: "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";
}

interface FailedReminder {
  appointmentId: string;
  type: string;
  attempts: number;
}

interface NamedEntity {
  id: string;
  name: string;
}

interface Page<T> {
  content: T[];
}

interface DashboardData {
  today: AppointmentResponse[];
  upcoming: AppointmentResponse[];
  recentCancellations: AppointmentResponse[];
  failedReminders: FailedReminder[];
  todaySales: number;
  weekSales: number;
  professionals: Map<string, string>;
  services: Map<string, string>;
}

function toIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function addDays(date: Date, days: number): Date {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + days);
  return copy;
}

function formatTime(iso: string): string {
  return iso.slice(11, 16);
}

function formatMoney(amount: number): string {
  return `$${amount.toFixed(2)}`;
}

const REMINDER_TYPE_LABEL: Record<string, string> = {
  CONFIRMATION: "Confirmación",
  REMINDER_24H: "Recordatorio 24h",
  REMINDER_2H: "Recordatorio 2h",
  CANCELLATION: "Cancelación",
};

async function loadDashboard(): Promise<DashboardData> {
  const today = new Date();
  const todayIso = toIsoDate(today);
  const weekStartIso = toIsoDate(addDays(today, -((today.getDay() + 6) % 7)));
  const weekAgoIso = toIsoDate(addDays(today, -7));
  const tomorrowIso = toIsoDate(addDays(today, 1));
  const nextWeekIso = toIsoDate(addDays(today, 7));

  const [
    todayAppointments,
    upcoming,
    recentCancellations,
    failedReminders,
    todayStats,
    weekStats,
    professionalsPage,
    services,
  ] = await Promise.all([
    api.get<AppointmentResponse[]>(`/appointments?from=${todayIso}&to=${todayIso}`),
    api.get<AppointmentResponse[]>(`/appointments?from=${tomorrowIso}&to=${nextWeekIso}&status=CONFIRMED`),
    api.get<AppointmentResponse[]>(`/appointments?from=${weekAgoIso}&to=${todayIso}&status=CANCELLED`),
    api.get<FailedReminder[]>("/reminders/failed"),
    api.get<{ totalSales: number }>(`/statistics/summary?from=${todayIso}&to=${todayIso}`),
    api.get<{ totalSales: number }>(`/statistics/summary?from=${weekStartIso}&to=${todayIso}`),
    // ponytail: one page of up to 200 professionals/customers is enough to
    // label appointments for any tenant this MVP realistically has; a
    // shop with more would need a real by-ids lookup instead of this map.
    api.get<Page<NamedEntity>>("/professionals?size=200"),
    api.get<NamedEntity[]>("/services"),
  ]);

  return {
    today: todayAppointments.sort((a, b) => a.startTime.localeCompare(b.startTime)),
    upcoming: upcoming.sort((a, b) => a.startTime.localeCompare(b.startTime)).slice(0, 5),
    recentCancellations: recentCancellations.sort((a, b) => b.startTime.localeCompare(a.startTime)).slice(0, 5),
    failedReminders,
    todaySales: todayStats.totalSales,
    weekSales: weekStats.totalSales,
    professionals: new Map(professionalsPage.content.map((p) => [p.id, p.name])),
    services: new Map(services.map((s) => [s.id, s.name])),
  };
}

function AppointmentRow({ appt, professionals, services }: { appt: AppointmentResponse; professionals: Map<string, string>; services: Map<string, string> }) {
  return (
    <li>
      <strong>{formatTime(appt.startTime)}</strong> — {services.get(appt.serviceId) ?? "Servicio"} con{" "}
      {professionals.get(appt.professionalId) ?? "Profesional"}
    </li>
  );
}

export default function HomeDashboard() {
  const { session } = useAuth();
  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (session?.role !== "ADMIN") {
      return;
    }
    loadDashboard()
      .then(setData)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar el inicio."));
  }, [session?.role]);

  if (session?.role !== "ADMIN") {
    return <TenantsAdminPage />;
  }

  if (error) {
    return (
      <div>
        <h1>Inicio</h1>
        <p className="error-text">{error}</p>
      </div>
    );
  }

  if (!data) {
    return (
      <div>
        <h1>Inicio</h1>
        <p>Cargando...</p>
      </div>
    );
  }

  return (
    <div>
      <h1>Inicio</h1>
      <p>
        Hola, {session.establishmentName} ({session.role}).
      </p>

      {data.failedReminders.length > 0 && (
        <div className="alert-banner">
          {data.failedReminders.length} recordatorio(s) fallaron al enviarse:{" "}
          {data.failedReminders.map((r) => REMINDER_TYPE_LABEL[r.type] ?? r.type).join(", ")}
        </div>
      )}

      <div className="dashboard-grid">
        <section className="card">
          <h2>Turnos de hoy</h2>
          {data.today.length === 0 ? (
            <p>Sin turnos hoy.</p>
          ) : (
            <ul>
              {data.today.map((appt) => (
                <AppointmentRow key={appt.id} appt={appt} professionals={data.professionals} services={data.services} />
              ))}
            </ul>
          )}
        </section>

        <section className="card">
          <h2>Próximos turnos</h2>
          {data.upcoming.length === 0 ? (
            <p>No hay turnos confirmados próximamente.</p>
          ) : (
            <ul>
              {data.upcoming.map((appt) => (
                <li key={appt.id}>
                  {appt.startTime.slice(0, 10)} <strong>{formatTime(appt.startTime)}</strong> —{" "}
                  {data.services.get(appt.serviceId) ?? "Servicio"} con{" "}
                  {data.professionals.get(appt.professionalId) ?? "Profesional"}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="card">
          <h2>Cancelaciones recientes</h2>
          {data.recentCancellations.length === 0 ? (
            <p>Sin cancelaciones en los últimos 7 días.</p>
          ) : (
            <ul>
              {data.recentCancellations.map((appt) => (
                <li key={appt.id}>
                  {appt.startTime.slice(0, 10)} <strong>{formatTime(appt.startTime)}</strong> —{" "}
                  {data.services.get(appt.serviceId) ?? "Servicio"} con{" "}
                  {data.professionals.get(appt.professionalId) ?? "Profesional"}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="card">
          <h2>Ventas</h2>
          <p>Hoy: {formatMoney(data.todaySales)}</p>
          <p>Esta semana: {formatMoney(data.weekSales)}</p>
        </section>
      </div>
    </div>
  );
}
