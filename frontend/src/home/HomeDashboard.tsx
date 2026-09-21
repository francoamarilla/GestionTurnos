import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import { AppointmentDetailDialog } from "../agenda/AppointmentDialogs";
import {
  addDays,
  customerLabel,
  dayWindow,
  loadAppointments,
  loadCatalog,
  longDate,
  nowInZone,
  PROFESSIONAL_COLORS,
  timeOf,
  toMinutes,
  toTime,
  type Appointment,
  type Catalog,
} from "../agenda/model";
import { useAuth } from "../auth/AuthContext";
import TenantsAdminPage from "../admin/TenantsAdminPage";
import { AppointmentStatusBadge } from "../components/Badge";
import { useToast } from "../components/Toast";
import { formatMoney } from "../format";

interface Summary {
  totalSales: number;
  salesByProfessional: { professionalId: string; total: number }[];
  occupancyRate: number;
  occupancyByProfessional: { professionalId: string; rate: number }[];
}

interface HomeData {
  catalog: Catalog;
  now: { date: string; minutes: number };
  today: Appointment[];
  summaryToday: Summary;
  summaryYesterday: Summary;
  summaryLastWeek: Summary;
  noShows: Appointment[]; // last 14 days and the whole current month
  recentCancellations: Appointment[]; // yesterday + today
  failedReminders: { appointmentId: string; type: string }[];
  whatsAppConnected: boolean;
  gaps: { professionalId: string; slot: string }[];
}

const REMINDER_TYPE_LABEL: Record<string, string> = {
  CONFIRMATION: "confirmación",
  REMINDER_24H: "recordatorio 24 h",
  REMINDER_2H: "recordatorio 2 h",
  CANCELLATION: "cancelación",
};

const WEEKDAY_NAMES = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];
const MAX_GAPS = 6;

function withoutSales(list: Appointment[]) {
  return list.filter((a) => a.origin !== "DIRECT_SALE");
}

function endMinutes(a: Appointment) {
  return toMinutes(timeOf(a.startTime)) + a.durationMinutes;
}

// Free starts left today per professional, using the shortest active
// service each one offers as the probe.
// ponytail: one availability call per professional - fine for a handful of
// professionals; a shop with dozens would want a batch endpoint.
async function loadGaps(catalog: Catalog, now: { date: string; minutes: number }) {
  const lists = await Promise.all(
    catalog.professionals.map(async (p) => {
      if (!dayWindow(catalog.schedules.get(p.id), now.date)) return [];
      const probe = catalog.services
        .filter((s) => s.active && catalog.assignments.get(p.id)?.includes(s.id))
        .sort((a, b) => a.durationMinutes - b.durationMinutes)[0];
      if (!probe) return [];
      const slots = await api
        .get<string[]>(`/availability?professionalId=${p.id}&serviceId=${probe.id}&date=${now.date}`)
        .catch(() => [] as string[]);
      return slots.filter((s) => toMinutes(timeOf(s)) > now.minutes).map((slot) => ({ professionalId: p.id, slot }));
    }),
  );
  return lists.flat().sort((a, b) => a.slot.localeCompare(b.slot));
}

async function loadHome(): Promise<HomeData> {
  const catalog = await loadCatalog();
  const now = nowInZone(catalog.timezone);
  const today = now.date;
  const monthStart = `${today.slice(0, 8)}01`;
  const twoWeeksAgo = addDays(today, -13);
  const noShowFrom = monthStart < twoWeeksAgo ? monthStart : twoWeeksAgo;
  const summary = (day: string) => api.get<Summary>(`/statistics/summary?from=${day}&to=${day}`);

  const [todayList, summaryToday, summaryYesterday, summaryLastWeek, noShows, cancellations, failedReminders, whatsApp, gaps] =
    await Promise.all([
      loadAppointments(today),
      summary(today),
      summary(addDays(today, -1)),
      summary(addDays(today, -7)),
      api.get<Appointment[]>(`/appointments?from=${noShowFrom}&to=${today}&status=NO_SHOW`),
      api.get<Appointment[]>(`/appointments?from=${addDays(today, -1)}&to=${today}&status=CANCELLED`),
      api.get<{ appointmentId: string; type: string }[]>("/reminders/failed"),
      api
        .get("/establishment/whatsapp-integration")
        .then(() => true)
        .catch((err) => {
          if (err instanceof ApiError && err.status === 404) return false;
          throw err;
        }),
      loadGaps(catalog, now),
    ]);

  return {
    catalog,
    now,
    today: todayList,
    summaryToday,
    summaryYesterday,
    summaryLastWeek,
    noShows: withoutSales(noShows),
    recentCancellations: withoutSales(cancellations).sort((a, b) => b.startTime.localeCompare(a.startTime)),
    failedReminders,
    whatsAppConnected: whatsApp,
    gaps,
  };
}

function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`;
}

function inMinutes(minutes: number) {
  if (minutes < 60) return `en ${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `en ${h} h ${m} min` : `en ${h} h`;
}

function Delta({ value, suffix, goodWhenUp = true }: { value: number; suffix: string; goodWhenUp?: boolean }) {
  if (value === 0) return <span>= igual</span>;
  const up = value > 0;
  return (
    <span className={up === goodWhenUp ? "up" : "down"}>
      {up ? "▲" : "▼"} {Math.abs(value)}
      {suffix}
    </span>
  );
}

function Kpi({ label, value, children, onClick }: { label: string; value: string; children: ReactNode; onClick: () => void }) {
  return (
    <button type="button" className="panel kpi" onClick={onClick}>
      <span className="lab">{label}</span>
      <span className="val">{value}</span>
      <span className="cmp">{children}</span>
    </button>
  );
}

const ICONS = {
  warn: (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 9v4M12 17h.01" />
      <path d="M10.3 3.9 2.6 17.4A2 2 0 0 0 4.3 20.4h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
    </svg>
  ),
  bad: (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  ),
  chat: (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9 9 0 0 1-2.8-.4L4 21l1.5-4.2A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z" />
    </svg>
  ),
  info: (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 8v8M8 12h8" />
      <circle cx="12" cy="12" r="9" />
    </svg>
  ),
};

interface TodoItem {
  key: string;
  tone: "warn" | "bad" | "info";
  icon: ReactNode;
  title: string;
  detail: string;
  action?: { label: string; run: () => void };
}

function AdminHome() {
  const { session } = useAuth();
  const navigate = useNavigate();
  const showToast = useToast();
  const [data, setData] = useState<HomeData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showPast, setShowPast] = useState(false);
  const [detail, setDetail] = useState<Appointment | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(() => {
    loadHome()
      .then((result) => {
        // Clear a previous failure: the render shows `error` ahead of `data`.
        setError(null);
        setData(result);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar el inicio."));
  }, []);

  useEffect(reload, [reload]);

  // Refresh "now" (in-progress, next, past rows) every minute.
  const loaded = data !== null;
  useEffect(() => {
    if (!loaded) return;
    const timer = window.setInterval(
      () => setData((d) => (d ? { ...d, now: nowInZone(d.catalog.timezone) } : d)),
      60_000,
    );
    return () => window.clearInterval(timer);
  }, [loaded]);

  const bookAt = (professionalId?: string, minutes?: number) =>
    navigate("/agenda", { state: { book: { professionalId, minutes } } });

  if (error && !data) {
    return (
      <div className="home">
        <div className="page-head">
          <h1>Inicio</h1>
        </div>
        <p className="error-text">{error}</p>
        <button type="button" onClick={reload}>
          Reintentar
        </button>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="home">
        <div className="page-head">
          <h1>Inicio</h1>
        </div>
        <p className="muted">Cargando…</p>
      </div>
    );
  }

  const { catalog, now, today } = data;
  const serviceName = (a: Appointment) =>
    a.description ?? catalog.services.find((s) => s.id === a.serviceId)?.name ?? "Servicio";
  const professionalName = (id: string) => catalog.professionals.find((p) => p.id === id)?.name ?? "Profesional";
  const clientName = (a: Appointment) =>
    a.customerId ? customerLabel(catalog.customers.find((c) => c.id === a.customerId)) : null;

  // Header line.
  const branchToday = dayWindow(catalog.branchSchedule, now.date);
  const closure = catalog.closures.find((c) => c.date === now.date);
  const openLine = closure
    ? `cerrado hoy${closure.reason ? ` (${closure.reason})` : ""}`
    : !branchToday
      ? "hoy no abre"
      : now.minutes < branchToday.open
        ? `abre a las ${toTime(branchToday.open)}`
        : now.minutes < branchToday.close
          ? `abierto hasta las ${toTime(branchToday.close)}`
          : "ya cerró por hoy";

  // Hero.
  const live = today.filter((a) => a.status === "CONFIRMED" || a.status === "COMPLETED");
  const inProgress = live.find((a) => toMinutes(timeOf(a.startTime)) <= now.minutes && now.minutes < endMinutes(a));
  const next = today.find((a) => a.status === "CONFIRMED" && toMinutes(timeOf(a.startTime)) > now.minutes);

  async function markDone(a: Appointment) {
    setBusy(true);
    try {
      await api.patch(`/appointments/${a.id}/status`, { status: "COMPLETED" });
      showToast(`Turno de ${timeOf(a.startTime)} marcado como completado`);
      reload();
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "No se pudo actualizar el turno.");
    } finally {
      setBusy(false);
    }
  }

  // KPIs.
  const { summaryToday, summaryYesterday, summaryLastWeek } = data;
  const salesDelta =
    summaryYesterday.totalSales > 0
      ? Math.round(((summaryToday.totalSales - summaryYesterday.totalSales) / summaryYesterday.totalSales) * 100)
      : null;
  const count = (status: Appointment["status"]) => today.filter((a) => a.status === status).length;
  const occToday = Math.round(summaryToday.occupancyRate * 100);
  const occDelta = occToday - Math.round(summaryLastWeek.occupancyRate * 100);
  const weekAgo = addDays(now.date, -6);
  const twoWeeksAgo = addDays(now.date, -13);
  const noShowsWeek = data.noShows.filter((a) => a.startTime.slice(0, 10) >= weekAgo).length;
  const noShowsPrevWeek = data.noShows.filter((a) => {
    const d = a.startTime.slice(0, 10);
    return d >= twoWeeksAgo && d < weekAgo;
  }).length;
  const weekdayName = WEEKDAY_NAMES[new Date(`${now.date}T12:00:00`).getDay()];

  // Timeline.
  const past = today.filter((a) => endMinutes(a) <= now.minutes);
  const upcoming = today.filter((a) => endMinutes(a) > now.minutes);
  const row = (a: Appointment) => {
    const isPast = endMinutes(a) <= now.minutes;
    return (
      <li key={a.id} className={`${isPast ? "past" : ""}${a.status === "CANCELLED" || a.status === "VOIDED" ? " cancel" : ""}`}>
        <button type="button" onClick={() => setDetail(a)}>
          <span className="t">{timeOf(a.startTime)}</span>
          <span className="txt">
            <b>{serviceName(a)}</b>
            <span>
              {clientName(a) ?? "Sin cliente"} · {professionalName(a.professionalId)}
            </span>
          </span>
          <AppointmentStatusBadge status={a.status} />
        </button>
      </li>
    );
  };

  // Needs attention.
  const monthStart = `${now.date.slice(0, 8)}01`;
  const todos: TodoItem[] = [];
  for (const a of today.filter((x) => x.status === "NO_SHOW")) {
    const name = clientName(a);
    const timesThisMonth = a.customerId
      ? data.noShows.filter((x) => x.customerId === a.customerId && x.startTime.slice(0, 10) >= monthStart).length
      : 0;
    todos.push({
      key: `noshow-${a.id}`,
      tone: "warn",
      icon: ICONS.warn,
      title: `${name ?? "Un cliente ocasional"} no vino hoy`,
      detail:
        timesThisMonth > 1
          ? `${timesThisMonth} veces este mes. Conviene pedirle seña.`
          : `${serviceName(a)} de las ${timeOf(a.startTime)} con ${professionalName(a.professionalId)}.`,
      action: a.customerId
        ? { label: "Ver ficha", run: () => navigate("/customers", { state: { customerId: a.customerId } }) }
        : undefined,
    });
  }
  if (data.recentCancellations.length > 0) {
    const n = data.recentCancellations.length;
    const pros = [...new Set(data.recentCancellations.map((a) => professionalName(a.professionalId)))];
    todos.push({
      key: "cancellations",
      tone: "bad",
      icon: ICONS.bad,
      title: n === 1 ? "1 turno cancelado entre ayer y hoy" : `${n} turnos cancelados entre ayer y hoy`,
      detail: `${pros.length === 1 ? `De ${pros[0]}` : `De ${pros.join(", ")}`}: ${data.recentCancellations
        .slice(0, 3)
        .map((a) => `${timeOf(a.startTime)}${a.startTime.slice(0, 10) === now.date ? "" : " de ayer"}`)
        .join(", ")}${n > 3 ? "…" : "."}`,
      action: { label: "Ver agenda", run: () => navigate("/agenda") },
    });
  }
  if (data.failedReminders.length > 0) {
    const n = data.failedReminders.length;
    const kinds = [...new Set(data.failedReminders.map((r) => REMINDER_TYPE_LABEL[r.type] ?? r.type))];
    todos.push({
      key: "reminders",
      tone: "bad",
      icon: ICONS.chat,
      title: n === 1 ? "1 mensaje no se pudo enviar" : `${n} mensajes no se pudieron enviar`,
      detail: `Tipo: ${kinds.join(", ")}.`,
    });
  }
  if (!data.whatsAppConnected) {
    todos.push({
      key: "whatsapp",
      tone: "info",
      icon: ICONS.chat,
      title: "WhatsApp sin conectar",
      detail: "Los clientes todavía no pueden reservar solos.",
      action: { label: "Ver", run: () => navigate("/settings") },
    });
  }
  const inactive = catalog.services.filter((s) => !s.active);
  if (inactive.length > 0) {
    todos.push({
      key: "inactive-services",
      tone: "info",
      icon: ICONS.info,
      title:
        inactive.length === 1
          ? `“${inactive[0].name}” está inactivo`
          : `${inactive.length} servicios inactivos`,
      detail:
        inactive.length === 1
          ? "No se puede reservar ni vender ese servicio."
          : `${inactive.map((s) => s.name).join(", ")}. No se pueden reservar ni vender.`,
      // Backlog item 1: with one inactive service the fix is one PATCH, so
      // Inicio resolves it here instead of only pointing at Servicios.
      action:
        inactive.length === 1
          ? {
              label: "Activar",
              run: () => {
                api
                  .patch(`/services/${inactive[0].id}`, { active: true })
                  .then(() => {
                    showToast(`Servicio “${inactive[0].name}” activado`);
                    reload();
                  })
                  .catch((err) =>
                    showToast(err instanceof ApiError ? err.message : "No se pudo activar el servicio."),
                  );
              },
            }
          : { label: "Ver servicios", run: () => navigate("/services") },
    });
  }

  // Per-professional today.
  const working = catalog.professionals.filter((p) => dayWindow(catalog.schedules.get(p.id), now.date));
  const gaps = data.gaps.slice(0, MAX_GAPS);

  return (
    <div className="home">
      <div className="page-head">
        <div>
          <h1>{session?.establishmentName ?? "Inicio"}</h1>
          <p>
            {longDate(now.date)} · {openLine}
          </p>
        </div>
        <div className="acts">
          <button type="button" onClick={() => bookAt()} disabled={catalog.professionals.length === 0}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
            Nuevo turno
          </button>
          <button type="button" className="secondary" onClick={() => navigate("/sales")}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M3 7h18v12H3zM3 11h18" />
            </svg>
            Registrar venta
          </button>
        </div>
      </div>

      {error && <p className="error-text">{error}</p>}

      <section className="hero">
        <div className="panel now-card">
          <div className="now-head">
            <span className="pulse" aria-hidden="true" />
            {inProgress ? "En curso" : "Ahora"} · {toTime(now.minutes)}
          </div>

          {inProgress ? (
            <>
              <div className="inprog">
                <div className="time">
                  {timeOf(inProgress.startTime)}–{toTime(endMinutes(inProgress))}
                </div>
                <div className="who">
                  <b>{serviceName(inProgress)}</b>
                  <span>
                    {clientName(inProgress) ?? "Sin cliente"} · {professionalName(inProgress.professionalId)}
                  </span>
                </div>
                {inProgress.status === "CONFIRMED" ? (
                  <button type="button" className="secondary sm" disabled={busy} onClick={() => markDone(inProgress)}>
                    Marcar hecho
                  </button>
                ) : (
                  <span className="badge badge-pine">
                    <i aria-hidden="true" />
                    Hecho
                  </span>
                )}
              </div>
              <div
                className="bar-mini"
                title={`Faltan ${endMinutes(inProgress) - now.minutes} min`}
                role="progressbar"
                aria-valuenow={now.minutes - toMinutes(timeOf(inProgress.startTime))}
                aria-valuemax={inProgress.durationMinutes}
              >
                <i
                  style={{
                    width: `${((now.minutes - toMinutes(timeOf(inProgress.startTime))) / inProgress.durationMinutes) * 100}%`,
                  }}
                />
              </div>
            </>
          ) : (
            <p className="idle">Nada en curso en este momento.</p>
          )}

          <div className="next">
            {next ? (
              <>
                <span className="lead">{inProgress ? "Después sigue" : "Lo próximo"}</span>
                <div className="time">{timeOf(next.startTime)}</div>
                <div className="who">
                  <b>
                    {serviceName(next)} · {clientName(next) ?? "sin cliente"}
                  </b>
                  <span>
                    {professionalName(next.professionalId)} · {next.durationMinutes} min · {formatMoney(next.price)}
                  </span>
                </div>
                <span className="in">{inMinutes(toMinutes(timeOf(next.startTime)) - now.minutes)}</span>
              </>
            ) : (
              <span className="lead">No queda nada agendado hoy.</span>
            )}
          </div>
        </div>

        <div className="kpis">
          <Kpi label="Ventas de hoy" value={formatMoney(summaryToday.totalSales)} onClick={() => navigate("/sales")}>
            {salesDelta === null ? (
              <>Ayer: {formatMoney(summaryYesterday.totalSales)}</>
            ) : (
              <>
                <Delta value={salesDelta} suffix="%" /> vs ayer ({formatMoney(summaryYesterday.totalSales)})
              </>
            )}
          </Kpi>
          <Kpi label="Turnos de hoy" value={String(today.length)} onClick={() => navigate("/agenda")}>
            {plural(count("COMPLETED"), "hecho", "hechos")} · {count("CONFIRMED")} por venir ·{" "}
            {plural(count("CANCELLED") + count("NO_SHOW"), "caído", "caídos")}
          </Kpi>
          <Kpi label="Ocupación de hoy" value={`${occToday}%`} onClick={() => navigate("/statistics")}>
            <Delta value={occDelta} suffix=" pts" /> vs el {weekdayName} pasado
          </Kpi>
          <Kpi label="No asistieron (7 días)" value={String(noShowsWeek)} onClick={() => navigate("/statistics")}>
            <Delta value={noShowsWeek - noShowsPrevWeek} suffix="" goodWhenUp={false} /> vs semana anterior
          </Kpi>
        </div>
      </section>

      <section className="grid2">
        <div className="panel">
          <div className="panel-head">
            <h2>El día completo</h2>
            <span className="count">
              {today.length} {today.length === 1 ? "turno" : "turnos"}
            </span>
            <button type="button" className="secondary sm" onClick={() => navigate("/agenda")}>
              Ver agenda
            </button>
          </div>
          {past.length > 0 && (
            <button type="button" className="showpast" aria-expanded={showPast} onClick={() => setShowPast((v) => !v)}>
              {showPast ? "Ocultar" : "Mostrar"} lo que ya pasó ({past.length})
            </button>
          )}
          <ul className="tl">
            {showPast && past.map(row)}
            <li className="nowline" aria-hidden="true">
              <span>Ahora {toTime(now.minutes)}</span>
              <i />
            </li>
            {upcoming.length > 0 ? upcoming.map(row) : <li className="tl-empty">No queda nada agendado hoy.</li>}
          </ul>
        </div>

        <div className="panel">
          <div className="panel-head">
            <h2>Requiere atención</h2>
            <span className="count">{todos.length}</span>
          </div>
          {todos.length === 0 ? (
            <div className="empty">
              <b>Todo en orden</b>
              Nada pendiente por ahora.
            </div>
          ) : (
            <ul className="todo">
              {todos.map((t) => (
                <li key={t.key}>
                  <span className={`ic ${t.tone}`}>{t.icon}</span>
                  <div className="txt">
                    <b>{t.title}</b>
                    <span>{t.detail}</span>
                  </div>
                  {t.action && (
                    <button type="button" className="secondary sm" onClick={t.action.run}>
                      {t.action.label}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2>Cómo viene cada uno hoy</h2>
          <button type="button" className="secondary sm" onClick={() => navigate("/statistics")}>
            Ver estadísticas
          </button>
        </div>
        <div className="occ">
          {working.length === 0 ? (
            <p className="muted">Hoy no trabaja ningún profesional.</p>
          ) : (
            working.map((p) => {
              const rate = summaryToday.occupancyByProfessional.find((o) => o.professionalId === p.id)?.rate ?? 0;
              const sales = summaryToday.salesByProfessional.find((s) => s.professionalId === p.id)?.total ?? 0;
              const pct = Math.round(rate * 100);
              return (
                <div key={p.id} className="occ-row">
                  <span className="name" title={p.name}>
                    {p.name}
                  </span>
                  <span className="track" role="meter" aria-label={`Ocupación de ${p.name}`} aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
                    <i
                      style={{
                        width: `${Math.min(100, pct)}%`,
                        background: PROFESSIONAL_COLORS[catalog.professionals.indexOf(p) % PROFESSIONAL_COLORS.length],
                      }}
                    />
                  </span>
                  <span className="meta">
                    <b>{pct}%</b> · {formatMoney(sales)}
                  </span>
                </div>
              );
            })
          )}
          {working.length > 0 && (
            <div className="gaps">
              {data.gaps.length === 0 ? (
                <b>No quedan huecos libres hoy.</b>
              ) : (
                <>
                  <b>
                    {data.gaps.length === 1 ? "Queda 1 hueco libre hoy." : `Quedan ${data.gaps.length} huecos libres hoy.`}
                  </b>{" "}
                  Tocá uno para agendar ahí.
                  <div className="chip-row">
                    {gaps.map((g) => (
                      <button
                        key={`${g.professionalId}-${g.slot}`}
                        type="button"
                        className="chip"
                        onClick={() => bookAt(g.professionalId, toMinutes(timeOf(g.slot)))}
                      >
                        {timeOf(g.slot)} {professionalName(g.professionalId).split(" ")[0]}
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      </section>

      {detail && (
        <AppointmentDetailDialog
          appointment={detail}
          catalog={catalog}
          onClose={() => setDetail(null)}
          onChanged={(message) => {
            setDetail(null);
            showToast(message);
            reload();
          }}
        />
      )}
    </div>
  );
}

export default function HomeDashboard() {
  const { session } = useAuth();
  return session?.role === "ADMIN" ? <AdminHome /> : <TenantsAdminPage />;
}
