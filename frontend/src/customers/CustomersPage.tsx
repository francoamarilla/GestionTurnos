import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useLocation, useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import { customerLabel, nowInZone, type Appointment, type Customer } from "../agenda/model";
import { AppointmentStatusBadge, Badge } from "../components/Badge";
import { Dialog } from "../components/Dialog";
import {
  ActiveChips,
  Avatar,
  DeactivateDialog,
  IdCell,
  Pager,
  RowMenu,
  SearchBox,
  SortHeader,
  TableEmpty,
  WarnBar,
} from "../components/TableKit";
import type { ActiveFilter, Sort } from "../components/tableUtils";
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

// GET /api/customers/summary: the customer plus the totals the table
// shows, already aggregated (ENDPOINTS-PENDIENTES.md #2). The same fields
// feed the filters and the sort, which is why both run on the server -
// this page only ever holds ten of the rows they apply to.
interface CustomerSummary extends Customer {
  visits: number;
  lastVisit: string | null; // ISO date-time in the tenant's zone, or null
  spent: number;
  noShows: number;
}

type SortKey = "name" | "visits" | "lastVisit" | "spent";

const ACTIVE_PARAM: Record<ActiveFilter, string> = {
  activos: "&active=true",
  inactivos: "&active=false",
  todos: "",
};

interface Stats {
  visits: number; // completed appointments
  spent: number;
  noShows: number;
  lastVisit: string | null; // ISO date of the last completed appointment
  history: Appointment[]; // newest first
}

// Only the ficha uses this now: one call for one open dialog, for the
// history list that no aggregate can replace. The table's numbers come
// from /customers/summary.
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

const avatarColor = (visits: number) => (visits >= LOYAL_VISITS ? "var(--brick)" : "var(--slate)");

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
  const [page, setPage] = useState<Page<CustomerSummary> | null>(null);
  const [pageNumber, setPageNumber] = useState(0);
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<ActiveFilter>("activos");
  const [sort, setSort] = useState<Sort<SortKey>>(null);
  const [dormantOnly, setDormantOnly] = useState(false);
  const [counts, setCounts] = useState<{ active: number; inactive: number; dormant: number } | null>(null);
  const [meta, setMeta] = useState<{
    today: string;
    services: Map<string, string>;
    professionals: Map<string, string>;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const reload = () => setReloadKey((k) => k + 1);

  // Backlog item 1: PATCH accepts `active`, so un cliente dado de baja
  // vuelve sin re-cargarlo (DELETE only sets active=false).
  function reactivate(customer: Customer) {
    api
      .patch(`/customers/${customer.id}`, { active: true })
      .then(() => {
        showToast(`${customerLabel(customer)} volvió a estar activo`);
        reload();
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo activar al cliente."));
  }

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

  // Chip and warnbar numbers count the whole base, not the current page or
  // the search, same as in Servicios and Profesionales. size=1 because only
  // totalElements is read.
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.get<Page<CustomerSummary>>("/customers/summary?size=1&active=true"),
      api.get<Page<CustomerSummary>>("/customers/summary?size=1&active=false"),
      api.get<Page<CustomerSummary>>(`/customers/summary?size=1&active=true&dormantDays=${DORMANT_DAYS}`),
    ])
      .then(([active, inactive, dormant]) => {
        if (cancelled) return;
        setCounts({
          active: active.totalElements,
          inactive: inactive.totalElements,
          dormant: dormant.totalElements,
        });
      })
      .catch(() => setCounts(null));
    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  useEffect(() => {
    let cancelled = false;
    const params = [
      `page=${pageNumber}`,
      `size=${PAGE_SIZE}`,
      `sort=${sort ? `${sort.key},${sort.dir === 1 ? "asc" : "desc"}` : "name,asc"}`,
      search ? `search=${encodeURIComponent(search)}` : "",
      dormantOnly ? `dormantDays=${DORMANT_DAYS}` : "",
    ].filter(Boolean);
    api
      .get<Page<CustomerSummary>>(`/customers/summary?${params.join("&")}${ACTIVE_PARAM[filter]}`)
      .then((result) => {
        if (cancelled) return;
        setError(null);
        setPage(result);
      })
      .catch((err) => !cancelled && setError(err instanceof ApiError ? err.message : "No se pudieron cargar los clientes."));
    return () => {
      cancelled = true;
    };
  }, [pageNumber, search, filter, sort, dormantOnly, reloadKey]);

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

  // "Ver quiénes son" is the dormant filter, and dormidos are counted among
  // the active ones, so it forces that chip too.
  function showDormant() {
    setDormantOnly(true);
    setFilter("activos");
    setPageNumber(0);
  }

  function showEveryone() {
    setDormantOnly(false);
    setPageNumber(0);
  }

  function sortOn(next: Sort<SortKey>) {
    setSort(next);
    setPageNumber(0);
  }

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
          {counts && (
            <ActiveChips
              value={filter}
              counts={counts}
              onChange={(next) => {
                setFilter(next);
                setPageNumber(0);
              }}
            />
          )}
          {page && (
            <span className="count">
              {total} {total === 1 ? "cliente" : "clientes"}
            </span>
          )}
        </div>
        {error && <p className="error-text table-error">{error}</p>}

        {dormantOnly ? (
          <WarnBar icon="clock" action={<button type="button" className="secondary sm" onClick={showEveryone}>Ver todos</button>}>
            Mostrando solo los que no vienen hace más de 2 meses.
          </WarnBar>
        ) : (
          counts !== null &&
          counts.dormant > 0 && (
            <WarnBar
              icon="clock"
              action={
                <button type="button" className="secondary sm" onClick={showDormant}>
                  Ver quiénes son
                </button>
              }
            >
              {counts.dormant} {counts.dormant === 1 ? "cliente" : "clientes"} sin venir hace más de 2 meses.
            </WarnBar>
          )
        )}

        {!page || !meta ? (
          !error && <p className="muted table-error">Cargando…</p>
        ) : page.content.length === 0 ? (
          search ? (
            <TableEmpty
              icon="search"
              title={`Nada coincide con “${search}”`}
              text="Probá con menos letras, con parte del teléfono, o revisá el filtro de estado."
              action={
                <button type="button" className="secondary" onClick={() => setQuery("")}>
                  Limpiar búsqueda
                </button>
              }
            />
          ) : dormantOnly ? (
            <TableEmpty
              icon="check"
              title="Nadie está dormido"
              text="Todos tus clientes pasaron en los últimos 2 meses."
              action={
                <button type="button" className="secondary" onClick={showEveryone}>
                  Ver todos
                </button>
              }
            />
          ) : filter === "inactivos" ? (
            <TableEmpty icon="check" title="No hay nadie dado de baja" text="Toda tu cartera está activa." />
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
                  <SortHeader label="Cliente" sortKey="name" sort={sort} onSort={sortOn} />
                  <SortHeader label="Turnos" sortKey="visits" sort={sort} onSort={sortOn} className="num hide-sm" />
                  <SortHeader label="Última visita" sortKey="lastVisit" sort={sort} onSort={sortOn} />
                  <SortHeader label="Gastado" sortKey="spent" sort={sort} onSort={sortOn} className="num hide-sm" />
                  <th>
                    <span className="sr-only">Acciones</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((customer) => {
                  const sinceLast = customer.lastVisit
                    ? daysBetween(customer.lastVisit.slice(0, 10), meta.today)
                    : null;
                  const open = () => setDialog({ kind: "sheet", customer });
                  return (
                    <tr key={customer.id} className={`clickable${customer.active ? "" : " off"}`} onClick={open}>
                      <td>
                        <IdCell
                          avatar={
                            <Avatar name={customer.name ?? customer.phone} color={avatarColor(customer.visits)} round />
                          }
                          title={
                            <>
                              <button type="button" className="rowlink" onClick={open}>
                                {customerLabel(customer)}
                              </button>
                              {customer.visits >= LOYAL_VISITS && <Badge variant="brick">Fiel</Badge>}
                              {!customer.active && <Badge variant="slate">Inactivo</Badge>}
                            </>
                          }
                          subtitle={
                            <>
                              {customer.name ? (customer.phone ?? "Sin teléfono") : "Sin nombre"}
                              {customer.noShows > 0 &&
                                ` · ${customer.noShows} ${customer.noShows === 1 ? "falta" : "faltas"}`}
                            </>
                          }
                        />
                      </td>
                      <td className="num hide-sm">{customer.visits}</td>
                      <td>
                        {sinceLast === null ? (
                          <span className="muted">Nunca</span>
                        ) : (
                          <>
                            {ago(sinceLast)}
                            {customer.active && sinceLast >= DORMANT_DAYS && <Badge variant="amber">Dormido</Badge>}
                          </>
                        )}
                      </td>
                      <td className="num hide-sm">{formatMoney(customer.spent)}</td>
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
                                : [
                                    null,
                                    {
                                      label: "Volver a activar",
                                      icon: "on" as const,
                                      onSelect: () => reactivate(customer),
                                    },
                                  ]),
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
