import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";

interface ProfessionalResponse {
  id: string;
  name: string;
  active: boolean;
}

interface NamedEntity {
  id: string;
  name: string;
}

interface CustomerOption {
  id: string;
  name: string | null;
  phone: string | null;
}

interface AppointmentResponse {
  id: string;
  professionalId: string;
  serviceId: string;
  customerId: string | null;
  startTime: string;
  durationMinutes: number;
  price: number;
  status: "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";
}

interface Page<T> {
  content: T[];
}

type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

interface DaySchedule {
  dayOfWeek: DayOfWeek;
  openTime: string | null;
  closeTime: string | null;
  closed: boolean;
}

interface EstablishmentResponse {
  schedule: DaySchedule[];
}

interface AgendaData {
  professionals: ProfessionalResponse[];
  services: NamedEntity[];
  customers: CustomerOption[];
  appointments: AppointmentResponse[];
  schedule: DaySchedule[];
}

function toIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y, m - 1, d + days);
  return toIsoDate(date);
}

function formatTime(iso: string): string {
  return iso.slice(11, 16);
}

function customerLabel(customer: CustomerOption | undefined): string {
  if (!customer) return "Sin cliente";
  return customer.name ?? customer.phone ?? "Sin cliente";
}

const DAY_OF_WEEK_BY_JS_INDEX: DayOfWeek[] = [
  "SUNDAY",
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
];

function dayOfWeekFromIso(iso: string): DayOfWeek {
  const [y, m, d] = iso.split("-").map(Number);
  return DAY_OF_WEEK_BY_JS_INDEX[new Date(y, m - 1, d).getDay()];
}

function timeToMinutes(time: string): number {
  const [h, m] = time.slice(0, 5).split(":").map(Number);
  return h * 60 + m;
}

function minutesToTime(minutes: number): string {
  const h = String(Math.floor(minutes / 60)).padStart(2, "0");
  const m = String(minutes % 60).padStart(2, "0");
  return `${h}:${m}`;
}

const SLOT_MINUTES = 30;
// ponytail: fallback window for a day with no establishment schedule row
// (or explicitly closed) - keeps the grid usable instead of empty; actual
// booking availability is still enforced server-side regardless of this.
const FALLBACK_OPEN_TIME = "09:00";
const FALLBACK_CLOSE_TIME = "20:00";

async function loadAgenda(date: string): Promise<AgendaData> {
  const [professionalsPage, services, customersPage, appointments, establishment] = await Promise.all([
    api.get<Page<ProfessionalResponse>>("/professionals?active=true&size=100"),
    api.get<NamedEntity[]>("/services"),
    // ponytail: one page of up to 200 customers for the booking dropdown -
    // fine for this MVP's scale, same ceiling used elsewhere; the Customers
    // screen's search is the real way to find someone in a bigger base.
    api.get<Page<CustomerOption>>("/customers?size=200"),
    api.get<AppointmentResponse[]>(`/appointments?from=${date}&to=${date}`),
    api.get<EstablishmentResponse>("/establishment"),
  ]);
  return {
    professionals: professionalsPage.content,
    services,
    customers: customersPage.content,
    appointments: appointments.sort((a, b) => a.startTime.localeCompare(b.startTime)),
    schedule: establishment.schedule,
  };
}

function BookingForm({
  date,
  professional,
  services,
  customers,
  onCancel,
  onBooked,
}: {
  date: string;
  professional: ProfessionalResponse;
  services: NamedEntity[];
  customers: CustomerOption[];
  onCancel: () => void;
  onBooked: () => void;
}) {
  const [serviceId, setServiceId] = useState(services[0]?.id ?? "");
  // Keyed by the query that produced them, so a service/date change makes
  // stale slots (and any slot selected from them) disappear as soon as the
  // key no longer matches, without a separate reset call in the effect.
  const [slotsResult, setSlotsResult] = useState<{ key: string; slots: string[] } | null>(null);
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);
  const [customerId, setCustomerId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const slotsKey = `${professional.id}|${serviceId}|${date}`;
  const slots = slotsResult?.key === slotsKey ? slotsResult.slots : null;
  const effectiveSelectedSlot = slots?.includes(selectedSlot ?? "") ? selectedSlot : null;

  useEffect(() => {
    if (!serviceId) return;
    api
      .get<string[]>(`/availability?professionalId=${professional.id}&serviceId=${serviceId}&date=${date}`)
      .then((result) => setSlotsResult({ key: slotsKey, slots: result }))
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la disponibilidad."));
  }, [slotsKey, serviceId, professional.id, date]);

  async function submit() {
    if (!effectiveSelectedSlot) return;
    setSubmitting(true);
    setError(null);
    try {
      await api.post("/appointments", {
        professionalId: professional.id,
        serviceId,
        startTime: effectiveSelectedSlot,
        customerId: customerId || null,
      });
      onBooked();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo crear el turno.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="card" style={{ maxWidth: 480 }}>
      <h2>Nuevo turno con {professional.name}</h2>
      {error && <p className="error-text">{error}</p>}

      <label className="field">
        <span>Servicio</span>
        <select value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
          {services.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>Cliente (opcional)</span>
        <select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
          <option value="">Sin cliente</option>
          {customers.map((c) => (
            <option key={c.id} value={c.id}>
              {customerLabel(c)}
            </option>
          ))}
        </select>
      </label>

      <div className="field">
        <span>Horario</span>
        {slots === null ? (
          <p>Cargando horarios...</p>
        ) : slots.length === 0 ? (
          <p>No hay horarios disponibles ese día.</p>
        ) : (
          <div className="slot-grid">
            {slots.map((slot) => (
              <button
                key={slot}
                type="button"
                className={slot === effectiveSelectedSlot ? "" : "secondary"}
                onClick={() => setSelectedSlot(slot)}
              >
                {formatTime(slot)}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="form-actions">
        <button type="button" onClick={submit} disabled={!effectiveSelectedSlot || submitting}>
          {submitting ? "Creando..." : "Crear turno"}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

function RescheduleForm({
  appointment,
  date,
  onCancel,
  onDone,
}: {
  appointment: AppointmentResponse;
  date: string;
  onCancel: () => void;
  onDone: () => void;
}) {
  const [newDate, setNewDate] = useState(date);
  const [slotsResult, setSlotsResult] = useState<{ key: string; slots: string[] } | null>(null);
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const slotsKey = `${appointment.professionalId}|${appointment.serviceId}|${newDate}`;
  const slots = slotsResult?.key === slotsKey ? slotsResult.slots : null;
  const effectiveSelectedSlot = slots?.includes(selectedSlot ?? "") ? selectedSlot : null;

  useEffect(() => {
    api
      .get<string[]>(
        `/availability?professionalId=${appointment.professionalId}&serviceId=${appointment.serviceId}&date=${newDate}`,
      )
      .then((result) => setSlotsResult({ key: slotsKey, slots: result }))
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la disponibilidad."));
  }, [slotsKey, appointment.professionalId, appointment.serviceId, newDate]);

  async function submit() {
    if (!effectiveSelectedSlot) return;
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/appointments/${appointment.id}/reschedule`, { startTime: effectiveSelectedSlot });
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo reprogramar el turno.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="card" style={{ maxWidth: 480 }}>
      <h2>Reprogramar turno</h2>
      {error && <p className="error-text">{error}</p>}

      <label className="field">
        <span>Nueva fecha</span>
        <input type="date" value={newDate} onChange={(e) => setNewDate(e.target.value)} />
      </label>

      <div className="field">
        <span>Horario</span>
        {slots === null ? (
          <p>Cargando horarios...</p>
        ) : slots.length === 0 ? (
          <p>No hay horarios disponibles ese día.</p>
        ) : (
          <div className="slot-grid">
            {slots.map((slot) => (
              <button
                key={slot}
                type="button"
                className={slot === effectiveSelectedSlot ? "" : "secondary"}
                onClick={() => setSelectedSlot(slot)}
              >
                {formatTime(slot)}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="form-actions">
        <button type="button" onClick={submit} disabled={!effectiveSelectedSlot || submitting}>
          {submitting ? "Guardando..." : "Confirmar"}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

function AppointmentCard({
  appointment,
  serviceName,
  customer,
  onChanged,
  onReschedule,
}: {
  appointment: AppointmentResponse;
  serviceName: string;
  customer: CustomerOption | undefined;
  onChanged: () => void;
  onReschedule: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function setStatus(status: string) {
    setBusy(true);
    setError(null);
    try {
      await api.patch(`/appointments/${appointment.id}/status`, { status });
      onChanged();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo actualizar el turno.");
      setBusy(false);
    }
  }

  return (
    <div className="appointment-card">
      <strong>{formatTime(appointment.startTime)}</strong> {serviceName}
      <br />
      {customerLabel(customer)}
      <br />
      <span>{appointment.status}</span>
      {error && <p className="error-text">{error}</p>}
      {appointment.status === "CONFIRMED" && (
        <div className="form-actions">
          <button type="button" className="secondary" disabled={busy} onClick={() => setStatus("COMPLETED")}>
            Completado
          </button>
          <button type="button" className="secondary" disabled={busy} onClick={() => setStatus("NO_SHOW")}>
            No asistió
          </button>
          <button type="button" className="secondary" disabled={busy} onClick={onReschedule}>
            Reprogramar
          </button>
          <button type="button" className="secondary" disabled={busy} onClick={() => setStatus("CANCELLED")}>
            Cancelar
          </button>
        </div>
      )}
    </div>
  );
}

export default function AgendaPage() {
  const [date, setDate] = useState(toIsoDate(new Date()));
  const [data, setData] = useState<AgendaData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [bookingFor, setBookingFor] = useState<ProfessionalResponse | null>(null);
  const [rescheduling, setRescheduling] = useState<AppointmentResponse | null>(null);

  function reload() {
    loadAgenda(date)
      .then(setData)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la agenda."));
  }

  useEffect(reload, [date]);

  if (bookingFor && data) {
    return (
      <div>
        <h1>Agenda</h1>
        <BookingForm
          date={date}
          professional={bookingFor}
          services={data.services}
          customers={data.customers}
          onCancel={() => setBookingFor(null)}
          onBooked={() => {
            setBookingFor(null);
            reload();
          }}
        />
      </div>
    );
  }

  if (rescheduling) {
    return (
      <div>
        <h1>Agenda</h1>
        <RescheduleForm
          appointment={rescheduling}
          date={date}
          onCancel={() => setRescheduling(null)}
          onDone={() => {
            setRescheduling(null);
            reload();
          }}
        />
      </div>
    );
  }

  return (
    <div>
      <h1>Agenda</h1>
      {error && <p className="error-text">{error}</p>}

      <div className="form-actions">
        <button type="button" className="secondary" onClick={() => setDate((d) => addDays(d, -1))}>
          Día anterior
        </button>
        <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <button type="button" className="secondary" onClick={() => setDate((d) => addDays(d, 1))}>
          Día siguiente
        </button>
      </div>

      {data === null ? (
        <p>Cargando...</p>
      ) : data.professionals.length === 0 ? (
        <p>No hay profesionales activos.</p>
      ) : (
        (() => {
          const daySchedule = data.schedule.find((s) => s.dayOfWeek === dayOfWeekFromIso(date));
          const isOpen = daySchedule && !daySchedule.closed && daySchedule.openTime && daySchedule.closeTime;
          const openMinutes = timeToMinutes(isOpen ? daySchedule.openTime! : FALLBACK_OPEN_TIME);
          const closeMinutes = timeToMinutes(isOpen ? daySchedule.closeTime! : FALLBACK_CLOSE_TIME);
          const slotCount = Math.max(1, Math.ceil((closeMinutes - openMinutes) / SLOT_MINUTES));
          const slotStarts = Array.from({ length: slotCount }, (_, i) => openMinutes + i * SLOT_MINUTES);

          return (
            <>
              {daySchedule?.closed && (
                <p>El establecimiento está cerrado este día (mostrando de todos modos un horario de referencia).</p>
              )}
              <div
                className="agenda-hourgrid"
                style={{
                  gridTemplateColumns: `64px repeat(${data.professionals.length}, minmax(180px, 1fr))`,
                  gridTemplateRows: `auto repeat(${slotCount}, 68px)`,
                }}
              >
                {data.professionals.map((professional, colIndex) => (
                  <div
                    key={professional.id}
                    className="agenda-hourgrid-header"
                    style={{ gridColumn: colIndex + 2, gridRow: 1 }}
                  >
                    <div>{professional.name}</div>
                    <button type="button" onClick={() => setBookingFor(professional)}>
                      Agregar turno
                    </button>
                  </div>
                ))}

                {slotStarts.map((minutes, rowIndex) => (
                  <div
                    key={minutes}
                    className="agenda-hourgrid-time"
                    style={{ gridColumn: 1, gridRow: rowIndex + 2 }}
                  >
                    {minutesToTime(minutes)}
                  </div>
                ))}

                {data.professionals.map((professional, colIndex) =>
                  slotStarts.map((minutes, rowIndex) => (
                    <div
                      key={`${professional.id}-${minutes}`}
                      className="agenda-hourgrid-cell"
                      style={{ gridColumn: colIndex + 2, gridRow: rowIndex + 2 }}
                    />
                  )),
                )}

                {data.professionals.map((professional, colIndex) =>
                  data.appointments
                    .filter((a) => a.professionalId === professional.id)
                    .map((appt) => {
                      const startMinutes = timeToMinutes(formatTime(appt.startTime));
                      const rowStart = Math.min(
                        Math.max(Math.floor((startMinutes - openMinutes) / SLOT_MINUTES) + 2, 2),
                        slotCount + 1,
                      );
                      const rowSpan = Math.max(1, Math.round(appt.durationMinutes / SLOT_MINUTES));
                      return (
                        <div
                          key={appt.id}
                          style={{ gridColumn: colIndex + 2, gridRow: `${rowStart} / span ${rowSpan}` }}
                        >
                          <AppointmentCard
                            appointment={appt}
                            serviceName={data.services.find((s) => s.id === appt.serviceId)?.name ?? "Servicio"}
                            customer={data.customers.find((c) => c.id === appt.customerId)}
                            onChanged={reload}
                            onReschedule={() => setRescheduling(appt)}
                          />
                        </div>
                      );
                    }),
                )}
              </div>
            </>
          );
        })()
      )}
    </div>
  );
}
