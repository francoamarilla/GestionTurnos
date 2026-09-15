import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { api, ApiError } from "../api/client";
import { formatMoney } from "../format";
import { ActiveBadge } from "../components/Badge";

interface ServiceResponse {
  id: string;
  name: string;
  description: string | null;
  durationMinutes: number;
  price: number;
  active: boolean;
}

interface ServiceFormValues {
  name: string;
  description: string;
  durationMinutes: number;
  price: number;
}

function ServiceForm({
  initial,
  onCancel,
  onSaved,
}: {
  initial: ServiceResponse | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ServiceFormValues>({
    defaultValues: {
      name: initial?.name ?? "",
      description: initial?.description ?? "",
      durationMinutes: initial?.durationMinutes ?? 30,
      price: initial?.price ?? 0,
    },
  });

  async function onSubmit(values: ServiceFormValues) {
    const body = {
      name: values.name,
      description: values.description || null,
      durationMinutes: Number(values.durationMinutes),
      price: Number(values.price),
    };
    try {
      if (initial) {
        await api.patch(`/services/${initial.id}`, body);
      } else {
        await api.post("/services", body);
      }
      onSaved();
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "No se pudo guardar el servicio.";
      setError("root", { message });
    }
  }

  return (
    <form className="card" onSubmit={handleSubmit(onSubmit)} noValidate>
      <h2>{initial ? "Editar servicio" : "Nuevo servicio"}</h2>

      <label className="field">
        <span>Nombre</span>
        <input {...register("name", { required: "El nombre es obligatorio" })} />
        {errors.name && <span className="error-text">{errors.name.message}</span>}
      </label>

      <label className="field">
        <span>Descripción</span>
        <input {...register("description")} />
      </label>

      <label className="field">
        <span>Duración (minutos)</span>
        <input
          type="number"
          min={1}
          {...register("durationMinutes", { required: true, min: 1, valueAsNumber: true })}
        />
        {errors.durationMinutes && <span className="error-text">Tiene que ser mayor a 0.</span>}
      </label>

      <label className="field">
        <span>Precio</span>
        <input type="number" min={0} step="0.01" {...register("price", { required: true, min: 0, valueAsNumber: true })} />
        {errors.price && <span className="error-text">Tiene que ser 0 o mayor.</span>}
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

export default function ServicesPage() {
  const [services, setServices] = useState<ServiceResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ServiceResponse | "new" | null>(null);

  function reload() {
    api
      .get<ServiceResponse[]>("/services")
      .then((result) => {
        setError(null);
        setServices(result);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los servicios."));
  }

  useEffect(reload, []);

  async function handleDeactivate(service: ServiceResponse) {
    if (!confirm(`¿Dar de baja "${service.name}"?`)) {
      return;
    }
    try {
      await api.delete(`/services/${service.id}`);
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo dar de baja el servicio.");
    }
  }

  if (editing) {
    return (
      <div>
        <h1>Servicios</h1>
        <ServiceForm
          initial={editing === "new" ? null : editing}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            reload();
          }}
        />
      </div>
    );
  }

  return (
    <div>
      <h1>Servicios</h1>
      {error && <p className="error-text">{error}</p>}
      <div className="form-actions">
        <button type="button" onClick={() => setEditing("new")}>
          Nuevo servicio
        </button>
      </div>

      {services === null ? (
        <p>Cargando...</p>
      ) : services.length === 0 ? (
        <p>Todavía no hay servicios cargados.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Nombre</th>
              <th>Duración</th>
              <th>Precio</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {services.map((service) => (
              <tr key={service.id}>
                <td>{service.name}</td>
                <td>{service.durationMinutes} min</td>
                <td>{formatMoney(service.price)}</td>
                <td>
                  <ActiveBadge active={service.active} />
                </td>
                <td>
                  <div className="form-actions">
                    <button type="button" className="secondary" onClick={() => setEditing(service)}>
                      Editar
                    </button>
                    {service.active && (
                      <button type="button" className="secondary" onClick={() => handleDeactivate(service)}>
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
