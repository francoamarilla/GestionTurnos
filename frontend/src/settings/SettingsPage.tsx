import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { api, ApiError } from "../api/client";

type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

interface DaySchedule {
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
  schedule: DaySchedule[];
}

interface ClosureResponse {
  id: string;
  date: string;
  reason: string | null;
}

interface WhatsAppIntegrationResponse {
  id: string;
  wabaId: string;
  phoneNumber: string;
  verificationStatus: string;
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

interface EstablishmentFormValues {
  businessName: string;
  contactPhone: string;
  cancellationWindowHours: number;
}

function EstablishmentForm({ establishment, onSaved }: { establishment: EstablishmentResponse; onSaved: () => void }) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<EstablishmentFormValues>({
    defaultValues: {
      businessName: establishment.businessName,
      contactPhone: establishment.contactPhone ?? "",
      cancellationWindowHours: establishment.cancellationWindowHours,
    },
  });

  async function onSubmit(values: EstablishmentFormValues) {
    try {
      await api.patch("/establishment", {
        businessName: values.businessName,
        contactPhone: values.contactPhone || null,
        cancellationWindowHours: Number(values.cancellationWindowHours),
      });
      onSaved();
    } catch (err) {
      setError("root", { message: err instanceof ApiError ? err.message : "No se pudo guardar." });
    }
  }

  return (
    <form className="card" onSubmit={handleSubmit(onSubmit)} noValidate>
      <h2>Establecimiento</h2>

      <label className="field">
        <span>Nombre del negocio</span>
        <input {...register("businessName", { required: "El nombre es obligatorio" })} />
        {errors.businessName && <span className="error-text">{errors.businessName.message}</span>}
      </label>

      <label className="field">
        <span>Teléfono de contacto</span>
        <input {...register("contactPhone")} />
      </label>

      <label className="field">
        <span>Ventana de cancelación (horas)</span>
        <input
          type="number"
          min={0}
          {...register("cancellationWindowHours", { required: true, min: 0, valueAsNumber: true })}
        />
      </label>

      <label className="field">
        <span>Zona horaria</span>
        <input value={establishment.timezone} disabled />
      </label>

      {errors.root && <p className="error-text">{errors.root.message}</p>}

      <div className="form-actions">
        <button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Guardando..." : "Guardar"}
        </button>
      </div>
    </form>
  );
}

function ScheduleEditor({ schedule: initial, onSaved }: { schedule: DaySchedule[]; onSaved: () => void }) {
  const [schedule, setSchedule] = useState<DaySchedule[]>(initial);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function updateDay(day: DayOfWeek, patch: Partial<DaySchedule>) {
    setSchedule((current) => current.map((d) => (d.dayOfWeek === day ? { ...d, ...patch } : d)));
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const payload = schedule.map((d) => ({
        ...d,
        openTime: d.closed ? null : toApiTime(d.openTime || "09:00"),
        closeTime: d.closed ? null : toApiTime(d.closeTime || "18:00"),
      }));
      await api.patch("/establishment", { schedule: payload });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo guardar el horario.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card">
      <h2>Horario general</h2>
      {error && <p className="error-text">{error}</p>}
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
      <div className="form-actions">
        <button type="button" onClick={save} disabled={saving}>
          {saving ? "Guardando..." : "Guardar"}
        </button>
      </div>
    </div>
  );
}

function ClosuresCard() {
  const [closures, setClosures] = useState<ClosureResponse[] | null>(null);
  const [date, setDate] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  function reload() {
    api
      .get<ClosureResponse[]>("/establishment/closures")
      .then((result) => setClosures(result.sort((a, b) => a.date.localeCompare(b.date))))
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los feriados."));
  }

  useEffect(reload, []);

  async function addClosure() {
    if (!date) return;
    setError(null);
    try {
      await api.post("/establishment/closures", { date, reason: reason || null });
      setDate("");
      setReason("");
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo agregar el feriado.");
    }
  }

  async function removeClosure(id: string) {
    if (!confirm("¿Eliminar este feriado?")) return;
    try {
      await api.delete(`/establishment/closures/${id}`);
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo eliminar el feriado.");
    }
  }

  return (
    <div className="card">
      <h2>Feriados</h2>
      {error && <p className="error-text">{error}</p>}

      <div className="form-actions">
        <label className="field">
          <span>Fecha</span>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        <label className="field">
          <span>Motivo</span>
          <input value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <button type="button" onClick={addClosure} disabled={!date}>
          Agregar
        </button>
      </div>

      {closures === null ? (
        <p>Cargando...</p>
      ) : closures.length === 0 ? (
        <p>Sin feriados cargados.</p>
      ) : (
        <ul>
          {closures.map((c) => (
            <li key={c.id}>
              {c.date} {c.reason ? `— ${c.reason}` : ""}{" "}
              <button type="button" className="secondary" onClick={() => removeClosure(c.id)}>
                Eliminar
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const WHATSAPP_STATUS_LABEL: Record<string, string> = {
  PENDING: "Pendiente de verificación",
  VERIFIED: "Verificado",
  FAILED: "Falló la verificación",
};

function WhatsAppCard() {
  const [integration, setIntegration] = useState<WhatsAppIntegrationResponse | null | "none">(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<WhatsAppIntegrationResponse>("/establishment/whatsapp-integration")
      .then(setIntegration)
      .catch((err) => {
        if (err instanceof ApiError && err.status === 404) {
          setIntegration("none");
        } else {
          setError(err instanceof ApiError ? err.message : "No se pudo cargar la integración de WhatsApp.");
        }
      });
  }, []);

  return (
    <div className="card">
      <h2>Integración de WhatsApp</h2>
      {error && <p className="error-text">{error}</p>}
      {integration === null ? (
        <p>Cargando...</p>
      ) : integration === "none" ? (
        <p>No configurada.</p>
      ) : (
        <>
          <p>Número: {integration.phoneNumber}</p>
          <p>Estado: {WHATSAPP_STATUS_LABEL[integration.verificationStatus] ?? integration.verificationStatus}</p>
        </>
      )}
    </div>
  );
}

export default function SettingsPage() {
  const [establishment, setEstablishment] = useState<EstablishmentResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  function reload() {
    api
      .get<EstablishmentResponse>("/establishment")
      .then(setEstablishment)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar el establecimiento."));
  }

  useEffect(reload, []);

  return (
    <div>
      <h1>Configuración</h1>
      {error && <p className="error-text">{error}</p>}

      {!establishment ? (
        <p>Cargando...</p>
      ) : (
        <div className="dashboard-grid">
          <EstablishmentForm establishment={establishment} onSaved={reload} />
          <ScheduleEditor schedule={establishment.schedule} onSaved={reload} />
          <ClosuresCard />
          <WhatsAppCard />
        </div>
      )}
    </div>
  );
}
