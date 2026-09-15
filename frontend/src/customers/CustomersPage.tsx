import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { api, ApiError } from "../api/client";
import { formatMoney } from "../format";

interface CustomerResponse {
  id: string;
  phone: string | null;
  name: string | null;
  internalNotes: string | null;
  active: boolean;
}

interface Page<T> {
  content: T[];
  number: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

interface AppointmentResponse {
  id: string;
  professionalId: string;
  serviceId: string;
  startTime: string;
  price: number;
  status: "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";
}

interface NamedEntity {
  id: string;
  name: string;
}

interface CustomerFormValues {
  name: string;
  phone: string;
  internalNotes: string;
}

function CustomerForm({
  initial,
  onCancel,
  onSaved,
}: {
  initial: CustomerResponse | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CustomerFormValues>({
    defaultValues: {
      name: initial?.name ?? "",
      phone: initial?.phone ?? "",
      internalNotes: initial?.internalNotes ?? "",
    },
  });

  async function onSubmit(values: CustomerFormValues) {
    const body = {
      name: values.name || null,
      phone: values.phone || null,
      internalNotes: values.internalNotes || null,
    };
    try {
      if (initial) {
        await api.patch(`/customers/${initial.id}`, body);
      } else {
        await api.post("/customers", body);
      }
      onSaved();
    } catch (err) {
      setError("root", { message: err instanceof ApiError ? err.message : "No se pudo guardar el cliente." });
    }
  }

  return (
    <form className="card" onSubmit={handleSubmit(onSubmit)} noValidate>
      <h2>{initial ? "Editar cliente" : "Nuevo cliente"}</h2>

      <label className="field">
        <span>Nombre</span>
        <input {...register("name")} />
      </label>

      <label className="field">
        <span>Teléfono</span>
        <input {...register("phone")} />
      </label>

      <label className="field">
        <span>Notas internas</span>
        <input {...register("internalNotes")} />
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

function CustomerDetail({ customer, onBack }: { customer: CustomerResponse; onBack: () => void }) {
  const [appointments, setAppointments] = useState<AppointmentResponse[] | null>(null);
  const [professionals, setProfessionals] = useState<Map<string, string>>(new Map());
  const [services, setServices] = useState<Map<string, string>>(new Map());
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api.get<AppointmentResponse[]>(`/customers/${customer.id}/appointments`),
      api.get<{ content: NamedEntity[] }>("/professionals?size=200"),
      api.get<NamedEntity[]>("/services"),
    ])
      .then(([appts, professionalsPage, serviceList]) => {
        setAppointments(appts.sort((a, b) => b.startTime.localeCompare(a.startTime)));
        setProfessionals(new Map(professionalsPage.content.map((p) => [p.id, p.name])));
        setServices(new Map(serviceList.map((s) => [s.id, s.name])));
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar el historial."));
  }, [customer.id]);

  return (
    <div className="card" style={{ maxWidth: 560 }}>
      <h2>{customer.name ?? "Cliente sin nombre"}</h2>
      <p>
        Teléfono: {customer.phone ?? "—"}
        {customer.internalNotes && (
          <>
            <br />
            Notas: {customer.internalNotes}
          </>
        )}
      </p>

      <h3>Historial de turnos</h3>
      {error && <p className="error-text">{error}</p>}
      {!appointments ? (
        <p>Cargando...</p>
      ) : appointments.length === 0 ? (
        <p>Sin turnos registrados.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Fecha</th>
              <th>Servicio</th>
              <th>Profesional</th>
              <th>Estado</th>
              <th>Precio</th>
            </tr>
          </thead>
          <tbody>
            {appointments.map((appt) => (
              <tr key={appt.id}>
                <td>
                  {appt.startTime.slice(0, 10)} {appt.startTime.slice(11, 16)}
                </td>
                <td>{services.get(appt.serviceId) ?? "Servicio"}</td>
                <td>{professionals.get(appt.professionalId) ?? "Profesional"}</td>
                <td>{appt.status}</td>
                <td>{formatMoney(appt.price)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="form-actions">
        <button type="button" className="secondary" onClick={onBack}>
          Volver
        </button>
      </div>
    </div>
  );
}

type ViewState =
  | { mode: "list" }
  | { mode: "form"; customer: CustomerResponse | null }
  | { mode: "detail"; customer: CustomerResponse };

export default function CustomersPage() {
  const [page, setPage] = useState<Page<CustomerResponse> | null>(null);
  const [pageNumber, setPageNumber] = useState(0);
  const [search, setSearch] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<ViewState>({ mode: "list" });

  function reload() {
    const query = search ? `&search=${encodeURIComponent(search)}` : "";
    api
      .get<Page<CustomerResponse>>(`/customers?page=${pageNumber}${query}`)
      .then(setPage)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los clientes."));
  }

  useEffect(reload, [pageNumber, search]);

  async function handleDeactivate(customer: CustomerResponse) {
    if (!confirm(`¿Dar de baja a "${customer.name ?? customer.phone ?? "este cliente"}"?`)) {
      return;
    }
    try {
      await api.delete(`/customers/${customer.id}`);
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo dar de baja al cliente.");
    }
  }

  if (view.mode === "form") {
    return (
      <div>
        <h1>Clientes</h1>
        <CustomerForm
          initial={view.customer}
          onCancel={() => setView({ mode: "list" })}
          onSaved={() => {
            setView({ mode: "list" });
            reload();
          }}
        />
      </div>
    );
  }

  if (view.mode === "detail") {
    return (
      <div>
        <h1>Clientes</h1>
        <CustomerDetail customer={view.customer} onBack={() => setView({ mode: "list" })} />
      </div>
    );
  }

  return (
    <div>
      <h1>Clientes</h1>
      {error && <p className="error-text">{error}</p>}

      <div className="form-actions">
        <button type="button" onClick={() => setView({ mode: "form", customer: null })}>
          Nuevo cliente
        </button>
      </div>

      <form
        className="form-actions"
        onSubmit={(e) => {
          e.preventDefault();
          setPageNumber(0);
          setSearch(searchInput);
        }}
      >
        <input
          className="search-input"
          placeholder="Buscar por nombre o teléfono"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />
        <button type="submit">Buscar</button>
      </form>

      {page === null ? (
        <p>Cargando...</p>
      ) : page.content.length === 0 ? (
        <p>No se encontraron clientes.</p>
      ) : (
        <>
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
              {page.content.map((customer) => (
                <tr key={customer.id}>
                  <td>{customer.name ?? "—"}</td>
                  <td>{customer.phone ?? "—"}</td>
                  <td>{customer.active ? "Activo" : "Inactivo"}</td>
                  <td>
                    <div className="form-actions">
                      <button type="button" className="secondary" onClick={() => setView({ mode: "detail", customer })}>
                        Ver ficha
                      </button>
                      <button type="button" className="secondary" onClick={() => setView({ mode: "form", customer })}>
                        Editar
                      </button>
                      {customer.active && (
                        <button type="button" className="secondary" onClick={() => handleDeactivate(customer)}>
                          Dar de baja
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="form-actions">
            <button type="button" className="secondary" disabled={page.first} onClick={() => setPageNumber((n) => n - 1)}>
              Anterior
            </button>
            <span>
              Página {page.number + 1} de {Math.max(page.totalPages, 1)}
            </span>
            <button type="button" className="secondary" disabled={page.last} onClick={() => setPageNumber((n) => n + 1)}>
              Siguiente
            </button>
          </div>
        </>
      )}
    </div>
  );
}
