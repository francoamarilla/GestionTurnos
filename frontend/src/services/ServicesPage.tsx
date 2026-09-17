import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { api, ApiError } from "../api/client";
import { PROFESSIONAL_COLORS, type Professional, type Service } from "../agenda/model";
import { ActiveBadge, Badge } from "../components/Badge";
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
import { initials, matchesActive, paginate, sortRows, type ActiveFilter, type Sort } from "../components/tableUtils";
import { useToast } from "../components/Toast";
import { formatMoney } from "../format";

const PAGE_SIZE = 10;

interface Data {
  services: Service[];
  // Active professionals and the service ids each one offers.
  professionals: Professional[];
  assignments: Map<string, string[]>;
}

async function loadData(): Promise<Data> {
  const [services, professionalsPage] = await Promise.all([
    api.get<Service[]>("/services"),
    api.get<{ content: Professional[] }>("/professionals?active=true&size=100"),
  ]);
  const professionals = professionalsPage.content;
  const assigned = await Promise.all(professionals.map((p) => api.get<string[]>(`/professionals/${p.id}/services`)));
  return {
    services: services.sort((a, b) => a.name.localeCompare(b.name)),
    professionals,
    assignments: new Map(professionals.map((p, i) => [p.id, assigned[i]])),
  };
}

const serviceColor = (index: number) => PROFESSIONAL_COLORS[index % PROFESSIONAL_COLORS.length];

interface ServiceFormValues {
  name: string;
  description: string;
  durationMinutes: number;
  price: number;
}

function ServiceDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: Service | null;
  onClose: () => void;
  onSaved: (name: string) => void;
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
      name: values.name.trim(),
      description: values.description.trim() || null,
      durationMinutes: Number(values.durationMinutes),
      price: Number(values.price),
    };
    try {
      if (initial) await api.patch(`/services/${initial.id}`, body);
      else await api.post("/services", body);
      onSaved(body.name);
    } catch (err) {
      setError("root", { message: err instanceof ApiError ? err.message : "No se pudo guardar el servicio." });
    }
  }

  return (
    <Dialog
      title={initial ? "Editar servicio" : "Nuevo servicio"}
      subtitle={initial ? initial.name : "Lo que ofrecés, cuánto dura y cuánto sale."}
      width={460}
      onClose={onClose}
      footer={
        <>
          <button type="submit" form="service-form" disabled={isSubmitting}>
            {isSubmitting ? "Guardando…" : "Guardar"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
        </>
      }
    >
      <form id="service-form" className="form-stack" onSubmit={handleSubmit(onSubmit)} noValidate>
        {errors.root && <p className="error-text">{errors.root.message}</p>}
        <div className="field">
          <label htmlFor="svc-name">Nombre</label>
          <input id="svc-name" {...register("name", { required: "El nombre es obligatorio.", validate: (v) => v.trim() !== "" || "El nombre es obligatorio." })} />
          {errors.name && <span className="error-text">{errors.name.message}</span>}
        </div>
        <div className="field">
          <label htmlFor="svc-desc">Descripción</label>
          <input id="svc-desc" placeholder="Opcional" {...register("description")} />
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="svc-min">Duración</label>
            <div className="input-affix">
              <input
                id="svc-min"
                type="number"
                min={1}
                step={5}
                inputMode="numeric"
                {...register("durationMinutes", { required: true, min: 1, valueAsNumber: true })}
              />
              <span>min</span>
            </div>
            {errors.durationMinutes && <span className="error-text">Tiene que ser mayor a 0.</span>}
          </div>
          <div className="field">
            <label htmlFor="svc-price">Precio</label>
            <div className="input-affix prefix">
              <span>$</span>
              <input
                id="svc-price"
                type="number"
                min={0}
                step="0.01"
                inputMode="decimal"
                {...register("price", { required: true, min: 0, valueAsNumber: true })}
              />
            </div>
            {errors.price && <span className="error-text">Tiene que ser 0 o mayor.</span>}
          </div>
        </div>
      </form>
    </Dialog>
  );
}

// "Quién lo hace": assignments live on each professional, so a change here
// rewrites the full service list of every professional that was toggled.
function WhoDialog({
  service,
  data,
  onClose,
  onSaved,
}: {
  service: Service;
  data: Data;
  onClose: () => void;
  onSaved: () => void;
}) {
  const initial = new Set(data.professionals.filter((p) => data.assignments.get(p.id)?.includes(service.id)).map((p) => p.id));
  const [selected, setSelected] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const changed = data.professionals.filter((p) => initial.has(p.id) !== selected.has(p.id));
      await Promise.all(
        changed.map((p) => {
          const current = (data.assignments.get(p.id) ?? []).filter((id) => id !== service.id);
          return api.put(`/professionals/${p.id}/services`, selected.has(p.id) ? [...current, service.id] : current);
        }),
      );
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo guardar.");
      setBusy(false);
    }
  }

  return (
    <Dialog
      title="Quién lo hace"
      subtitle={service.name}
      width={420}
      onClose={onClose}
      footer={
        <>
          <button type="button" disabled={busy} onClick={save}>
            {busy ? "Guardando…" : "Guardar"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
        </>
      }
    >
      {error && <p className="error-text">{error}</p>}
      {data.professionals.length === 0 ? (
        <p className="muted">No hay profesionales activos. Cargalos en Profesionales.</p>
      ) : (
        <div className="checklist">
          {data.professionals.map((p, i) => (
            <label key={p.id}>
              <input
                type="checkbox"
                checked={selected.has(p.id)}
                onChange={(e) => {
                  const next = new Set(selected);
                  if (e.target.checked) next.add(p.id);
                  else next.delete(p.id);
                  setSelected(next);
                }}
              />
              <Avatar name={p.name} color={serviceColor(i)} round />
              {p.name}
            </label>
          ))}
        </div>
      )}
    </Dialog>
  );
}

type SortKey = "durationMinutes" | "price";
type DialogState =
  | { kind: "form"; service: Service | null }
  | { kind: "who"; service: Service }
  | { kind: "deactivate"; service: Service }
  | null;

export default function ServicesPage() {
  const showToast = useToast();
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<ActiveFilter>("activos");
  const [sort, setSort] = useState<Sort<SortKey>>(null);
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<DialogState>(null);

  function reload() {
    loadData()
      .then((result) => {
        setError(null);
        setData(result);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los servicios."));
  }

  useEffect(reload, []);

  const header = (
    <div className="page-head">
      <div>
        <h1>Servicios</h1>
        <p>Lo que ofrecés, cuánto dura y cuánto sale.</p>
      </div>
      <div className="acts">
        <button type="button" onClick={() => setDialog({ kind: "form", service: null })}>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Nuevo servicio
        </button>
      </div>
    </div>
  );

  if (!data) {
    return (
      <div className="list-page">
        {header}
        {error ? (
          <div>
            <p className="error-text">{error}</p>
            <button type="button" onClick={reload}>
              Reintentar
            </button>
          </div>
        ) : (
          <p className="muted">Cargando…</p>
        )}
      </div>
    );
  }

  const whoDoes = (service: Service) =>
    data.professionals.filter((p) => data.assignments.get(p.id)?.includes(service.id));
  const colorOf = (service: Service) => serviceColor(data.services.indexOf(service));
  const q = query.trim().toLowerCase();
  const filtered = data.services.filter((s) => matchesActive(s.active, filter) && (!q || s.name.toLowerCase().includes(q)));
  const view = paginate(sortRows(filtered, sort, (s, key) => s[key]), page, PAGE_SIZE);
  const orphans = data.services.filter((s) => s.active && whoDoes(s).length === 0);
  const activeCount = data.services.filter((s) => s.active).length;

  return (
    <div className="list-page">
      {header}

      <div className="table-card">
        {orphans.length > 0 && (
          <WarnBar
            action={
              orphans.length === 1 && (
                <button type="button" className="link" onClick={() => setDialog({ kind: "who", service: orphans[0] })}>
                  Asignar profesional
                </button>
              )
            }
          >
            {orphans.length === 1
              ? `“${orphans[0].name}” no lo hace nadie, así que no se puede reservar.`
              : `${orphans.length} servicios no los hace nadie y no se pueden reservar: ${orphans.map((s) => s.name).join(", ")}.`}
          </WarnBar>
        )}
        <div className="table-tools">
          <SearchBox
            value={query}
            placeholder="Buscar servicio"
            onChange={(v) => {
              setQuery(v);
              setPage(0);
            }}
          />
          <ActiveChips
            value={filter}
            counts={{ active: activeCount, inactive: data.services.length - activeCount }}
            onChange={(f) => {
              setFilter(f);
              setPage(0);
            }}
          />
          <span className="count">
            {filtered.length} {filtered.length === 1 ? "resultado" : "resultados"}
          </span>
        </div>
        {error && <p className="error-text table-error">{error}</p>}

        {filtered.length === 0 ? (
          q ? (
            <TableEmpty
              icon="search"
              title={`Nada coincide con “${query.trim()}”`}
              text="Probá con menos letras, o revisá si está en Inactivos."
              action={
                <button type="button" className="secondary" onClick={() => setQuery("")}>
                  Limpiar búsqueda
                </button>
              }
            />
          ) : filter === "inactivos" ? (
            <TableEmpty icon="check" title="No hay nada dado de baja" text="Todo lo que tenés cargado está activo y disponible." />
          ) : (
            <TableEmpty
              icon="plus"
              title="Todavía no cargaste servicios"
              text="Sin servicios no se puede reservar nada. Arrancá por el que más hacés."
              action={
                <button type="button" onClick={() => setDialog({ kind: "form", service: null })}>
                  Nuevo servicio
                </button>
              }
            />
          )
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>Servicio</th>
                  <SortHeader label="Duración" sortKey="durationMinutes" sort={sort} onSort={setSort} className="num hide-sm" />
                  <SortHeader label="Precio" sortKey="price" sort={sort} onSort={setSort} className="num" />
                  <th className="hide-sm">Lo hacen</th>
                  <th className="hide-sm">Estado</th>
                  <th>
                    <span className="sr-only">Acciones</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {view.rows.map((service) => {
                  const pros = whoDoes(service);
                  return (
                    <tr key={service.id} className={service.active ? "" : "off"}>
                      <td>
                        <IdCell
                          avatar={<Avatar name={service.name} color={colorOf(service)} />}
                          title={service.name}
                          subtitle={service.description || `${service.durationMinutes} min · ${formatMoney(service.price)}`}
                        />
                      </td>
                      <td className="num hide-sm">{service.durationMinutes} min</td>
                      <td className="num">{formatMoney(service.price)}</td>
                      <td className="hide-sm">
                        {pros.length > 0 ? (
                          <div className="pills" title={pros.map((p) => p.name).join(", ")}>
                            {pros.slice(0, 3).map((p) => (
                              <span key={p.id} className="p" style={{ background: colorOf(service) }}>
                                {initials(p.name)}
                              </span>
                            ))}
                            {pros.length > 3 && <span className="p more">+{pros.length - 3}</span>}
                          </div>
                        ) : service.active ? (
                          <Badge variant="amber">Sin nadie</Badge>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td className="hide-sm">
                        <ActiveBadge active={service.active} />
                      </td>
                      <td>
                        <div className="rowacts">
                          <button
                            type="button"
                            className="secondary sm hide-sm"
                            onClick={() => setDialog({ kind: "form", service })}
                          >
                            Editar
                          </button>
                          <RowMenu
                            items={[
                              { label: "Editar", icon: "pen", onSelect: () => setDialog({ kind: "form", service }) },
                              {
                                label: "Duplicar servicio",
                                icon: "copy",
                                onSelect: () =>
                                  api
                                    .post("/services", {
                                      name: `${service.name} (copia)`,
                                      description: service.description ?? null,
                                      durationMinutes: service.durationMinutes,
                                      price: service.price,
                                    })
                                    .then(() => {
                                      showToast(`Se creó “${service.name} (copia)”`);
                                      reload();
                                    })
                                    .catch((err) =>
                                      setError(err instanceof ApiError ? err.message : "No se pudo duplicar el servicio."),
                                    ),
                              },
                              { label: "Quién lo hace", icon: "user", onSelect: () => setDialog({ kind: "who", service }) },
                              ...(service.active
                                ? [
                                    null,
                                    {
                                      label: "Dar de baja",
                                      icon: "off" as const,
                                      danger: true,
                                      onSelect: () => setDialog({ kind: "deactivate", service }),
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
            <Pager {...view} onPage={setPage} />
          </>
        )}
      </div>

      {dialog?.kind === "form" && (
        <ServiceDialog
          initial={dialog.service}
          onClose={() => setDialog(null)}
          onSaved={(name) => {
            setDialog(null);
            showToast(dialog.service ? `“${name}” actualizado` : `“${name}” creado`);
            reload();
          }}
        />
      )}
      {dialog?.kind === "who" && (
        <WhoDialog
          service={dialog.service}
          data={data}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            showToast(`Profesionales de “${dialog.service.name}” actualizados`);
            reload();
          }}
        />
      )}
      {dialog?.kind === "deactivate" && (
        <DeactivateDialog
          question={<>¿Dar de baja <b>{dialog.service.name}</b>?</>}
          effect="Deja de aparecer para reservar y para vender. Los turnos ya agendados no se tocan."
          onClose={() => setDialog(null)}
          onConfirm={async () => {
            await api.delete(`/services/${dialog.service.id}`);
            setDialog(null);
            showToast(`“${dialog.service.name}” quedó inactivo`);
            reload();
          }}
        />
      )}
    </div>
  );
}
