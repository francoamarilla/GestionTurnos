import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { useToast } from "../components/Toast";
import { AppointmentDetailDialog, NewAppointmentDialog } from "./AppointmentDialogs";
import {
  addDays,
  customerLabel,
  dayWindow,
  isWorked,
  rangesLabel,
  loadAppointments,
  loadCatalog,
  longDate,
  nowInZone,
  PROFESSIONAL_COLORS,
  STATUS_CLASS,
  timeOf,
  toMinutes,
  toTime,
  type Appointment,
  type Catalog,
  type Customer,
} from "./model";

const STEP = 30;
// ponytail: grid window for a day the branch has no hours (closed or no
// row) - keeps the board readable; booking is still refused server-side.
const FALLBACK_WINDOW = { open: 9 * 60, close: 20 * 60 };

// top/height in slot units, scaled by the --slot-h token.
function slotBox(fromMinutes: number, lengthMinutes: number, inset = 0): CSSProperties {
  return {
    top: `calc(var(--slot-h) * ${fromMinutes / STEP} + ${inset}px)`,
    height: `calc(var(--slot-h) * ${lengthMinutes / STEP} - ${inset * 2}px)`,
  };
}

interface AgendaRouterState {
  book?: { professionalId?: string; minutes?: number; customer?: Customer };
  professionalId?: string;
}

type DialogState =
  | { kind: "new"; professionalId?: string; minutes?: number; customer?: Customer }
  | { kind: "detail"; appointment: Appointment }
  | null;

export default function AgendaPage() {
  const showToast = useToast();
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [date, setDate] = useState<string | null>(null);
  const [appointments, setAppointments] = useState<{ date: string; list: Appointment[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const location = useLocation();
  const navigate = useNavigate();
  // Other screens deep-link here via router state:
  //   { book: { professionalId?, minutes?, customer? } } opens the booking dialog,
  //   { professionalId } filters the board to that professional.
  const routerState = location.state as AgendaRouterState | null;
  const [filter, setFilter] = useState(routerState?.professionalId ?? "all");
  const [dialog, setDialog] = useState<DialogState>(() =>
    routerState?.book ? { kind: "new", ...routerState.book } : null,
  );
  const [now, setNow] = useState<{ date: string; minutes: number } | null>(null);
  const boardRef = useRef<HTMLDivElement>(null);
  const scrolledFor = useRef<string | null>(null);

  function loadAll() {
    loadCatalog()
      .then((result) => {
        setError(null);
        setCatalog(result);
        const current = nowInZone(result.timezone);
        setNow(current);
        setDate((d) => d ?? current.date);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la agenda."));
  }

  function reloadDay(day: string) {
    loadAppointments(day)
      .then((list) => {
        setError(null);
        setAppointments({ date: day, list });
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los turnos."));
  }

  useEffect(loadAll, []);

  // Consume the router state so a reload doesn't reopen the dialog.
  useEffect(() => {
    if (location.state) navigate(location.pathname, { replace: true, state: null });
  }, [location.state, location.pathname, navigate]);

  useEffect(() => {
    if (date) reloadDay(date);
  }, [date]);

  // Keep the "now" line moving.
  useEffect(() => {
    if (!catalog) return;
    const timer = window.setInterval(() => setNow(nowInZone(catalog.timezone)), 60_000);
    return () => window.clearInterval(timer);
  }, [catalog]);

  // "n" opens a new appointment (mockup shortcut).
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement).tagName;
      if (e.key !== "n" || dialog || e.ctrlKey || e.metaKey || e.altKey) return;
      if (["INPUT", "SELECT", "TEXTAREA"].includes(tag)) return;
      e.preventDefault();
      setDialog({ kind: "new" });
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [dialog]);

  const list = appointments && appointments.date === date ? appointments.list : null;
  const isToday = !!now && now.date === date;
  const branchWindow = catalog && date ? dayWindow(catalog.branchSchedule, date) : null;
  const window_ = branchWindow ?? FALLBACK_WINDOW;

  // Scroll the board to "now" once per day shown.
  useEffect(() => {
    if (!list || !date || scrolledFor.current === date || !boardRef.current) return;
    scrolledFor.current = date;
    const slotH = parseFloat(getComputedStyle(boardRef.current).getPropertyValue("--slot-h")) || 54;
    boardRef.current.scrollTop =
      isToday && now ? Math.max(0, ((now.minutes - window_.open) / STEP) * slotH - 200) : 0;
  }, [list, date, isToday, now, window_.open]);

  if (error && !catalog) {
    return (
      <div className="agenda-page">
        <div className="page-bar">
          <h1>Agenda</h1>
        </div>
        <div className="agenda-message">
          <p className="error-text">{error}</p>
          <button type="button" onClick={loadAll}>
            Reintentar
          </button>
        </div>
      </div>
    );
  }

  if (!catalog || !date) {
    return (
      <div className="agenda-page">
        <div className="page-bar">
          <h1>Agenda</h1>
        </div>
        <p className="agenda-message muted">Cargando…</p>
      </div>
    );
  }

  const closure = catalog.closures.find((c) => c.date === date);
  const professionals = catalog.professionals.filter((p) => filter === "all" || p.id === filter);
  const rows = Math.max(1, Math.ceil((window_.close - window_.open) / STEP));
  const nowOffset = isToday && now ? now.minutes - window_.open : null;
  const showNow = nowOffset !== null && nowOffset >= 0 && nowOffset <= rows * STEP;

  return (
    <div className="agenda-page">
      <div className="page-bar">
        <h1>Agenda</h1>
        <div className="daynav">
          <button type="button" title="Día anterior" aria-label="Día anterior" onClick={() => setDate(addDays(date, -1))}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 5l-7 7 7 7" />
            </svg>
          </button>
          <button type="button" title="Día siguiente" aria-label="Día siguiente" onClick={() => setDate(addDays(date, 1))}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M9 5l7 7-7 7" />
            </svg>
          </button>
          <button type="button" className="today" onClick={() => now && setDate(now.date)}>
            Hoy
          </button>
        </div>
        <label className="dateline">
          {longDate(date)}
          {isToday && <em> · hoy</em>}
          <input
            type="date"
            className="dateline-picker"
            aria-label="Elegir fecha"
            value={date}
            onClick={(e) => e.currentTarget.showPicker?.()}
            onChange={(e) => e.target.value && setDate(e.target.value)}
          />
        </label>
        <span className="spacer" />
        {catalog.professionals.length > 1 && (
          <select
            className="select"
            aria-label="Filtrar profesional"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          >
            <option value="all">Todos los profesionales</option>
            {catalog.professionals.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        )}
        <button type="button" onClick={() => setDialog({ kind: "new" })} disabled={catalog.professionals.length === 0}>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Nuevo turno
        </button>
      </div>

      <div className="legend">
        <span>
          <i style={{ background: "var(--primary)" }} />
          Confirmado
        </span>
        <span>
          <i style={{ background: "var(--pine)" }} />
          Completado
        </span>
        <span>
          <i style={{ background: "var(--amber)" }} />
          No asistió
        </span>
        <span>
          <i style={{ background: "var(--slate)" }} />
          Cancelado
        </span>
        <span className="hint">
          Tocá un espacio libre para agendar ahí · <kbd>N</kbd> nuevo turno
        </span>
      </div>

      {error && <p className="agenda-message error-text">{error}</p>}
      {closure ? (
        <p className="agenda-notice">
          El local está cerrado este día{closure.reason ? ` (${closure.reason})` : ""}. No se pueden agendar turnos.
        </p>
      ) : (
        !branchWindow && <p className="agenda-notice">El local no abre este día. No se pueden agendar turnos.</p>
      )}

      {catalog.professionals.length === 0 ? (
        <p className="agenda-message muted">No hay profesionales activos. Agregalos en Profesionales.</p>
      ) : (
        <div className="board" ref={boardRef} aria-busy={!list}>
          <div
            className="cols"
            style={{ gridTemplateColumns: `var(--gutter-w) repeat(${professionals.length}, minmax(190px, 1fr))` }}
          >
            <div className="hcell gut" />
            {professionals.map((p) => {
              const count =
                list?.filter((a) => a.professionalId === p.id && a.status !== "CANCELLED" && a.status !== "VOIDED")
                  .length ?? 0;
              return (
                <div key={p.id} className="hcell">
                  <div className="pro">
                    <span
                      className="dot"
                      style={{ background: PROFESSIONAL_COLORS[catalog.professionals.indexOf(p) % PROFESSIONAL_COLORS.length] }}
                    />
                    <div>
                      <b>{p.name}</b>
                      <small>
                        {count} {count === 1 ? "turno" : "turnos"} ·{" "}
                        {rangesLabel(catalog.schedules.get(p.id), date) ?? "no trabaja"}
                      </small>
                    </div>
                  </div>
                </div>
              );
            })}

            <div className="gutter">
              {Array.from({ length: rows }, (_, i) => {
                const minutes = window_.open + i * STEP;
                return (
                  <div key={minutes} className={`tick${minutes % 60 ? " half" : ""}`}>
                    <span>{toTime(minutes)}</span>
                  </div>
                );
              })}
              {showNow && (
                <div className="nowlabel" style={{ top: `calc(var(--slot-h) * ${nowOffset! / STEP})` }}>
                  {toTime(now!.minutes)}
                </div>
              )}
            </div>

            {professionals.map((p) => {
              const bookable = !closure && branchWindow;
              return (
                <div key={p.id} className="col">
                  {Array.from({ length: rows }, (_, i) => {
                    const minutes = window_.open + i * STEP;
                    // Closed when the branch is shut (a split day's midday
                    // gap included, #7) or this professional isn't working.
                    const closed =
                      !isWorked(catalog.branchSchedule, date, minutes) ||
                      !isWorked(catalog.schedules.get(p.id), date, minutes);
                    const past = !!now && (date < now.date || (isToday && minutes + STEP <= now.minutes));
                    return (
                      <div key={minutes} className={`slot ${minutes % 60 ? "half" : "hour"}${closed ? " closed" : ""}`}>
                        {!closed && bookable && !past && (
                          <button
                            type="button"
                            aria-label={`Agendar a las ${toTime(minutes)} con ${p.name}`}
                            onClick={() => setDialog({ kind: "new", professionalId: p.id, minutes })}
                          >
                            + Agendar
                          </button>
                        )}
                      </div>
                    );
                  })}

                  {list
                    ?.filter((a) => a.professionalId === p.id)
                    .map((a) => {
                      const start = toMinutes(timeOf(a.startTime));
                      const service = catalog.services.find((s) => s.id === a.serviceId);
                      const customer = catalog.customers.find((c) => c.id === a.customerId);
                      const short = a.durationMinutes <= STEP;
                      return (
                        <button
                          key={a.id}
                          type="button"
                          className={`appt ${STATUS_CLASS[a.status]}${short ? " short" : ""}`}
                          style={slotBox(Math.max(0, start - window_.open), a.durationMinutes, 3)}
                          onClick={() => setDialog({ kind: "detail", appointment: a })}
                        >
                          <span className="t">{timeOf(a.startTime)}</span>
                          <span className="s">{a.description ?? service?.name ?? "Servicio"}</span>
                          <span className="c">{a.customerId ? customerLabel(customer) : "Sin cliente"}</span>
                        </button>
                      );
                    })}

                  {showNow && <div className="now" style={{ top: `calc(var(--slot-h) * ${nowOffset! / STEP})` }} />}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {dialog?.kind === "new" && (
        <NewAppointmentDialog
          catalog={catalog}
          date={date}
          initialProfessionalId={dialog.professionalId}
          initialMinutes={dialog.minutes}
          initialCustomer={dialog.customer}
          onClose={() => setDialog(null)}
          onCreated={(appointment, customer) => {
            if (customer && !catalog.customers.some((c) => c.id === customer.id)) {
              setCatalog({ ...catalog, customers: [...catalog.customers, customer] });
            }
            setDialog(null);
            const service = catalog.services.find((s) => s.id === appointment.serviceId);
            showToast(`Turno agendado ${timeOf(appointment.startTime)} · ${service?.name ?? ""}`);
            reloadDay(date);
          }}
        />
      )}

      {dialog?.kind === "detail" && (
        <AppointmentDetailDialog
          appointment={dialog.appointment}
          catalog={catalog}
          onClose={() => setDialog(null)}
          onChanged={(message) => {
            setDialog(null);
            showToast(message);
            reloadDay(date);
          }}
        />
      )}
    </div>
  );
}
