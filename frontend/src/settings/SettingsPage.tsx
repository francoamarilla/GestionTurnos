import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { type DaySchedule } from "../agenda/model";
import { Badge } from "../components/Badge";
import { toApiWeek, toDraftWeek, WeekRanges, weekProblem, type DraftDay } from "../components/WeekRanges";
import { useToast } from "../components/Toast";

// Section nav (mockup .subnav).
const SECTIONS = [
  { id: "negocio", label: "Negocio" },
  { id: "horarios", label: "Horarios" },
  { id: "reglas", label: "Reglas de turnos" },
  { id: "feriados", label: "Feriados y cierres" },
  { id: "whatsapp", label: "WhatsApp" },
  { id: "cerrar", label: "Cerrar el negocio" },
];

interface EstablishmentResponse {
  status: "ACTIVE" | "SUSPENDED" | "TRIAL";
  businessName: string;
  contactPhone: string | null;
  address: string | null;
  cancellationWindowHours: number;
  bookingHorizonDays: number | null; // null = sin límite
  slotIntervalMinutes: number;
  onlineBookingEnabled: boolean;
  remindersEnabled: boolean;
  timezone: string;
  schedule: DaySchedule[];
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

interface Draft {
  businessName: string;
  contactPhone: string;
  address: string;
  timezone: string;
  cancellationWindowHours: number;
  bookingHorizonDays: number; // 0 = sin límite, as the API reads it
  slotIntervalMinutes: number;
  onlineBookingEnabled: boolean;
  remindersEnabled: boolean;
  schedule: DraftDay[];
}

function toDraft(establishment: EstablishmentResponse): Draft {
  return {
    businessName: establishment.businessName,
    contactPhone: establishment.contactPhone ?? "",
    address: establishment.address ?? "",
    timezone: establishment.timezone,
    cancellationWindowHours: establishment.cancellationWindowHours,
    bookingHorizonDays: establishment.bookingHorizonDays ?? 0,
    slotIntervalMinutes: establishment.slotIntervalMinutes,
    onlineBookingEnabled: establishment.onlineBookingEnabled,
    remindersEnabled: establishment.remindersEnabled,
    schedule: toDraftWeek(establishment.schedule),
  };
}

// One PATCH carries the whole form: the API applies only the fields sent and
// replaces the full week in one shot.
function toPayload(draft: Draft) {
  return {
    businessName: draft.businessName.trim(),
    contactPhone: draft.contactPhone.trim() || null,
    address: draft.address.trim(),
    timezone: draft.timezone,
    cancellationWindowHours: draft.cancellationWindowHours,
    bookingHorizonDays: draft.bookingHorizonDays,
    slotIntervalMinutes: draft.slotIntervalMinutes,
    onlineBookingEnabled: draft.onlineBookingEnabled,
    remindersEnabled: draft.remindersEnabled,
    schedule: toApiWeek(draft.schedule),
  };
}

// Fields actually changed, for the save bar's "N cambios pendientes"; a day
// counts once however many of its ranges moved.
function changeCount(draft: Draft, base: Draft): number {
  let changes = 0;
  if (draft.businessName.trim() !== base.businessName.trim()) changes++;
  if (draft.contactPhone.trim() !== base.contactPhone.trim()) changes++;
  if (draft.address.trim() !== base.address.trim()) changes++;
  if (draft.timezone !== base.timezone) changes++;
  if (draft.cancellationWindowHours !== base.cancellationWindowHours) changes++;
  if (draft.bookingHorizonDays !== base.bookingHorizonDays) changes++;
  if (draft.slotIntervalMinutes !== base.slotIntervalMinutes) changes++;
  if (draft.onlineBookingEnabled !== base.onlineBookingEnabled) changes++;
  if (draft.remindersEnabled !== base.remindersEnabled) changes++;
  changes += draft.schedule.filter(
    (day, i) => JSON.stringify(day.ranges) !== JSON.stringify(base.schedule[i].ranges),
  ).length;
  return changes;
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

const HORIZON_DAYS = [
  { days: 7, label: "1 semana adelante" },
  { days: 14, label: "2 semanas adelante" },
  { days: 30, label: "1 mes adelante" },
  { days: 0, label: "Sin límite" },
];

const SLOT_INTERVALS = [15, 30, 60];

// The tenant's own zone plus every Argentine one, instead of the ~600 the
// browser knows. ponytail: widen the filter if a tenant lands outside.
function timezoneOptions(current: string): string[] {
  const zones = Intl.supportedValuesOf("timeZone").filter((zone) => zone.startsWith("America/Argentina/"));
  return zones.includes(current) ? zones : [current, ...zones];
}

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

// ENDPOINTS-PENDIENTES.md #9. Typing the name is the confirmation: this
// one can only be undone by us, not from the panel.
function CloseBusinessSection({
  businessName,
  closed,
  onClosed,
}: {
  businessName: string;
  closed: boolean;
  onClosed: () => void;
}) {
  const toast = useToast();
  const [typed, setTyped] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function close() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/establishment/deactivate");
      toast("El negocio quedó cerrado");
      onClosed();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo cerrar el negocio.");
      setBusy(false);
    }
  }

  return (
    <section className="panel danger-zone" id="cerrar">
      <div className="panel-head">
        <div>
          <h2>Cerrar el negocio en la plataforma</h2>
          <p>
            {closed
              ? "El negocio está cerrado: no entran turnos nuevos ni ventas. Tus datos siguen acá."
              : "Se apagan las reservas online y no se pueden cargar turnos ni ventas nuevas. Tus datos históricos quedan guardados."}
          </p>
        </div>
      </div>
      {!closed && (
        <div className="panel-body">
          {error && <p className="error-text">{error}</p>}
          <label className="field">
            <span>Escribí “{businessName}” para confirmar</span>
            <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
            <span className="help">Para volver a abrirlo tenés que escribirnos: no se reabre desde acá.</span>
          </label>
          <button type="button" disabled={busy || typed.trim() !== businessName} onClick={close}>
            {busy ? "Cerrando…" : "Cerrar el negocio"}
          </button>
        </div>
      )}
    </section>
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
  const [active, setActive] = useState(SECTIONS[0].id);
  const [status, setStatus] = useState<EstablishmentResponse["status"]>("ACTIVE");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function load() {
    api
      .get<EstablishmentResponse>("/establishment")
      .then((establishment) => {
        setError(null);
        setStatus(establishment.status);
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

  const changes = changeCount(draft, base);
  const problem = !draft.businessName.trim()
    ? "El nombre del negocio no puede quedar vacío."
    : weekProblem(draft.schedule);

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
            <div className="field-row">
              <label className="field">
                <span>Dirección</span>
                <input
                  value={draft.address}
                  maxLength={200}
                  placeholder="Av. Colón 1234, Córdoba"
                  onChange={(e) => update({ address: e.target.value })}
                />
                <span className="help">Sale en el recordatorio, para que el cliente sepa dónde ir.</span>
              </label>
              <label className="field">
                <span>Zona horaria</span>
                <select value={draft.timezone} onChange={(e) => update({ timezone: e.target.value })}>
                  {timezoneOptions(draft.timezone).map((zone) => (
                    <option key={zone} value={zone}>
                      {zoneLabel(zone)}
                    </option>
                  ))}
                </select>
                <span className="help">Toda la agenda se muestra en esta zona.</span>
              </label>
            </div>
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
                update({ schedule: draft.schedule.map((day) => ({ ...day, ranges: monday.ranges })) });
                toast("Se copió el horario del lunes a los 7 días");
              }}
            >
              Copiar lunes a todos
            </button>
          </div>
          <div className="panel-body">
            <WeekRanges
              week={draft.schedule}
              onChange={(schedule) => update({ schedule })}
              closedLabel="No se atiende este día"
            />
          </div>
        </section>

        <section className="panel" id="reglas">
          <div className="panel-head">
            <div>
              <h2>Reglas de turnos</h2>
              <p>Cómo reservan y cancelan tus clientes por su cuenta.</p>
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
            <div className="field-row">
              <label className="field">
                <span>Se puede reservar hasta</span>
                <select
                  value={draft.bookingHorizonDays}
                  onChange={(e) => update({ bookingHorizonDays: Number(e.target.value) })}
                >
                  {HORIZON_DAYS.map(({ days, label }) => (
                    <option key={days} value={days}>
                      {label}
                    </option>
                  ))}
                </select>
                <span className="help">Evita que te llenen la agenda de meses que todavía no sabés si vas a trabajar.</span>
              </label>
              <label className="field">
                <span>Cada cuánto arrancan los turnos</span>
                <select
                  value={draft.slotIntervalMinutes}
                  onChange={(e) => update({ slotIntervalMinutes: Number(e.target.value) })}
                >
                  {SLOT_INTERVALS.map((minutes) => (
                    <option key={minutes} value={minutes}>
                      Cada {minutes} minutos
                    </option>
                  ))}
                </select>
                <span className="help">
                  Con 15 minutos aprovechás mejor los huecos, pero se ofrecen más horarios para elegir.
                </span>
              </label>
            </div>
            <div className="week">
              <div className="week-row">
                <label className="sw">
                  <input
                    type="checkbox"
                    checked={draft.onlineBookingEnabled}
                    aria-label="Permitir reservas online"
                    onChange={(e) => update({ onlineBookingEnabled: e.target.checked })}
                  />
                  <i aria-hidden="true" />
                </label>
                <span className="dname">
                  <b>Permitir reservas online</b>
                  <small>Si lo apagás, los turnos solo se cargan desde el panel.</small>
                </span>
              </div>
              <div className="week-row">
                <label className="sw">
                  <input
                    type="checkbox"
                    checked={draft.remindersEnabled}
                    aria-label="Avisar por WhatsApp antes del turno"
                    onChange={(e) => update({ remindersEnabled: e.target.checked })}
                  />
                  <i aria-hidden="true" />
                </label>
                <span className="dname">
                  <b>Avisar por WhatsApp antes del turno</b>
                  <small>Recordatorios de 24 h y 2 h. Bajan los no-show.</small>
                </span>
              </div>
            </div>
          </div>
        </section>

        <ClosuresSection />
        <WhatsAppSection />
        <CloseBusinessSection
          businessName={base.businessName}
          closed={status === "SUSPENDED"}
          onClosed={load}
        />
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
