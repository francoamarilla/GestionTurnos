import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useLocation, useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import { customerLabel, nowInZone, type Appointment, type Customer } from "../agenda/model";
import { AppointmentStatusBadge, Badge } from "../components/Badge";
import { Dialog } from "../components/Dialog";
import {
  Avatar,
  DeactivateDialog,
  IdCell,
  Pager,
  RowMenu,
  SearchBox,
  TableEmpty,
  WarnBar,
} from "../components/TableKit";
import { useToast } from "../components/Toast";
import { formatMoney } from "../format";

const PAGE_SIZE = 10;
const DORMANT_DAYS = 60;
const LOYAL_VISITS = 15;

interface Page<T> {
  content: T[];
  number: number;
  totalPages: number;
  totalElements: number;
}

interface Stats {
  visits: number; // completed appointments
  spent: number;
  noShows: number;
  lastVisit: string | null; // ISO date of the last completed appointment
  history: Appointment[]; // newest first
}

// ponytail: per-customer history call for the visible page only - the API
// has no aggregate yet (see ENDPOINTS-PENDIENTES.md #2), so these columns
// can't be sorted or filtered across the whole base.
async function loadStats(id: string): Promise<Stats> {
  const history = (await api.get<Appointment[]>(`/customers/${id}/appointments`)).sort((a, b) =>
    b.startTime.localeCompare(a.startTime),
  );
  const completed = history.filter((a) => a.status === "COMPLETED");
  return {
    visits: completed.length,
    spent: completed.reduce((sum, a) => sum + Number(a.price), 0),
    noShows: history.filter((a) => a.status === "NO_SHOW").length,
    lastVisit: completed[0]?.startTime.slice(0, 10) ?? null,
    history,
  };
}

function daysBetween(fromIso: string, toIso: string): number {
  return Math.round((Date.parse(`${toIso}T12:00:00`) - Date.parse(`${fromIso}T12:00:00`)) / 86_400_000);
}

function ago(days: number): string {
  if (days <= 0) return "hoy";
  if (days === 1) return "ayer";
  if (days < 14) return `hace ${days} días`;
  if (days < 60) return `hace ${Math.round(days / 7)} semanas`;
  if (days < 365) return `hace ${Math.round(days / 30)} meses`;
  return `hace ${Math.round(days / 365)} ${Math.round(days / 365) === 1 ? "año" : "años"}`;
}

const avatarColor = (stats: Stats | undefined) =>
  stats && stats.visits >= LOYAL_VISITS ? "var(--brick)" : "var(--slate)";

function whatsAppUrl(phone: string): string {
  return `https://wa.me/${phone.replace(/\D/g, "")}`;
}

interface FormValues {
  name: string;
  phone: string;
  internalNotes: string;
}

function CustomerDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: Customer | null;
  onClose: () => void;
  onSaved: (label: string) => void;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    defaultValues: {
      name: initial?.name ?? "",
      phone: initial?.phone ?? "",
      internalNotes: initial?.internalNotes ?? "",
    },
  });

  async function onSubmit(values: FormValues) {
    const body = {
      name: values.name.trim() || null,
      phone: values.phone.trim() || null,
      internalNotes: values.internalNotes.trim() || null,
    };
    if (!body.name && !body.phone) {
      setError("root", { message: "Cargá al menos el nombre o el teléfono." });
      return;
    }
    try {
      if (initial) await api.patch(`/customers/${initial.id}`, body);
      else await api.post("/customers", body);
      onSaved(body.name ?? body.phone ?? "Cliente");
    } catch (err) {
      setError("root", { message: err instanceof ApiError ? err.message : "No se pudo guardar el cliente." });
    }
  }

  return (
    <Dialog
      title={initial ? "Editar cliente" : "Nuevo cliente"}
      subtitle={initial ? customerLabel(initial) : "Los que reservan por WhatsApp se cargan solos."}
      width={460}
      onClose={onClose}
      footer={
        <>
          <button type="submit" form="customer-form" disabled={isSubmitting}>
            {isSubmitting ? "Guardando…" : "Guardar"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
        </>
      }
    >
      <form id="customer-form" className="form-stack" onSubmit={handleSubmit(onSubmit)} noValidate>
        {errors.root && <p className="error-text">{errors.root.message}</p>}
        <div className="field">
          <label htmlFor="cli-name">Nombre</label>
          <input id="cli-name" {...register("name")} />
        </div>
        <div className="field">
          <label htmlFor="cli-phone">Teléfono</label>
          <input id="cli-phone" type="tel" placeholder="+54 9 351 …" {...register("phone")} />
          <span className="help">Con código de país, como lo usa WhatsApp.</span>
        </div>
        <div className="field">
          <label htmlFor="cli-notes">Notas internas</label>
          <textarea id="cli-notes" rows={3} placeholder="Solo las ve el equipo" {...register("internalNotes")} />
        </div>
      </form>
    </Dialog>
  );
}

// Mockup "ficha cliente".
function CustomerSheet({
  customer,
  today,
  names,
  onClose,
  onEdit,
  onBook,
}: {
  customer: Customer;
  today: string;
  names: { services: Map<string, string>; professionals: Map<string, string> };
  onClose: () => void;
  onEdit: () => void;
  onBook: () => void;
}) {
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadStats(customer.id)
      .then(setStats)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar el historial."));
  }, [customer.id]);

  const sinceLast = stats?.lastVisit ? daysBetween(stats.lastVisit, today) : null;

  return (
    <Dialog
      title={customerLabel(customer)}
      subtitle={customer.name ? (customer.phone ?? "Sin teléfono cargado") : "Sin nombre cargado"}
      width={470}
      onClose={onClose}
      footer={
        <>
          {customer.active && (
            <button type="button" onClick={onBook}>
              Nuevo turno
            </button>
          )}
          {customer.phone && (
            <a className="button secondary" href={whatsAppUrl(customer.phone)} target="_blank" rel="noreferrer">
              WhatsApp
            </a>
          )}
          <span className="spacer" />
          <button type="button" className="ghost" onClick={onEdit}>
            Editar
          </button>
        </>
      }
    >
      {error && <p className="error-text">{error}</p>}
      {!customer.active && <Badge variant="slate">Inactivo</Badge>}
      {!stats ? (
        !error && <p className="muted">Cargando historial…</p>
      ) : (
        <>
          <div className="stats">
            <div className="stat">
              <b>{stats.visits}</b>
              <span>{stats.visits === 1 ? "turno" : "turnos"}</span>
            </div>
            <div className="stat">
              <b>{formatMoney(stats.spent)}</b>
              <span>gastado</span>
            </div>
            <div className="stat">
              <b>{stats.noShows}</b>
              <span>{stats.noShows === 1 ? "falta" : "faltas"}</span>
            </div>
          </div>
          {sinceLast !== null && sinceLast >= DORMANT_DAYS && (
            <WarnBar icon="clock">No viene {ago(sinceLast)}. Buen momento para escribirle.</WarnBar>
          )}
        </>
      )}
      {customer.internalNotes && (
        <div>
          <span className="lab">Notas</span>
          <p className="notes">{customer.internalNotes}</p>
        </div>
      )}
      {stats && (
        <div>
          <span className="lab">Últimos turnos</span>
          {stats.history.length === 0 ? (
            <p className="muted">Todavía no tiene turnos.</p>
          ) : (
            <ul className="hist">
              {stats.history.slice(0, 8).map((a) => {
                const date = a.startTime.slice(0, 10);
                return (
                  <li key={a.id}>
                    <span className="d" title={`${date} ${a.startTime.slice(11, 16)}`}>
                      {date > today ? date.split("-").reverse().slice(0, 2).join("/") : ago(daysBetween(date, today))}
                    </span>
                    <span className="what">
                      {names.services.get(a.serviceId) ?? "Servicio"} · {names.professionals.get(a.professionalId) ?? "—"}
                    </span>
                    {a.status === "COMPLETED" ? (
                      <span className="p">{formatMoney(a.price)}</span>
                    ) : (
                      <span className="p">
                        <AppointmentStatusBadge status={a.status} />
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </Dialog>
  );
}

type DialogState =
  | { kind: "form"; customer: Customer | null }
  | { kind: "sheet"; customer: Customer }
  | { kind: "deactivate"; customer: Customer }
  | null;

export default function CustomersPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const showToast = useToast();
  const [page, setPage] = useState<Page<Customer> | null>(null);
  const [pageNumber, setPageNumber] = useState(0);
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [stats, setStats] = useState<Map<string, Stats>>(new Map());
  const [meta, setMeta] = useState<{
    today: string;
    services: Map<string, string>;
    professionals: Map<string, string>;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const reload = () => setReloadKey((k) => k + 1);

  // Names for the history list and the tenant's "today".
  useEffect(() => {
    Promise.all([
      api.get<{ timezone: string }>("/establishment"),
      api.get<{ id: string; name: string }[]>("/services"),
      api.get<{ content: { id: string; name: string }[] }>("/professionals?size=100"),
    ])
      .then(([establishment, services, professionals]) =>
        setMeta({
          today: nowInZone(establishment.timezone).date,
          services: new Map(services.map((s) => [s.id, s.name])),
          professionals: new Map(professionals.content.map((p) => [p.id, p.name])),
        }),
      )
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la pantalla."));
  }, []);

  // Search as you type.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearch(query.trim());
      setPageNumber(0);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    let cancelled = false;
    const q = search ? `&search=${encodeURIComponent(search)}` : "";
    api
      .get<Page<Customer>>(`/customers?page=${pageNumber}&size=${PAGE_SIZE}&sort=name${q}`)
      .then(async (result) => {
        if (cancelled) return;
        setError(null);
        setPage(result);
        const loaded = await Promise.all(result.content.map((c) => loadStats(c.id).catch(() => null)));
        if (cancelled) return;
        setStats(new Map(result.content.flatMap((c, i) => (loaded[i] ? [[c.id, loaded[i]] as const] : []))));
      })
      .catch((err) => !cancelled && setError(err instanceof ApiError ? err.message : "No se pudieron cargar los clientes."));
    return () => {
      cancelled = true;
    };
  }, [pageNumber, search, reloadKey]);

  // Opened from Inicio ("Ver ficha"): router state { customerId }.
  useEffect(() => {
    const customerId = (location.state as { customerId?: string } | null)?.customerId;
    if (!customerId) return;
    navigate(location.pathname, { replace: true, state: null });
    api
      .get<Customer>(`/customers/${customerId}`)
      .then((customer) => setDialog({ kind: "sheet", customer }))
      .catch(() => showToast("No se encontró el cliente."));
  }, [location.state, location.pathname, navigate, showToast]);

  const bookFor = (customer: Customer) => navigate("/agenda", { state: { book: { customer } } });

  const header = (
    <div className="page-head">
      <div>
        <h1>Clientes</h1>
        <p>Tu cartera, con cuánto hace que no vienen.</p>
      </div>
      <div className="acts">
        <button type="button" onClick={() => setDialog({ kind: "form", customer: null })}>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Nuevo cliente
        </button>
      </div>
    </div>
  );

  const total = page?.totalElements ?? 0;
  const from = page ? page.number * PAGE_SIZE + 1 : 0;

  return (
    <div className="list-page">
      {header}

      <div className="table-card">
        <div className="table-tools">
          <SearchBox value={query} placeholder="Buscar por nombre o teléfono" onChange={setQuery} />
          {page && (
            <span className="count">
              {total} {total === 1 ? "cliente" : "clientes"}
            </span>
          )}
        </div>
        {error && <p className="error-text table-error">{error}</p>}

        {!page || !meta ? (
          !error && <p className="muted table-error">Cargando…</p>
        ) : page.content.length === 0 ? (
          search ? (
            <TableEmpty
              icon="search"
              title={`Nada coincide con “${search}”`}
              text="Probá con menos letras o con parte del teléfono."
              action={
                <button type="button" className="secondary" onClick={() => setQuery("")}>
                  Limpiar búsqueda
                </button>
              }
            />
          ) : (
            <TableEmpty
              icon="plus"
              title="Todavía no hay clientes"
              text="Los clientes se cargan solos cuando reservan, o los agregás a mano."
              action={
                <button type="button" onClick={() => setDialog({ kind: "form", customer: null })}>
                  Nuevo cliente
                </button>
              }
            />
          )
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>Cliente</th>
                  <th className="num hide-sm">Turnos</th>
                  <th>Última visita</th>
                  <th className="num hide-sm">Gastado</th>
                  <th>
                    <span className="sr-only">Acciones</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((customer) => {
                  const s = stats.get(customer.id);
                  const sinceLast = s?.lastVisit ? daysBetween(s.lastVisit, meta.today) : null;
                  const open = () => setDialog({ kind: "sheet", customer });
                  return (
                    <tr key={customer.id} className={`clickable${customer.active ? "" : " off"}`} onClick={open}>
                      <td>
                        <IdCell
                          avatar={<Avatar name={customer.name ?? customer.phone} color={avatarColor(s)} round />}
                          title={
                            <>
                              <button type="button" className="rowlink" onClick={open}>
                                {customerLabel(customer)}
                              </button>
                              {s && s.visits >= LOYAL_VISITS && <Badge variant="brick">Fiel</Badge>}
                              {!customer.active && <Badge variant="slate">Inactivo</Badge>}
                            </>
                          }
                          subtitle={
                            <>
                              {customer.name ? (customer.phone ?? "Sin teléfono") : "Sin nombre"}
                              {s && s.noShows > 0 && ` · ${s.noShows} ${s.noShows === 1 ? "falta" : "faltas"}`}
                            </>
                          }
                        />
                      </td>
                      <td className="num hide-sm">{s ? s.visits : "…"}</td>
                      <td>
                        {!s ? (
                          "…"
                        ) : sinceLast === null ? (
                          <span className="muted">Nunca</span>
                        ) : (
                          <>
                            {ago(sinceLast)}
                            {customer.active && sinceLast >= DORMANT_DAYS && <Badge variant="amber">Dormido</Badge>}
                          </>
                        )}
                      </td>
                      <td className="num hide-sm">{s ? formatMoney(s.spent) : "…"}</td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <div className="rowacts">
                          {customer.active && (
                            <button type="button" className="secondary sm hide-sm" onClick={() => bookFor(customer)}>
                              Nuevo turno
                            </button>
                          )}
                          <RowMenu
                            items={[
                              { label: "Ver ficha", icon: "user", onSelect: open },
                              { label: "Editar datos", icon: "pen", onSelect: () => setDialog({ kind: "form", customer }) },
                              ...(customer.active
                                ? [{ label: "Nuevo turno", icon: "cal" as const, onSelect: () => bookFor(customer) }]
                                : []),
                              ...(customer.phone
                                ? [
                                    {
                                      label: "Escribir por WhatsApp",
                                      icon: "wa" as const,
                                      onSelect: () => window.open(whatsAppUrl(customer.phone!), "_blank", "noopener"),
                                    },
                                  ]
                                : []),
                              ...(customer.active
                                ? [
                                    null,
                                    {
                                      label: "Dar de baja",
                                      icon: "off" as const,
                                      danger: true,
                                      onSelect: () => setDialog({ kind: "deactivate", customer }),
                                    },
                                  ]
                                : []),
                            ]}
                          />
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <Pager
              page={page.number}
              pages={page.totalPages}
              from={from}
              to={from + page.content.length - 1}
              total={total}
              onPage={setPageNumber}
            />
          </>
        )}
      </div>

      {dialog?.kind === "form" && (
        <CustomerDialog
          initial={dialog.customer}
          onClose={() => setDialog(null)}
          onSaved={(label) => {
            setDialog(null);
            showToast(dialog.customer ? `${label} actualizado` : `${label} agregado`);
            reload();
          }}
        />
      )}
      {dialog?.kind === "sheet" && meta && (
        <CustomerSheet
          customer={dialog.customer}
          today={meta.today}
          names={meta}
          onClose={() => setDialog(null)}
          onEdit={() => setDialog({ kind: "form", customer: dialog.customer })}
          onBook={() => bookFor(dialog.customer)}
        />
      )}
      {dialog?.kind === "deactivate" && (
        <DeactivateDialog
          question={<>¿Dar de baja a <b>{customerLabel(dialog.customer)}</b>?</>}
          effect="No se le pueden agendar turnos nuevos. Su historial queda guardado."
          onClose={() => setDialog(null)}
          onConfirm={async () => {
            await api.delete(`/customers/${dialog.customer.id}`);
            setDialog(null);
            showToast(`${customerLabel(dialog.customer)} quedó inactivo`);
            reload();
          }}
        />
      )}
    </div>
  );
}
