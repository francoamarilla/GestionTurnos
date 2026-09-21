import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import { CustomerCombo, type PickedCustomer } from "../agenda/AppointmentDialogs";
import {
  customerLabel,
  loadAppointments,
  nowInZone,
  PROFESSIONAL_COLORS,
  timeOf,
  toMinutes,
  type Appointment,
  type Customer,
  type PaymentMethod,
  type Professional,
  type Service,
} from "../agenda/model";
import { Badge } from "../components/Badge";
import { Dialog } from "../components/Dialog";
import { Delta, RangeBar, SplitBar, StatTile } from "../components/ReportKit";
import {
  percentChange,
  previousRange,
  rangeLabel,
  rememberedRange,
  rememberRange,
  shortDate,
  type Range,
} from "../components/reportRange";
import { RowMenu, TableEmpty } from "../components/TableKit";
import { useToast } from "../components/Toast";
import { formatMoney } from "../format";

interface Catalog {
  timezone: string;
  professionals: (Professional & { active: boolean })[]; // all, so old sales keep their name
  services: Service[];
  assignments: Map<string, string[]>; // active professionals only
  customers: Map<string, Customer>;
}

async function loadCatalog(): Promise<Catalog> {
  const [establishment, professionalsPage, services, customersPage] = await Promise.all([
    api.get<{ timezone: string }>("/establishment"),
    api.get<{ content: (Professional & { active: boolean })[] }>("/professionals?size=100"),
    api.get<Service[]>("/services"),
    // ponytail: same size=200 name-lookup ceiling used elsewhere.
    api.get<{ content: Customer[] }>("/customers?size=200"),
  ]);
  const active = professionalsPage.content.filter((p) => p.active);
  const assigned = await Promise.all(active.map((p) => api.get<string[]>(`/professionals/${p.id}/services`)));
  return {
    timezone: establishment.timezone,
    professionals: professionalsPage.content,
    services,
    assignments: new Map(active.map((p, i) => [p.id, assigned[i]])),
    customers: new Map(customersPage.content.map((c) => [c.id, c])),
  };
}

// "Ventas" = everything charged: completed appointments plus direct sales
// (the same population /statistics/summary sums as totalSales).
function loadSales(range: { from: string; to: string }, professionalId: string) {
  const pro = professionalId ? `&professionalId=${professionalId}` : "";
  return api
    .get<Appointment[]>(`/appointments?from=${range.from}&to=${range.to}&status=COMPLETED${pro}`)
    .then((list) => list.sort((a, b) => b.startTime.localeCompare(a.startTime)));
}

const sum = (list: Appointment[]) => list.reduce((total, a) => total + Number(a.price), 0);

// Hues from Mockups/ventas-stats-mockup.html: pine / brick / amber.
const PAYMENT: Record<PaymentMethod, { label: string; short: string; variant: "pine" | "brick" | "amber" }> = {
  CASH: { label: "Efectivo", short: "Efec.", variant: "pine" },
  TRANSFER: { label: "Transferencia", short: "Transf.", variant: "brick" },
  CARD: { label: "Tarjeta", short: "Tarj.", variant: "amber" },
};
const PAYMENT_METHODS = Object.keys(PAYMENT) as PaymentMethod[];

// Service select value for a sale off the catalog (sent as serviceId null).
const OTHER = "other";

function csvCell(value: string | number): string {
  const text = String(value);
  return /[";\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function downloadCsv(filename: string, rows: (string | number)[][]) {
  // ";" + BOM: what spreadsheet apps in es-AR open correctly by default.
  const csv = String.fromCharCode(0xfeff) + rows.map((row) => row.map(csvCell).join(";")).join("\r\n");
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function SaleDialog({
  catalog,
  onClose,
  onSaved,
}: {
  catalog: Catalog;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const now = nowInZone(catalog.timezone);
  const activeProfessionals = catalog.professionals.filter((p) => p.active);
  const offered = (id: string) =>
    catalog.services.filter((s) => s.active && catalog.assignments.get(id)?.includes(s.id));

  const [professionalId, setProfessionalId] = useState(activeProfessionals[0]?.id ?? "");
  const [serviceId, setServiceId] = useState(offered(activeProfessionals[0]?.id ?? "")[0]?.id ?? OTHER);
  const [description, setDescription] = useState("");
  const [price, setPrice] = useState<string>(String(offered(activeProfessionals[0]?.id ?? "")[0]?.price ?? ""));
  const [customer, setCustomer] = useState<PickedCustomer>(null);
  const [payment, setPayment] = useState<PaymentMethod>("CASH");
  // "Cobrar este": today's latest confirmed appointment that already started.
  const [candidate, setCandidate] = useState<Appointment | null>(null);
  const [linked, setLinked] = useState<Appointment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    loadAppointments(now.date)
      .then((list) =>
        setCandidate(
          list.filter((a) => a.status === "CONFIRMED" && toMinutes(timeOf(a.startTime)) <= now.minutes).pop() ?? null,
        ),
      )
      .catch(() => setCandidate(null));
    // Only on open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const services = offered(professionalId);
  const amount = Number(price);
  const nameOf = (a: Appointment) => ({
    service: a.description ?? catalog.services.find((s) => s.id === a.serviceId)?.name ?? "Servicio",
    professional: catalog.professionals.find((p) => p.id === a.professionalId)?.name ?? "Profesional",
    customer: a.customerId ? customerLabel(catalog.customers.get(a.customerId)) : "Cliente ocasional",
  });

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      if (linked) {
        await api.patch(`/appointments/${linked.id}/status`, {
          status: "COMPLETED",
          paymentMethod: payment,
          price: amount,
        });
        onSaved(`Turno de las ${timeOf(linked.startTime)} cobrado: ${formatMoney(amount)}`);
        return;
      }
      const isPhone = customer?.kind === "new" && /^[+\d\s()-]{6,}$/.test(customer.text);
      await api.post("/sales", {
        professionalId,
        serviceId: serviceId === OTHER ? null : serviceId,
        description: serviceId === OTHER ? description.trim() : null,
        price: amount,
        customerId: customer?.kind === "existing" ? customer.customer.id : null,
        customerName: customer?.kind === "new" && !isPhone ? customer.text : null,
        customerPhone: customer?.kind === "new" && isPhone ? customer.text : null,
        paymentMethod: payment,
      });
      onSaved(`Venta de ${formatMoney(amount)} registrada`);
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 409
          ? "Ese profesional tiene un turno en este horario. Si la venta es de ese turno, usá “Cobrar este”."
          : err instanceof ApiError
            ? err.message
            : "No se pudo registrar la venta.",
      );
      setBusy(false);
    }
  }

  const priceOk = price !== "" && amount >= 0;
  const valid =
    priceOk && (linked || (professionalId && (serviceId !== OTHER || description.trim() !== "")));

  // The price field is shared: linking loads the booked price, unlinking
  // goes back to the selected service's.
  function link(appointment: Appointment | null) {
    setLinked(appointment);
    const service = services.find((s) => s.id === serviceId);
    setPrice(String(appointment ? appointment.price : (service?.price ?? "")));
  }

  const priceField = (
    <div className="field">
      <label htmlFor="sale-price">Precio cobrado</label>
      <div className="input-affix prefix money">
        <span>$</span>
        <input
          id="sale-price"
          type="number"
          min={0}
          step="0.01"
          inputMode="decimal"
          value={price}
          onChange={(e) => setPrice(e.target.value)}
        />
      </div>
      <span className="help">
        {linked
          ? `Se reservó a ${formatMoney(linked.price)}. Cambialo si hiciste un descuento.`
          : serviceId === OTHER
            ? "Lo que cobraste por esto."
            : "Viene del servicio, pero lo podés cambiar si hiciste un descuento."}
      </span>
    </div>
  );
  const shown = linked ? nameOf(linked) : null;

  return (
    <Dialog
      title="Registrar venta"
      subtitle={`Hoy, ${shortDate(now.date)}`}
      width={470}
      onClose={onClose}
      footer={
        <>
          <button type="button" disabled={!valid || busy} onClick={submit}>
            {busy ? "Registrando…" : linked ? "Cobrar turno" : "Registrar venta"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
          <span className="tot">{formatMoney(amount || 0)}</span>
        </>
      }
    >
      {error && <p className="error-text">{error}</p>}

      {candidate && (
        <div className="linkpick">
          <div className="tx">
            <b>
              {timeOf(candidate.startTime)} · {nameOf(candidate).service}
            </b>
            <span>
              {nameOf(candidate).customer} con {nameOf(candidate).professional}
            </span>
          </div>
          {linked ? (
            <button type="button" className="secondary sm" onClick={() => link(null)}>
              Desvincular
            </button>
          ) : (
            <button type="button" className="secondary sm" onClick={() => link(candidate)}>
              Cobrar este
            </button>
          )}
        </div>
      )}

      {linked && shown ? (
        <>
          <dl className="kv">
            <dt>Profesional</dt>
            <dd>{shown.professional}</dd>
            <dt>Servicio</dt>
            <dd>{shown.service}</dd>
            <dt>Cliente</dt>
            <dd>{shown.customer}</dd>
          </dl>
          {priceField}
        </>
      ) : (
        <>
          <div className="field">
            <label htmlFor="sale-pro">Profesional</label>
            <select
              id="sale-pro"
              value={professionalId}
              onChange={(e) => {
                const first = offered(e.target.value)[0];
                setProfessionalId(e.target.value);
                setServiceId(first?.id ?? OTHER);
                setPrice(first ? String(first.price) : "");
              }}
            >
              {activeProfessionals.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="sale-srv">Servicio</label>
            <select
              id="sale-srv"
              value={serviceId}
              onChange={(e) => {
                setServiceId(e.target.value);
                const service = services.find((s) => s.id === e.target.value);
                // "Otro" has no list price: leave it for the admin to type.
                setPrice(service ? String(service.price) : "");
              }}
            >
              {services.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} · {formatMoney(s.price)}
                </option>
              ))}
              <option value={OTHER}>Otro (a definir)</option>
            </select>
          </div>
          {serviceId === OTHER && (
            <div className="field">
              <label htmlFor="sale-desc">Qué se vendió</label>
              <input
                id="sale-desc"
                maxLength={120}
                placeholder="Ej.: pomada, lavado, arreglo de barba"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
          )}
          {priceField}
          <div className="field">
            <label htmlFor="appt-customer">Cliente</label>
            <CustomerCombo value={customer} onChange={setCustomer} placeholder="Buscá por nombre, o dejalo vacío" />
            <span className="help">Si lo cargás, la venta queda en su ficha y suma a su historial.</span>
          </div>
        </>
      )}

      <div className="field">
        <span className="label" id="sale-pay">
          Cómo pagó
        </span>
        <div className="chip-row" role="group" aria-labelledby="sale-pay">
          {PAYMENT_METHODS.map((m) => (
            <button key={m} type="button" className="chip" aria-pressed={payment === m} onClick={() => setPayment(m)}>
              {PAYMENT[m].label}
            </button>
          ))}
        </div>
      </div>
    </Dialog>
  );
}

export default function SalesPage() {
  const navigate = useNavigate();
  const showToast = useToast();
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [range, setRange] = useState<Range | null>(null);
  const [compare, setCompare] = useState(true);
  const [professionalId, setProfessionalId] = useState("");
  const [data, setData] = useState<{ key: string; sales: Appointment[]; previous: Appointment[] | null } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    loadCatalog()
      .then((result) => {
        const remembered = rememberedRange(nowInZone(result.timezone).date);
        setCatalog(result);
        setRange(remembered.range);
        setCompare(remembered.compare);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo cargar la pantalla."));
  }, []);

  const key = range ? `${range.from}|${range.to}|${professionalId}|${compare}|${reloadKey}` : "";
  useEffect(() => {
    if (!range) return;
    let cancelled = false;
    Promise.all([loadSales(range, professionalId), compare ? loadSales(previousRange(range), professionalId) : null])
      .then(([sales, previous]) => {
        if (cancelled) return;
        setError(null);
        setData({ key, sales, previous });
      })
      .catch((err) => !cancelled && setError(err instanceof ApiError ? err.message : "No se pudieron cargar las ventas."));
    return () => {
      cancelled = true;
    };
    // key covers range/professional/compare/reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  function changeRange(next: Range, nextCompare: boolean) {
    setRange(next);
    setCompare(nextCompare);
    rememberRange(next, nextCompare);
  }

  if (!catalog || !range) {
    return (
      <div className="list-page">
        <div className="page-head">
          <div>
            <h1>Ventas</h1>
          </div>
        </div>
        {error ? <p className="error-text">{error}</p> : <p className="muted">Cargando…</p>}
      </div>
    );
  }

  const today = nowInZone(catalog.timezone).date;
  const sales = data?.sales ?? [];
  const previous = data?.previous ?? null;
  const total = sum(sales);
  const average = sales.length ? total / sales.length : 0;
  const paidWith = (m: PaymentMethod | null) => sum(sales.filter((a) => a.paymentMethod === m));
  const unrecorded = paidWith(null);
  const share = (value: number) => (total ? Math.round((value / total) * 100) : 0);
  const professional = (id: string) => catalog.professionals.find((p) => p.id === id);
  const colorOf = (id: string) =>
    PROFESSIONAL_COLORS[Math.max(0, catalog.professionals.findIndex((p) => p.id === id)) % PROFESSIONAL_COLORS.length];
  const serviceName = (a: Appointment) =>
    a.description ?? catalog.services.find((s) => s.id === a.serviceId)?.name ?? "Servicio";
  const customerOf = (a: Appointment) => (a.customerId ? catalog.customers.get(a.customerId) : undefined);

  const byDay = new Map<string, Appointment[]>();
  for (const a of sales) {
    const day = a.startTime.slice(0, 10);
    byDay.set(day, [...(byDay.get(day) ?? []), a]);
  }
  const singleDay = range.from === range.to;

  function exportCsv() {
    downloadCsv(`ventas_${range!.from}_${range!.to}.csv`, [
      ["Fecha", "Hora", "Profesional", "Servicio", "Origen", "Cliente", "Pago", "Precio"],
      ...[...sales].reverse().map((a) => [
        a.startTime.slice(0, 10),
        timeOf(a.startTime),
        professional(a.professionalId)?.name ?? "",
        serviceName(a),
        a.origin === "DIRECT_SALE" ? "Venta directa" : "Turno",
        a.customerId ? customerLabel(customerOf(a)) : "Ocasional",
        a.paymentMethod ? PAYMENT[a.paymentMethod].label : "",
        Number(a.price),
      ]),
    ]);
  }

  return (
    <div className="list-page report-page">
      <div className="page-head">
        <div>
          <h1>Ventas</h1>
          <p>Todo lo cobrado · {rangeLabel(range)}</p>
        </div>
        <div className="acts">
          <button type="button" className="secondary" disabled={sales.length === 0} onClick={exportCsv}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 4v12M7 11l5 5 5-5M4 20h16" />
            </svg>
            Exportar
          </button>
          <button type="button" onClick={() => setDialogOpen(true)} disabled={!catalog.professionals.some((p) => p.active)}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
            Registrar venta
          </button>
        </div>
      </div>

      <RangeBar range={range} today={today} compare={compare} onChange={changeRange}>
        <select
          className="select"
          aria-label="Filtrar profesional"
          value={professionalId}
          onChange={(e) => setProfessionalId(e.target.value)}
        >
          <option value="">Todos los profesionales</option>
          {catalog.professionals.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
              {p.active ? "" : " (inactivo)"}
            </option>
          ))}
        </select>
      </RangeBar>

      {error && <p className="error-text">{error}</p>}

      <div className={`report-body${data?.key === key ? "" : " stale"}`} aria-busy={data?.key !== key}>
        <div className="kpis4">
          <StatTile label="Cobrado" value={formatMoney(total)}>
            {compare && previous && <Delta change={percentChange(total, sum(previous))} />}
          </StatTile>
          <StatTile label="Ventas" value={sales.length}>
            {compare && previous && <Delta change={percentChange(sales.length, previous.length)} />}
          </StatTile>
          <StatTile label="Ticket promedio" value={formatMoney(Math.round(average))}>
            {compare && previous && (
              <Delta change={percentChange(average, previous.length ? sum(previous) / previous.length : 0)} />
            )}
          </StatTile>
          <div className="panel stat-tile">
            <span className="lab">Cómo pagó</span>
            <SplitBar
              label="Medio de pago de lo cobrado"
              parts={[
                ...PAYMENT_METHODS.map((m) => ({
                  label: `${PAYMENT[m].short} ${share(paidWith(m))}%`,
                  value: paidWith(m),
                  color: `var(--${PAYMENT[m].variant})`,
                })),
                // Sales from before #3, and turnos completed from Agenda/Inicio.
                ...(unrecorded ? [{ label: `Sin dato ${share(unrecorded)}%`, value: unrecorded, color: "var(--slate)" }] : []),
              ]}
            />
          </div>
        </div>

        <div className="table-card">
          <div className="panel-head">
            <div>
              <h2>Detalle de ventas</h2>
              <p>
                {sales.length} {sales.length === 1 ? "venta" : "ventas"} · {formatMoney(total)} en el período
              </p>
            </div>
          </div>
          {data === null ? (
            <p className="muted table-error">Cargando…</p>
          ) : sales.length === 0 ? (
            <TableEmpty
              icon="search"
              title="No hay ventas en este rango"
              text="Probá con otro período, o registrá la primera."
              action={
                <button type="button" onClick={() => setDialogOpen(true)}>
                  Registrar venta
                </button>
              }
            />
          ) : (
            <table className="sales-table">
              <thead>
                <tr>
                  <th>Hora</th>
                  <th>Profesional</th>
                  <th>Servicio</th>
                  <th className="hide-sm">Cliente</th>
                  <th className="hide-sm">Pago</th>
                  <th className="num">Precio</th>
                  <th>
                    <span className="sr-only">Acciones</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {[...byDay.entries()].map(([day, list]) => [
                  !singleDay && (
                    <tr key={`day-${day}`} className="dayhead">
                      <td colSpan={3}>
                        {shortDate(day)} · {list.length} {list.length === 1 ? "venta" : "ventas"}
                      </td>
                      <td className="hide-sm" colSpan={2} />
                      <td className="num">{formatMoney(sum(list))}</td>
                      <td />
                    </tr>
                  ),
                  ...list.map((a) => {
                    const customer = customerOf(a);
                    return (
                      <tr key={a.id}>
                        <td>{timeOf(a.startTime)}</td>
                        <td>
                          <span className="who">
                            <span className="dot" style={{ background: colorOf(a.professionalId) }} aria-hidden="true" />
                            {professional(a.professionalId)?.name ?? "Profesional"}
                          </span>
                        </td>
                        <td className="grow">
                          {serviceName(a)}
                          {a.origin === "DIRECT_SALE" && <span className="linked"> · sin turno</span>}
                        </td>
                        <td className="hide-sm">
                          {a.customerId ? customerLabel(customer) : <span className="muted">Ocasional</span>}
                        </td>
                        <td className="hide-sm">
                          {a.paymentMethod ? (
                            <Badge variant={PAYMENT[a.paymentMethod].variant}>{PAYMENT[a.paymentMethod].label}</Badge>
                          ) : (
                            <span className="muted">—</span>
                          )}
                        </td>
                        <td className="num">{formatMoney(a.price)}</td>
                        <td>
                          {a.customerId && (
                            <div className="rowacts">
                              <RowMenu
                                items={[
                                  {
                                    label: "Ficha del cliente",
                                    icon: "user",
                                    onSelect: () => navigate("/customers", { state: { customerId: a.customerId } }),
                                  },
                                ]}
                              />
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  }),
                ])}
                <tr className="total">
                  <td colSpan={3}>
                    Total del período · {sales.length} {sales.length === 1 ? "venta" : "ventas"}
                  </td>
                  <td className="hide-sm" colSpan={2} />
                  <td className="num">{formatMoney(total)}</td>
                  <td />
                </tr>
              </tbody>
            </table>
          )}
        </div>
      </div>

      {dialogOpen && (
        <SaleDialog
          catalog={catalog}
          onClose={() => setDialogOpen(false)}
          onSaved={(message) => {
            setDialogOpen(false);
            showToast(message);
            // A sale may have created a customer inline: refresh names too.
            loadCatalog()
              .then(setCatalog)
              .catch(() => {});
            setReloadKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}
