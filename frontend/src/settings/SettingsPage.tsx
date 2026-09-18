import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { toMinutes, type DayOfWeek } from "../agenda/model";
import { Badge } from "../components/Badge";
import { useToast } from "../components/Toast";

// Monday-first, as the business thinks about its week.
const WEEK: { key: DayOfWeek; label: string }[] = [
  { key: "MONDAY", label: "Lunes" },
  { key: "TUESDAY", label: "Martes" },
  { key: "WEDNESDAY", label: "Miércoles" },
  { key: "THURSDAY", label: "Jueves" },
  { key: "FRIDAY", label: "Viernes" },
  { key: "SATURDAY", label: "Sábado" },
  { key: "SUNDAY", label: "Domingo" },
];

// Section nav (mockup .subnav). "Cerrar el negocio" is not here: the Admin
// can't deactivate its own tenant yet (ENDPOINTS-PENDIENTES.md #9).
const SECTIONS = [
  { id: "negocio", label: "Negocio" },
  { id: "horarios", label: "Horarios" },
  { id: "reglas", label: "Reglas de turnos" },
  { id: "feriados", label: "Feriados y cierres" },
  { id: "whatsapp", label: "WhatsApp" },
];

interface ApiDaySchedule {
  dayOfWeek: DayOfWeek;
  openTime: string | null;
  closeTime: string | null;
  closed: boolean;
}

interface EstablishmentResponse {
  businessName: string;
  contactPhone: string | null;
  cancellationWindowHours: number;
  timezone: string;
  schedule: ApiDaySchedule[];
}

interface ClosureResponse {
  id: string;
  date: string;
  reason: string | null;
}

interface WhatsAppIntegrationResponse {
  phoneNumber: string;
  verificationStatus: string;
}

// A draft day always carries times, even while closed, so reopening a day
// shows hours right away instead of two blank inputs.
interface Day {
  dayOfWeek: DayOfWeek;
  label: string;
  openTime: string; // HH:mm
  closeTime: string; // HH:mm
  closed: boolean;
}

interface Draft {
  businessName: string;
  contactPhone: string;
  cancellationWindowHours: number;
  schedule: Day[];
}

function toDraft(establishment: EstablishmentResponse): Draft {
  return {
    businessName: establishment.businessName,
    contactPhone: establishment.contactPhone ?? "",
    cancellationWindowHours: establishment.cancellationWindowHours,
    schedule: WEEK.map(({ key, label }) => {
      const day = establishment.schedule.find((d) => d.dayOfWeek === key);
      return {
        dayOfWeek: key,
        label,
        openTime: (day?.openTime ?? "09:00").slice(0, 5),
        closeTime: (day?.closeTime ?? "18:00").slice(0, 5),
        closed: day?.closed ?? true,
      };
    }),
  };
}

// One PATCH carries the whole form: the API applies only the fields sent and
// replaces the full week in one shot.
function toPayload(draft: Draft) {
  return {
    businessName: draft.businessName.trim(),
    contactPhone: draft.contactPhone.trim() || null,
    cancellationWindowHours: draft.cancellationWindowHours,
    schedule: draft.schedule.map((day) => ({
      dayOfWeek: day.dayOfWeek,
      openTime: day.closed ? null : `${day.openTime}:00`,
      closeTime: day.closed ? null : `${day.closeTime}:00`,
      closed: day.closed,
    })),
  };
}

// Fields actually changed, for the save bar's "N cambios pendientes". Times of
// a day that is closed on both sides don't count - they aren't sent.
function changeCount(draft: Draft, base: Draft): number {
  let changes = 0;
  if (draft.businessName.trim() !== base.businessName.trim()) changes++;
  if (draft.contactPhone.trim() !== base.contactPhone.trim()) changes++;
  if (draft.cancellationWindowHours !== base.cancellationWindowHours) changes++;
  changes += draft.schedule.filter((day, i) => {
    const before = base.schedule[i];
    return (
      day.closed !== before.closed ||
      (!day.closed && (day.openTime !== before.openTime || day.closeTime !== before.closeTime))
    );
  }).length;
  return changes;
}

function hoursOf(day: Day): string {
  if (day.closed) return "Cerrado";
  const minutes = toMinutes(day.closeTime) - toMinutes(day.openTime);
  if (minutes <= 0) return "Revisar horario";
  return `${(minutes / 60).toFixed(minutes % 60 ? 1 : 0).replace(".", ",")} h`;
}

// "America/Argentina/Cordoba" -> "Cordoba (GMT-3)"
function zoneLabel(timezone: string): string {
  const city = timezone.split("/").pop()?.replace(/_/g, " ") ?? timezone;
  const offset = new Intl.DateTimeFormat("es-AR", { timeZone: timezone, timeZoneName: "shortOffset" })
    .formatToParts(new Date())
    .find((part) => part.type === "timeZoneName")?.value;
  return offset ? `${city} (${offset})` : city;
}

const CANCEL_HOURS = [0, 1, 2, 4, 12, 24];

function cancelLabel(hours: number): string {
  if (hours === 0) return "En cualquier momento";
  return `${hours} ${hours === 1 ? "hora" : "horas"} antes`;
}

function closureLabel(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Intl.DateTimeFormat("es-AR", { weekday: "short", day: "numeric", month: "short", year: "numeric" }).format(
    new Date(y, m - 1, d),
  );
}

function ClosuresSection() {
  const toast = useToast();
  const [closures, setClosures] = useState<ClosureResponse[] | null>(null);
  const [date, setDate] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function reload() {
    api
      .get<ClosureResponse[]>("/establishment/closures")
      .then((result) => {
        setError(null);
        setClosures([...result].sort((a, b) => a.date.localeCompare(b.date)));
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los feriados."));
  }

  useEffect(reload, []);

  async function add() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/establishment/closures", { date, reason: reason.trim() || null });
      setDate("");
      setReason("");
      reload();
      toast("Cierre agregado");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo agregar el feriado.");
    } finally {
      setBusy(false);
    }
  }

  async function remove(closure: ClosureResponse) {
    setError(null);
    try {
      await api.delete(`/establishment/closures/${closure.id}`);
      reload();
      toast(`Se liberó el ${closureLabel(closure.date)}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo quitar el feriado.");
    }
  }

  return (
    <section className="panel" id="feriados">
      <div className="panel-head">
        <div>
          <h2>Feriados y cierres</h2>
          <p>Días en los que no atendés. Se bloquean en la agenda y nadie puede reservarlos.</p>
        </div>
      </div>
      <div className="panel-body">
        {error && <p className="error-text">{error}</p>}
        <div className="closure-form">
          <label className="field">
            <span>Fecha</span>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="field">
            <span>Motivo</span>
            <input
              value={reason}
              placeholder="Feriado, vacaciones, mantenimiento…"
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <button type="button" onClick={add} disabled={!date || busy}>
            Agregar
          </button>
        </div>

        {closures === null ? (
          <p className="muted">Cargando…</p>
        ) : closures.length === 0 ? (
          <div className="table-empty">
            <b>Sin cierres cargados</b>
            Agregá los feriados que no vas a trabajar para que nadie reserve esos días.
          </div>
        ) : (
          <ul className="closures">
            {closures.map((closure) => (
              <li key={closure.id}>
                <b>{closureLabel(closure.date)}</b>
                <span className="muted">{closure.reason ?? "Cerrado"}</span>
                <button type="button" className="secondary sm" onClick={() => remove(closure)}>
                  Quitar
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

const WHATSAPP_STATUS: Record<string, { variant: "pine" | "amber" | "brick"; label: string }> = {
  VERIFIED: { variant: "pine", label: "Conectado" },
  PENDING: { variant: "amber", label: "Pendiente de verificación" },
  FAILED: { variant: "brick", label: "Falló la verificación" },
};

// Read-only on purpose: connecting a number (alta, verificación por código,
// elección de mensajes) has no endpoint yet - ENDPOINTS-PENDIENTES.md #8.
function WhatsAppSection() {
  const [integration, setIntegration] = useState<WhatsAppIntegrationResponse | null | "none">(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<WhatsAppIntegrationResponse>("/establishment/whatsapp-integration")
      .then(setIntegration)
      .catch((err) => {
        if (err instanceof ApiError && err.status === 404) setIntegration("none");
        else setError(err instanceof ApiError ? err.message : "No se pudo cargar la integración de WhatsApp.");
      });
  }, []);

  const status = integration && integration !== "none" ? WHATSAPP_STATUS[integration.verificationStatus] : null;

  return (
    <section className="panel" id="whatsapp">
      <div className="panel-head">
        <div>
          <h2>WhatsApp</h2>
          <p>El número por el que tus clientes reservan, cancelan y reciben los recordatorios.</p>
        </div>
        {integration !== null && (
          <Badge variant={status?.variant ?? "slate"}>{status?.label ?? "Sin conectar"}</Badge>
        )}
      </div>
      <div className="panel-body">
        {error && <p className="error-text">{error}</p>}
        <div className="wa">
          <span className="ic" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9 9 0 0 1-2.8-.4L4 21l1.5-4.2A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z" />
            </svg>
          </span>
          <div>
            {integration === null ? (
              <p className="muted">Cargando…</p>
            ) : integration === "none" ? (
              <>
                <b>Todavía no hay número conectado</b>
                <p>
                  Los turnos se cargan solo desde el panel y no se envían recordatorios. La conexión del número se hace
                  con nosotros, no desde acá.
                </p>
              </>
            ) : (
              <>
                <b>{integration.phoneNumber}</b>
                <p>
                  {integration.verificationStatus === "VERIFIED"
                    ? "El bot atiende reservas y cancelaciones por chat, y manda el recordatorio antes del turno."
                    : "Hasta que el número quede verificado el bot no responde ni manda recordatorios."}
                </p>
              </>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

export default function SettingsPage() {
  const toast = useToast();
  const [base, setBase] = useState<Draft | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [timezone, setTimezone] = useState("");
  const [active, setActive] = useState(SECTIONS[0].id);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function load() {
    api
      .get<EstablishmentResponse>("/establishment")
      .then((establishment) => {
        setError(null);
        setTimezone(establishment.timezone);
        setBase(toDraft(establishment));
        setDraft(toDraft(establishment));
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la configuración."));
  }

  useEffect(load, []);

  // Highlight the section being read (mockup's IntersectionObserver). Runs
  // once the sections exist, not on every keystroke.
  const loaded = draft !== null;
  useEffect(() => {
    if (!loaded) return;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) if (entry.isIntersecting) setActive(entry.target.id);
      },
      { rootMargin: "-10% 0px -70% 0px" },
    );
    for (const { id } of SECTIONS) {
      const element = document.getElementById(id);
      if (element) observer.observe(element);
    }
    return () => observer.disconnect();
  }, [loaded]);

  if (!draft || !base) {
    return (
      <div className="settings-page">
        <div className="page-head">
          <h1>Configuración</h1>
        </div>
        {error ? <p className="error-text">{error}</p> : <p className="muted">Cargando…</p>}
      </div>
    );
  }

  const update = (patch: Partial<Draft>) => setDraft({ ...draft, ...patch });
  const updateDay = (dayOfWeek: DayOfWeek, patch: Partial<Day>) =>
    update({ schedule: draft.schedule.map((day) => (day.dayOfWeek === dayOfWeek ? { ...day, ...patch } : day)) });

  const changes = changeCount(draft, base);
  const badDay = draft.schedule.find((day) => !day.closed && toMinutes(day.openTime) >= toMinutes(day.closeTime));
  const problem = !draft.businessName.trim()
    ? "El nombre del negocio no puede quedar vacío."
    : badDay
      ? `${badDay.label}: el cierre tiene que ser posterior a la apertura.`
      : null;

  // The stored window may not be one of the presets (it's a free integer in
  // the API) - show it as its own option instead of silently changing it.
  const cancelOptions = CANCEL_HOURS.includes(draft.cancellationWindowHours)
    ? CANCEL_HOURS
    : [...CANCEL_HOURS, draft.cancellationWindowHours].sort((a, b) => a - b);

  async function save() {
    if (!draft) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await api.patch<EstablishmentResponse>("/establishment", toPayload(draft));
      setTimezone(updated.timezone);
      setBase(toDraft(updated));
      setDraft(toDraft(updated));
      toast("Configuración guardada");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo guardar la configuración.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="settings-page">
      <div className="page-head">
        <div>
          <h1>Configuración</h1>
          <p>Los datos del local, cuándo abrís y las reglas con las que se reservan los turnos.</p>
        </div>
      </div>

      <nav className="subnav" aria-label="Secciones">
        {SECTIONS.map(({ id, label }) => (
          <a key={id} href={`#${id}`} aria-current={active === id ? "true" : undefined}>
            {label}
          </a>
        ))}
      </nav>

      <div className="sections">
        <section className="panel" id="negocio">
          <div className="panel-head">
            <div>
              <h2>Datos del negocio</h2>
              <p>Es lo que ven tus clientes cuando reservan y lo que sale en los recordatorios.</p>
            </div>
          </div>
          <div className="panel-body">
            <div className="field-row">
              <label className="field">
                <span>Nombre del negocio</span>
                <input
                  value={draft.businessName}
                  onChange={(e) => update({ businessName: e.target.value })}
                  autoComplete="off"
                />
              </label>
              <label className="field">
                <span>Teléfono de contacto</span>
                {/* ponytail: plain input, no fixed +54 9 prefix - the stored
                    number is free text and splitting it would mangle it. */}
                <input
                  type="tel"
                  placeholder="+54 9 351 …"
                  value={draft.contactPhone}
                  onChange={(e) => update({ contactPhone: e.target.value })}
                />
                <span className="help">Para que el cliente pueda llamarte desde el recordatorio.</span>
              </label>
            </div>
            <label className="field">
              <span>Zona horaria</span>
              <input value={zoneLabel(timezone)} disabled />
              <span className="help">
                Toda la agenda se muestra en esta zona. Se define al dar de alta el negocio.
              </span>
            </label>
          </div>
        </section>

        <section className="panel" id="horarios">
          <div className="panel-head">
            <div>
              <h2>Horarios de atención</h2>
              <p>El horario general del local. Cada profesional puede tener el suyo, siempre dentro de estas franjas.</p>
            </div>
            <button
              type="button"
              className="secondary sm"
              onClick={() => {
                const monday = draft.schedule[0];
                update({ schedule: draft.schedule.map((day) => ({ ...monday, dayOfWeek: day.dayOfWeek, label: day.label })) });
                toast("Se copió el horario del lunes a los 7 días");
              }}
            >
              Copiar lunes a todos
            </button>
          </div>
          <div className="panel-body">
            <div className="week">
              {draft.schedule.map((day) => (
                <div key={day.dayOfWeek} className={`week-row${day.closed ? " off" : ""}`}>
                  <label className="sw">
                    <input
                      type="checkbox"
                      checked={!day.closed}
                      aria-label={`${day.label}, abierto`}
                      onChange={(e) => updateDay(day.dayOfWeek, { closed: !e.target.checked })}
                    />
                    <i aria-hidden="true" />
                  </label>
                  <span className="dname">
                    <b>{day.label}</b>
                    <small>{hoursOf(day)}</small>
                  </span>
                  {day.closed ? (
                    <span className="muted">No se atiende este día</span>
                  ) : (
                    <span className="times">
                      <input
                        type="time"
                        aria-label={`${day.label}, abre`}
                        value={day.openTime}
                        onChange={(e) => updateDay(day.dayOfWeek, { openTime: e.target.value })}
                      />
                      <span className="muted">a</span>
                      <input
                        type="time"
                        aria-label={`${day.label}, cierra`}
                        value={day.closeTime}
                        onChange={(e) => updateDay(day.dayOfWeek, { closeTime: e.target.value })}
                      />
                    </span>
                  )}
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* Un solo rango por día: el horario partido y el resto de las reglas
            (horizonte de reserva, intervalo de la grilla, reservas online,
            recordatorio 24 h) están en ENDPOINTS-PENDIENTES.md #6 y #7. */}
        <section className="panel" id="reglas">
          <div className="panel-head">
            <div>
              <h2>Reglas de turnos</h2>
              <p>Cómo y cuándo pueden cancelar tus clientes por su cuenta.</p>
            </div>
          </div>
          <div className="panel-body">
            <label className="field">
              <span>Se puede cancelar hasta</span>
              <select
                value={draft.cancellationWindowHours}
                onChange={(e) => update({ cancellationWindowHours: Number(e.target.value) })}
              >
                {cancelOptions.map((hours) => (
                  <option key={hours} value={hours}>
                    {cancelLabel(hours)}
                  </option>
                ))}
              </select>
              <span className="help">
                {draft.cancellationWindowHours === 0
                  ? "El cliente puede cancelar incluso sobre la hora del turno."
                  : "Pasado ese límite el cliente ya no cancela solo: tiene que escribirte."}
              </span>
            </label>
          </div>
        </section>

        <ClosuresSection />
        <WhatsAppSection />
      </div>

      {/* Save bar (mockup .savebar): rendered only while there is something to
          save, so it never sits off-screen with focusable buttons. */}
      {changes > 0 && (
        <div className="savebar">
          <div className="msg">
            Tenés cambios sin guardar
            <span className={problem || error ? "error-text" : undefined}>
              {problem ?? error ?? (changes === 1 ? "1 cambio pendiente" : `${changes} cambios pendientes`)}
            </span>
          </div>
          <div className="acts">
            <button type="button" className="secondary" onClick={() => setDraft(base)} disabled={saving}>
              Descartar
            </button>
            <button type="button" onClick={save} disabled={saving || problem !== null}>
              {saving ? "Guardando…" : "Guardar cambios"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
