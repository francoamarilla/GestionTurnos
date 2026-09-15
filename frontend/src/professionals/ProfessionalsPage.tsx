import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { api, ApiError } from "../api/client";
import { ActiveBadge } from "../components/Badge";

interface ProfessionalResponse {
  id: string;
  name: string;
  contactPhone: string | null;
  active: boolean;
}

interface Page<T> {
  content: T[];
}

interface ServiceOption {
  id: string;
  name: string;
}

type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

interface DaySchedule {
  dayOfWeek: DayOfWeek;
  openTime: string | null;
  closeTime: string | null;
  closed: boolean;
}

const DAYS: { key: DayOfWeek; label: string }[] = [
  { key: "MONDAY", label: "Lunes" },
  { key: "TUESDAY", label: "Martes" },
  { key: "WEDNESDAY", label: "Miércoles" },
  { key: "THURSDAY", label: "Jueves" },
  { key: "FRIDAY", label: "Viernes" },
  { key: "SATURDAY", label: "Sábado" },
  { key: "SUNDAY", label: "Domingo" },
];

function toTimeInput(value: string | null): string {
  return value ? value.slice(0, 5) : "";
}

function toApiTime(value: string): string {
  return value.length === 5 ? `${value}:00` : value;
}

interface ProfessionalFormValues {
  name: string;
  contactPhone: string;
}

function ProfessionalForm({
  initial,
  onCancel,
  onSaved,
}: {
  initial: ProfessionalResponse | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProfessionalFormValues>({
    defaultValues: { name: initial?.name ?? "", contactPhone: initial?.contactPhone ?? "" },
  });

  async function onSubmit(values: ProfessionalFormValues) {
    const body = { name: values.name, contactPhone: values.contactPhone || null };
    try {
      if (initial) {
        await api.patch(`/professionals/${initial.id}`, body);
      } else {
        await api.post("/professionals", body);
      }
      onSaved();
    } catch (err) {
      setError("root", { message: err instanceof ApiError ? err.message : "No se pudo guardar el profesional." });
    }
  }

  return (
    <form className="card" onSubmit={handleSubmit(onSubmit)} noValidate>
      <h2>{initial ? "Editar profesional" : "Nuevo profesional"}</h2>

      <label className="field">
        <span>Nombre</span>
        <input {...register("name", { required: "El nombre es obligatorio" })} />
        {errors.name && <span className="error-text">{errors.name.message}</span>}
      </label>

      <label className="field">
        <span>Teléfono de contacto</span>
        <input {...register("contactPhone")} />
      </label>

      {errors.root && <p className="error-text">{errors.root.message}</p>}

      <div className="form-actions">
        <button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Guardando..." : "Guardar"}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </form>
  );
}

function ScheduleEditor({ professional, onCancel }: { professional: ProfessionalResponse; onCancel: () => void }) {
  const [schedule, setSchedule] = useState<DaySchedule[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<DaySchedule[]>(`/professionals/${professional.id}/schedule`)
      .then(setSchedule)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar el horario."));
  }, [professional.id]);

  function updateDay(day: DayOfWeek, patch: Partial<DaySchedule>) {
    setSchedule((current) => current && current.map((d) => (d.dayOfWeek === day ? { ...d, ...patch } : d)));
  }

  async function save() {
    if (!schedule) return;
    setSaving(true);
    setError(null);
    try {
      const payload = schedule.map((d) => ({
        ...d,
        openTime: d.closed ? null : toApiTime(d.openTime || "09:00"),
        closeTime: d.closed ? null : toApiTime(d.closeTime || "18:00"),
      }));
      await api.patch(`/professionals/${professional.id}/schedule`, payload);
      onCancel();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo guardar el horario.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card" style={{ maxWidth: 480 }}>
      <h2>Horario de {professional.name}</h2>
      {error && <p className="error-text">{error}</p>}
      {!schedule ? (
        <p>Cargando...</p>
      ) : (
        <table>
          <tbody>
            {DAYS.map(({ key, label }) => {
              const day = schedule.find((d) => d.dayOfWeek === key);
              if (!day) return null;
              return (
                <tr key={key}>
                  <td>{label}</td>
                  <td>
                    <label>
                      <input
                        type="checkbox"
                        checked={day.closed}
                        onChange={(e) => updateDay(key, { closed: e.target.checked })}
                      />{" "}
                      Cerrado
                    </label>
                  </td>
                  <td>
                    {!day.closed && (
                      <input
                        type="time"
                        value={toTimeInput(day.openTime)}
                        onChange={(e) => updateDay(key, { openTime: e.target.value })}
                      />
                    )}
                  </td>
                  <td>
                    {!day.closed && (
                      <input
                        type="time"
                        value={toTimeInput(day.closeTime)}
                        onChange={(e) => updateDay(key, { closeTime: e.target.value })}
                      />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <div className="form-actions">
        <button type="button" onClick={save} disabled={saving || !schedule}>
          {saving ? "Guardando..." : "Guardar"}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

function ServicesAssignment({ professional, onCancel }: { professional: ProfessionalResponse; onCancel: () => void }) {
  const [allServices, setAllServices] = useState<ServiceOption[] | null>(null);
  const [assigned, setAssigned] = useState<Set<string> | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api.get<ServiceOption[]>("/services"),
      api.get<string[]>(`/professionals/${professional.id}/services`),
    ])
      .then(([services, assignedIds]) => {
        setAllServices(services);
        setAssigned(new Set(assignedIds));
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los servicios."));
  }, [professional.id]);

  function toggle(id: string) {
    setAssigned((current) => {
      if (!current) return current;
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  async function save() {
    if (!assigned) return;
    setSaving(true);
    setError(null);
    try {
      await api.put(`/professionals/${professional.id}/services`, Array.from(assigned));
      onCancel();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudieron guardar los servicios.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card" style={{ maxWidth: 420 }}>
      <h2>Servicios de {professional.name}</h2>
      {error && <p className="error-text">{error}</p>}
      {!allServices || !assigned ? (
        <p>Cargando...</p>
      ) : allServices.length === 0 ? (
        <p>Todavía no hay servicios cargados en el catálogo.</p>
      ) : (
        <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "flex", flexDirection: "column", gap: 8 }}>
          {allServices.map((service) => (
            <li key={service.id}>
              <label>
                <input type="checkbox" checked={assigned.has(service.id)} onChange={() => toggle(service.id)} />{" "}
                {service.name}
              </label>
            </li>
          ))}
        </ul>
      )}
      <div className="form-actions">
        <button type="button" onClick={save} disabled={saving || !assigned}>
          {saving ? "Guardando..." : "Guardar"}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

type ViewState =
  | { mode: "list" }
  | { mode: "form"; professional: ProfessionalResponse | null }
  | { mode: "schedule"; professional: ProfessionalResponse }
  | { mode: "services"; professional: ProfessionalResponse };

export default function ProfessionalsPage() {
  const [professionals, setProfessionals] = useState<ProfessionalResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<ViewState>({ mode: "list" });

  function reload() {
    // ponytail: one page of up to 100 professionals - plenty for any
    // tenant this MVP realistically has; add real pagination controls
    // if that ever stops being true.
    api
      .get<Page<ProfessionalResponse>>("/professionals?size=100")
      .then((page) => {
        setError(null);
        setProfessionals(page.content);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los profesionales."));
  }

  useEffect(reload, []);

  async function handleDeactivate(professional: ProfessionalResponse) {
    if (!confirm(`¿Dar de baja a "${professional.name}"?`)) {
      return;
    }
    try {
      await api.delete(`/professionals/${professional.id}`);
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo dar de baja al profesional.");
    }
  }

  if (view.mode === "form") {
    return (
      <div>
        <h1>Profesionales</h1>
        <ProfessionalForm
          initial={view.professional}
          onCancel={() => setView({ mode: "list" })}
          onSaved={() => {
            setView({ mode: "list" });
            reload();
          }}
        />
      </div>
    );
  }

  if (view.mode === "schedule") {
    return (
      <div>
        <h1>Profesionales</h1>
        <ScheduleEditor professional={view.professional} onCancel={() => setView({ mode: "list" })} />
      </div>
    );
  }

  if (view.mode === "services") {
    return (
      <div>
        <h1>Profesionales</h1>
        <ServicesAssignment professional={view.professional} onCancel={() => setView({ mode: "list" })} />
      </div>
    );
  }

  return (
    <div>
      <h1>Profesionales</h1>
      {error && <p className="error-text">{error}</p>}
      <div className="form-actions">
        <button type="button" onClick={() => setView({ mode: "form", professional: null })}>
          Nuevo profesional
        </button>
      </div>

      {professionals === null ? (
        <p>Cargando...</p>
      ) : professionals.length === 0 ? (
        <p>Todavía no hay profesionales cargados.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Nombre</th>
              <th>Teléfono</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {professionals.map((professional) => (
              <tr key={professional.id}>
                <td>{professional.name}</td>
                <td>{professional.contactPhone ?? "—"}</td>
                <td>
                  <ActiveBadge active={professional.active} />
                </td>
                <td>
                  <div className="form-actions">
                    <button type="button" className="secondary" onClick={() => setView({ mode: "form", professional })}>
                      Editar
                    </button>
                    <button type="button" className="secondary" onClick={() => setView({ mode: "schedule", professional })}>
                      Horario
                    </button>
                    <button type="button" className="secondary" onClick={() => setView({ mode: "services", professional })}>
                      Servicios
                    </button>
                    {professional.active && (
                      <button type="button" className="secondary" onClick={() => handleDeactivate(professional)}>
                        Dar de baja
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
