import { useEffect, useId, useState } from "react";
import { api, ApiError } from "../api/client";
import { AppointmentStatusBadge } from "../components/Badge";
import { Dialog } from "../components/Dialog";
import { formatMoney } from "../format";
import {
  customerLabel,
  longDate,
  nowInZone,
  STATUS_LABEL,
  timeOf,
  toMinutes,
  toTime,
  type Appointment,
  type AppointmentStatus,
  type Catalog,
  type Customer,
} from "./model";

function errorMessage(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.message : fallback;
}

// Free slots for professional+service+date, split into morning/afternoon
// chips (mockup .chip-row). Busy slots aren't returned by the API at all.
function SlotPicker({
  professionalId,
  serviceId,
  date,
  timezone,
  selected,
  preferredMinutes,
  onSelect,
}: {
  professionalId: string;
  serviceId: string;
  date: string;
  timezone: string;
  selected: string | null;
  preferredMinutes?: number;
  onSelect: (slot: string | null) => void;
}) {
  const key = `${professionalId}|${serviceId}|${date}`;
  const [result, setResult] = useState<{ key: string; slots: string[] | null; error: string | null } | null>(null);
  const current = result?.key === key ? result : null;

  useEffect(() => {
    if (!professionalId || !serviceId || !date) return;
    let cancelled = false;
    api
      .get<string[]>(`/availability?professionalId=${professionalId}&serviceId=${serviceId}&date=${date}`)
      .then((all) => {
        if (cancelled) return;
        // Today's already-past starts aren't bookable in practice.
        const now = nowInZone(timezone);
        const slots = date === now.date ? all.filter((s) => toMinutes(timeOf(s)) > now.minutes) : all;
        setResult({ key, slots, error: null });
        const preferred = slots.find((s) => toMinutes(timeOf(s)) === preferredMinutes);
        onSelect(preferred ?? null);
      })
      .catch((err) => !cancelled && setResult({ key, slots: null, error: errorMessage(err, "No se pudo cargar la disponibilidad.") }));
    return () => {
      cancelled = true;
    };
    // onSelect/preferredMinutes only matter when a new slot list arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  if (!serviceId) return <span className="help">Este profesional no tiene servicios activos asignados.</span>;
  if (!current) return <span className="help">Cargando horarios…</span>;
  if (current.error) return <span className="error-text">{current.error}</span>;

  const slots = current.slots ?? [];
  if (slots.length === 0) return <span className="help">No hay horarios libres ese día.</span>;
  const groups = [
    { label: "Mañana", slots: slots.filter((s) => toMinutes(timeOf(s)) < 13 * 60) },
    { label: "Tarde", slots: slots.filter((s) => toMinutes(timeOf(s)) >= 13 * 60) },
  ];
  return (
    <>
      {groups.map((group) => (
        <div key={group.label}>
          <div className="slotlabel">{group.label}</div>
          <div className="chip-row">
            {group.slots.length === 0 ? (
              <span className="help">Sin horarios a la {group.label.toLowerCase()}.</span>
            ) : (
              group.slots.map((slot) => (
                <button
                  key={slot}
                  type="button"
                  className="chip"
                  aria-pressed={slot === selected}
                  onClick={() => onSelect(slot)}
                >
                  {timeOf(slot)}
                </button>
              ))
            )}
          </div>
        </div>
      ))}
    </>
  );
}

type PickedCustomer = { kind: "existing"; customer: Customer } | { kind: "new"; text: string } | null;

// Mockup .combo: server-side search, "Crear cliente" for an unknown name or
// phone (created on submit), empty = walk-in customer.
function CustomerCombo({ value, onChange }: { value: PickedCustomer; onChange: (value: PickedCustomer) => void }) {
  const listId = useId();
  const [query, setQuery] = useState(
    value?.kind === "existing" ? customerLabel(value.customer) : value?.kind === "new" ? value.text : "",
  );
  const [open, setOpen] = useState(false);
  const [results, setResults] = useState<Customer[]>([]);
  const [highlight, setHighlight] = useState(0);

  useEffect(() => {
    if (!open) return;
    const q = query.trim();
    const timer = window.setTimeout(() => {
      api
        .get<{ content: Customer[] }>(`/customers?size=8${q ? `&search=${encodeURIComponent(q)}` : ""}`)
        .then((page) => setResults(page.content.filter((c) => c.active).slice(0, 5)))
        .catch(() => setResults([]));
    }, 200);
    return () => window.clearTimeout(timer);
  }, [query, open]);

  const q = query.trim();
  const options: { label: string; hint?: string; isAction?: boolean; pick: PickedCustomer }[] = [
    ...results.map((c) => ({
      label: customerLabel(c),
      hint: c.name ? (c.phone ?? undefined) : undefined,
      pick: { kind: "existing", customer: c } as PickedCustomer,
    })),
    q
      ? { label: `Crear cliente “${q}”`, isAction: true, pick: { kind: "new", text: q } as PickedCustomer }
      : { label: "Sin cliente (ocasional)", isAction: true, pick: null },
  ];

  function choose(index: number) {
    const option = options[index];
    if (!option) return;
    onChange(option.pick);
    setQuery(option.pick === null ? "" : option.pick.kind === "new" ? option.pick.text : option.label);
    setOpen(false);
  }

  return (
    <div className="combo">
      <input
        className="input"
        id="appt-customer"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        autoComplete="off"
        placeholder="Buscá por nombre o teléfono"
        value={query}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onChange={(e) => {
          setQuery(e.target.value);
          setHighlight(0);
          setOpen(true);
          if (!e.target.value.trim()) onChange(null);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setOpen(true);
            setHighlight((h) => Math.min(h + 1, options.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setHighlight((h) => Math.max(h - 1, 0));
          } else if (e.key === "Enter" && open) {
            e.preventDefault();
            choose(highlight);
          } else if (e.key === "Escape" && open) {
            // Close the list, not the whole dialog.
            e.preventDefault();
            e.stopPropagation();
            setOpen(false);
          }
        }}
      />
      {open && (
        <div className="combo-list" id={listId} role="listbox">
          {options.map((option, i) => (
            <button
              key={option.label}
              type="button"
              role="option"
              aria-selected={i === highlight}
              className={i === highlight ? "hl" : ""}
              // mousedown, not click: fires before the input's blur closes the list.
              onMouseDown={(e) => {
                e.preventDefault();
                choose(i);
              }}
            >
              {option.isAction ? <span className="new">{option.label}</span> : <b>{option.label}</b>}
              {option.hint && <small>{option.hint}</small>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function NewAppointmentDialog({
  catalog,
  date,
  initialProfessionalId,
  initialMinutes,
  onClose,
  onCreated,
}: {
  catalog: Catalog;
  date: string;
  initialProfessionalId?: string;
  initialMinutes?: number;
  onClose: () => void;
  onCreated: (appointment: Appointment, customer: Customer | null) => void;
}) {
  const [professionalId, setProfessionalId] = useState(initialProfessionalId ?? catalog.professionals[0]?.id ?? "");
  const offered = (id: string) =>
    catalog.services.filter((s) => s.active && catalog.assignments.get(id)?.includes(s.id));
  const services = offered(professionalId);
  const [serviceId, setServiceId] = useState(services[0]?.id ?? "");
  const [customer, setCustomer] = useState<PickedCustomer>(null);
  const [slot, setSlot] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const service = services.find((s) => s.id === serviceId);
  const professional = catalog.professionals.find((p) => p.id === professionalId);

  async function submit() {
    if (!slot || !service) return;
    setSubmitting(true);
    setError(null);
    try {
      let picked: Customer | null = customer?.kind === "existing" ? customer.customer : null;
      if (customer?.kind === "new") {
        // Digits-only-ish input is a phone, anything else a name.
        const isPhone = /^[+\d\s()-]{6,}$/.test(customer.text);
        picked = await api.post<Customer>("/customers", isPhone ? { phone: customer.text } : { name: customer.text });
        // Keep it if booking fails below, so a retry doesn't create it twice.
        setCustomer({ kind: "existing", customer: picked });
      }
      const created = await api.post<Appointment>("/appointments", {
        professionalId,
        serviceId,
        startTime: slot,
        customerId: picked?.id ?? null,
      });
      onCreated(created, picked);
    } catch (err) {
      setError(errorMessage(err, "No se pudo crear el turno."));
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      title="Nuevo turno"
      subtitle={longDate(date)}
      onClose={onClose}
      footer={
        <>
          <button type="button" disabled={!slot || !service || submitting} onClick={submit}>
            {submitting ? "Agendando…" : "Agendar turno"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
        </>
      }
    >
      {error && <p className="error-text">{error}</p>}

      <div className="field">
        <label htmlFor="appt-professional">Profesional</label>
        <select
          id="appt-professional"
          value={professionalId}
          onChange={(e) => {
            setProfessionalId(e.target.value);
            setServiceId(offered(e.target.value)[0]?.id ?? "");
            setSlot(null);
          }}
        >
          {catalog.professionals.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="appt-service">Servicio</label>
        <select
          id="appt-service"
          value={serviceId}
          disabled={services.length === 0}
          onChange={(e) => {
            setServiceId(e.target.value);
            setSlot(null);
          }}
        >
          {services.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} · {s.durationMinutes} min · {formatMoney(s.price)}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="appt-customer">Cliente</label>
        <CustomerCombo value={customer} onChange={setCustomer} />
        <span className="help">Dejalo vacío si es un cliente ocasional que pasó por el local.</span>
      </div>

      <div className="field">
        <label>Horario</label>
        <SlotPicker
          professionalId={professionalId}
          serviceId={serviceId}
          date={date}
          timezone={catalog.timezone}
          selected={slot}
          preferredMinutes={professionalId === initialProfessionalId ? initialMinutes : undefined}
          onSelect={setSlot}
        />
      </div>

      {slot && service ? (
        <div className="summary">
          <div>
            <b>
              {timeOf(slot)} a {toTime(toMinutes(timeOf(slot)) + service.durationMinutes)}
            </b>{" "}
            · {service.name}
            <br />
            <span className="muted">
              {professional?.name} ·{" "}
              {customer === null
                ? "cliente ocasional"
                : customer.kind === "new"
                  ? customer.text
                  : customerLabel(customer.customer)}
            </span>
          </div>
          <span className="price">{formatMoney(service.price)}</span>
        </div>
      ) : (
        <div className="summary empty">Elegí un horario para ver el resumen.</div>
      )}
    </Dialog>
  );
}

export function AppointmentDetailDialog({
  appointment,
  catalog,
  onClose,
  onChanged,
}: {
  appointment: Appointment;
  catalog: Catalog;
  onClose: () => void;
  onChanged: (message: string) => void;
}) {
  const [mode, setMode] = useState<"view" | "confirmCancel" | "reschedule">("view");
  const [newDate, setNewDate] = useState(appointment.startTime.slice(0, 10));
  const [slot, setSlot] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const service = catalog.services.find((s) => s.id === appointment.serviceId);
  const professional = catalog.professionals.find((p) => p.id === appointment.professionalId);
  const customer = catalog.customers.find((c) => c.id === appointment.customerId);
  const start = timeOf(appointment.startTime);
  const end = toTime(toMinutes(start) + appointment.durationMinutes);
  const editable = appointment.status === "CONFIRMED";

  async function run(action: () => Promise<unknown>, message: string, fallback: string) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged(message);
    } catch (err) {
      setError(errorMessage(err, fallback));
      setBusy(false);
    }
  }

  const setStatus = (status: AppointmentStatus) =>
    run(
      () => api.patch(`/appointments/${appointment.id}/status`, { status }),
      `Turno marcado como ${STATUS_LABEL[status].toLowerCase()}`,
      "No se pudo actualizar el turno.",
    );

  if (mode === "reschedule") {
    return (
      <Dialog
        title="Reprogramar turno"
        subtitle={`${service?.name ?? "Servicio"} con ${professional?.name ?? "profesional"}`}
        width={520}
        onClose={onClose}
        footer={
          <>
            <button
              type="button"
              disabled={!slot || busy}
              onClick={() =>
                run(
                  () => api.post(`/appointments/${appointment.id}/reschedule`, { startTime: slot }),
                  `Turno movido a ${longDate(newDate).toLowerCase()} ${slot ? timeOf(slot) : ""}`,
                  "No se pudo reprogramar el turno.",
                )
              }
            >
              {busy ? "Guardando…" : "Confirmar"}
            </button>
            <button type="button" className="secondary" onClick={() => setMode("view")}>
              Volver
            </button>
          </>
        }
      >
        {error && <p className="error-text">{error}</p>}
        <div className="field">
          <label htmlFor="resched-date">Nueva fecha</label>
          <input
            id="resched-date"
            type="date"
            value={newDate}
            onChange={(e) => {
              setNewDate(e.target.value);
              setSlot(null);
            }}
          />
        </div>
        <div className="field">
          <label>Horario</label>
          <SlotPicker
            professionalId={appointment.professionalId}
            serviceId={appointment.serviceId}
            date={newDate}
            timezone={catalog.timezone}
            selected={slot}
            onSelect={setSlot}
          />
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog
      title={service?.name ?? "Turno"}
      subtitle={longDate(appointment.startTime.slice(0, 10))}
      width={440}
      onClose={onClose}
      footer={
        editable &&
        (mode === "confirmCancel" ? (
          <>
            <span className="muted">¿Cancelar este turno?</span>
            <span className="spacer" />
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                run(
                  () => api.patch(`/appointments/${appointment.id}/status`, { status: "CANCELLED" }),
                  "Turno cancelado",
                  "No se pudo cancelar el turno.",
                )
              }
            >
              Sí, cancelar
            </button>
            <button type="button" className="secondary" onClick={() => setMode("view")}>
              Volver
            </button>
          </>
        ) : (
          <>
            <button type="button" className="secondary" disabled={busy} onClick={() => setMode("reschedule")}>
              Reprogramar
            </button>
            <span className="spacer" />
            <button type="button" className="ghost" disabled={busy} onClick={() => setMode("confirmCancel")}>
              Cancelar turno
            </button>
          </>
        ))
      }
    >
      {error && <p className="error-text">{error}</p>}
      <div>
        <AppointmentStatusBadge status={appointment.status} />
      </div>
      <dl className="kv">
        <dt>Horario</dt>
        <dd>
          {start} a {end}
        </dd>
        <dt>Profesional</dt>
        <dd>{professional?.name ?? "—"}</dd>
        <dt>Cliente</dt>
        <dd>{appointment.customerId ? customerLabel(customer) : "Sin cliente (ocasional)"}</dd>
        <dt>Precio</dt>
        <dd>{formatMoney(appointment.price)}</dd>
      </dl>
      {editable && (
        <div className="field">
          <label>Cambiar estado</label>
          <div className="statebar">
            <button type="button" className="secondary" disabled={busy} onClick={() => setStatus("COMPLETED")}>
              Completado
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setStatus("NO_SHOW")}>
              No asistió
            </button>
          </div>
        </div>
      )}
    </Dialog>
  );
}
