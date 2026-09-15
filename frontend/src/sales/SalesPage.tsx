import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { formatMoney } from "../format";

interface AppointmentResponse {
  id: string;
  professionalId: string;
  serviceId: string;
  customerId: string | null;
  startTime: string;
  price: number;
}

interface NamedEntity {
  id: string;
  name: string;
}

interface ServiceOption {
  id: string;
  name: string;
  price: number;
}

interface CustomerOption {
  id: string;
  name: string | null;
  phone: string | null;
}

interface Page<T> {
  content: T[];
}

function toIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function customerLabel(customer: CustomerOption | undefined): string {
  if (!customer) return "Sin cliente";
  return customer.name ?? customer.phone ?? "Sin cliente";
}

const NEW_CUSTOMER = "__new__";

function NewSaleForm({
  professionals,
  services,
  customers,
  onCancel,
  onSaved,
}: {
  professionals: NamedEntity[];
  services: ServiceOption[];
  customers: CustomerOption[];
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [professionalId, setProfessionalId] = useState(professionals[0]?.id ?? "");
  const [serviceId, setServiceId] = useState(services[0]?.id ?? "");
  const [price, setPrice] = useState(services[0]?.price ?? 0);
  const [customerChoice, setCustomerChoice] = useState("");
  const [customerName, setCustomerName] = useState("");
  const [customerPhone, setCustomerPhone] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function handleServiceChange(id: string) {
    setServiceId(id);
    const service = services.find((s) => s.id === id);
    if (service) {
      setPrice(service.price);
    }
  }

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      await api.post("/sales", {
        professionalId,
        serviceId,
        price,
        customerId: customerChoice && customerChoice !== NEW_CUSTOMER ? customerChoice : null,
        customerName: customerChoice === NEW_CUSTOMER ? customerName || null : null,
        customerPhone: customerChoice === NEW_CUSTOMER ? customerPhone || null : null,
      });
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo registrar la venta.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="card" style={{ maxWidth: 480 }}>
      <h2>Nueva venta</h2>
      {error && <p className="error-text">{error}</p>}

      <label className="field">
        <span>Profesional</span>
        <select value={professionalId} onChange={(e) => setProfessionalId(e.target.value)}>
          {professionals.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>Servicio</span>
        <select value={serviceId} onChange={(e) => handleServiceChange(e.target.value)}>
          {services.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>Precio</span>
        <input type="number" min={0} step="0.01" value={price} onChange={(e) => setPrice(Number(e.target.value))} />
      </label>

      <label className="field">
        <span>Cliente</span>
        <select value={customerChoice} onChange={(e) => setCustomerChoice(e.target.value)}>
          <option value="">Sin cliente (anónimo)</option>
          <option value={NEW_CUSTOMER}>Cliente nuevo...</option>
          {customers.map((c) => (
            <option key={c.id} value={c.id}>
              {customerLabel(c)}
            </option>
          ))}
        </select>
      </label>

      {customerChoice === NEW_CUSTOMER && (
        <>
          <label className="field">
            <span>Nombre del cliente nuevo</span>
            <input value={customerName} onChange={(e) => setCustomerName(e.target.value)} />
          </label>
          <label className="field">
            <span>Teléfono del cliente nuevo</span>
            <input value={customerPhone} onChange={(e) => setCustomerPhone(e.target.value)} />
          </label>
        </>
      )}

      <div className="form-actions">
        <button type="button" onClick={submit} disabled={!professionalId || !serviceId || submitting}>
          {submitting ? "Guardando..." : "Registrar venta"}
        </button>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

export default function SalesPage() {
  const today = toIsoDate(new Date());
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [professionalFilter, setProfessionalFilter] = useState("");
  const [sales, setSales] = useState<AppointmentResponse[] | null>(null);
  // All professionals (active + inactive) - a sale's professional shouldn't
  // read as "Profesional" forever just because they were later deactivated.
  const [professionals, setProfessionals] = useState<NamedEntity[]>([]);
  // Active-only subset for the new-sale form, which can't book with someone
  // deactivated.
  const [activeProfessionals, setActiveProfessionals] = useState<NamedEntity[]>([]);
  const [services, setServices] = useState<ServiceOption[]>([]);
  const [customers, setCustomers] = useState<CustomerOption[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  function reload() {
    const filter = professionalFilter ? `&professionalId=${professionalFilter}` : "";
    api
      .get<AppointmentResponse[]>(`/sales?from=${from}&to=${to}${filter}`)
      .then((result) => {
        setError(null);
        setSales(result.sort((a, b) => b.startTime.localeCompare(a.startTime)));
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar las ventas."));
  }

  useEffect(reload, [from, to, professionalFilter]);

  function reloadCustomers() {
    // ponytail: same size=200 lookup ceiling used elsewhere in this app.
    return api
      .get<Page<CustomerOption>>("/customers?size=200")
      .then((customersPage) => setCustomers(customersPage.content));
  }

  useEffect(() => {
    Promise.all([
      api.get<Page<NamedEntity>>("/professionals?size=100"),
      api.get<Page<NamedEntity>>("/professionals?active=true&size=100"),
      api.get<ServiceOption[]>("/services"),
      reloadCustomers(),
    ])
      .then(([professionalsPage, activeProfessionalsPage, serviceList]) => {
        setProfessionals(professionalsPage.content);
        setActiveProfessionals(activeProfessionalsPage.content);
        setServices(serviceList);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los datos."));
  }, []);

  if (showForm) {
    return (
      <div>
        <h1>Ventas</h1>
        <NewSaleForm
          professionals={activeProfessionals}
          services={services}
          customers={customers}
          onCancel={() => setShowForm(false)}
          onSaved={() => {
            setShowForm(false);
            reload();
            reloadCustomers();
          }}
        />
      </div>
    );
  }

  const total = sales?.reduce((sum, sale) => sum + sale.price, 0) ?? 0;
  const professionalName = (id: string) => professionals.find((p) => p.id === id)?.name ?? "Profesional";
  const serviceName = (id: string) => services.find((s) => s.id === id)?.name ?? "Servicio";
  const customer = (id: string | null) => customers.find((c) => c.id === id);

  return (
    <div>
      <h1>Ventas</h1>
      {error && <p className="error-text">{error}</p>}

      <div className="form-actions">
        <button type="button" onClick={() => setShowForm(true)}>
          Nueva venta
        </button>
      </div>

      <div className="form-actions">
        <label className="field">
          <span>Desde</span>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="field">
          <span>Hasta</span>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="field">
          <span>Profesional</span>
          <select value={professionalFilter} onChange={(e) => setProfessionalFilter(e.target.value)}>
            <option value="">Todos</option>
            {professionals.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {sales === null ? (
        <p>Cargando...</p>
      ) : sales.length === 0 ? (
        <p>No hay ventas en ese período.</p>
      ) : (
        <>
          <table>
            <thead>
              <tr>
                <th>Fecha</th>
                <th>Profesional</th>
                <th>Servicio</th>
                <th>Cliente</th>
                <th>Precio</th>
              </tr>
            </thead>
            <tbody>
              {sales.map((sale) => (
                <tr key={sale.id}>
                  <td>
                    {sale.startTime.slice(0, 10)} {sale.startTime.slice(11, 16)}
                  </td>
                  <td>{professionalName(sale.professionalId)}</td>
                  <td>{serviceName(sale.serviceId)}</td>
                  <td>{customerLabel(customer(sale.customerId))}</td>
                  <td>{formatMoney(sale.price)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>
            <strong>Total: {formatMoney(total)}</strong>
          </p>
        </>
      )}
    </div>
  );
}
