import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import {
  addDays,
  nowInZone,
  PROFESSIONAL_COLORS,
  type DayOfWeek,
  type DaySchedule,
  type Professional,
  type Service,
} from "../agenda/model";
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
import { matchesActive, paginate, sortRows, type ActiveFilter, type Sort } from "../components/tableUtils";
import { useToast } from "../components/Toast";
import { toApiWeek, toDraftWeek, WeekRanges, weekProblem, type DraftDay } from "../components/WeekRanges";

const PAGE_SIZE = 10;

// Monday-first, as the business thinks about its week.
const WEEK: { key: DayOfWeek; label: string; short: string }[] = [
  { key: "MONDAY", label: "Lunes", short: "lun" },
  { key: "TUESDAY", label: "Martes", short: "mar" },
  { key: "WEDNESDAY", label: "Miércoles", short: "mié" },
  { key: "THURSDAY", label: "Jueves", short: "jue" },
  { key: "FRIDAY", label: "Viernes", short: "vie" },
  { key: "SATURDAY", label: "Sábado", short: "sáb" },
  { key: "SUNDAY", label: "Domingo", short: "dom" },
];

interface Row extends Professional {
  active: boolean;
  schedule: DaySchedule[];
  serviceIds: string[];
  occupancy: number | null; // last 7 days, 0..1
}

interface Data {
  rows: Row[];
  services: Service[];
}

async function loadData(): Promise<Data> {
  const [professionalsPage, services, establishment] = await Promise.all([
    // ponytail: one page of up to 100 professionals - plenty for this MVP.
    api.get<{ content: (Professional & { active: boolean })[] }>("/professionals?size=100"),
    api.get<Service[]>("/services"),
    api.get<{ timezone: string }>("/establishment"),
  ]);
  const today = nowInZone(establishment.timezone).date;
  const professionals = professionalsPage.content;
  const [schedules, assignments, summary] = await Promise.all([
    Promise.all(professionals.map((p) => api.get<DaySchedule[]>(`/professionals/${p.id}/schedule`))),
    Promise.all(professionals.map((p) => api.get<string[]>(`/professionals/${p.id}/services`))),
    api.get<{ occupancyByProfessional: { professionalId: string; rate: number }[] }>(
      `/statistics/summary?from=${addDays(today, -6)}&to=${today}`,
    ),
  ]);
  return {
    services,
    rows: professionals
      .map((p, i) => ({
        ...p,
        schedule: schedules[i],
        serviceIds: assignments[i],
        occupancy: summary.occupancyByProfessional.find((o) => o.professionalId === p.id)?.rate ?? null,
      }))
      .sort((a, b) => Number(b.active) - Number(a.active) || a.name.localeCompare(b.name)),
  };
}

// "09:00:00" -> "09", "10:30:00" -> "10:30"
function hh(time: string | null): string {
  const t = (time ?? "").slice(0, 5);
  return t.endsWith(":00") ? t.slice(0, 2) : t;
}

// "09 a 20", or "09 a 13 y 16 a 20" on a split day (#7).
function hoursLabel(day: DaySchedule): string {
  return day.ranges.map((r) => `${hh(r.openTime)} a ${hh(r.closeTime)}`).join(" y ");
}

// "Mar a sáb · 09 a 20", "Lun, mié · 10 a 18", "Lun a vie · horario variable".
function scheduleSummary(schedule: DaySchedule[]): string | null {
  const open = WEEK.map((d, i) => ({ ...d, i, day: schedule.find((s) => s.dayOfWeek === d.key) })).filter(
    (d) => d.day && d.day.ranges.length > 0,
  );
  if (open.length === 0) return null;
  const contiguous = open.every((d, k) => k === 0 || d.i === open[k - 1].i + 1);
  const days =
    open.length === 1
      ? open[0].short
      : contiguous
        ? `${open[0].short} a ${open[open.length - 1].short}`
        : open.map((d) => d.short).join(", ");
  const first = hoursLabel(open[0].day!);
  const sameHours = open.every((d) => hoursLabel(d.day!) === first);
  const label = days[0].toUpperCase() + days.slice(1);
  return sameHours ? `${label} · ${first}` : `${label} · horario variable`;
}

const colorOf = (rows: Row[], row: Row) => PROFESSIONAL_COLORS[rows.indexOf(row) % PROFESSIONAL_COLORS.length];

interface FormValues {
  name: string;
  contactPhone: string;
}

function ProfessionalDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: Row | null;
  onClose: () => void;
  onSaved: (name: string) => void;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    defaultValues: { name: initial?.name ?? "", contactPhone: initial?.contactPhone ?? "" },
  });

  async function onSubmit(values: FormValues) {
    const body = { name: values.name.trim(), contactPhone: values.contactPhone.trim() || null };
    try {
      if (initial) await api.patch(`/professionals/${initial.id}`, body);
      else await api.post("/professionals", body);
      onSaved(body.name);
    } catch (err) {
      setError("root", { message: err instanceof ApiError ? err.message : "No se pudo guardar el profesional." });
    }
  }

  return (
    <Dialog
      title={initial ? "Editar profesional" : "Nuevo profesional"}
      subtitle={initial ? initial.name : "Después definí su horario y qué servicios hace."}
      width={440}
      onClose={onClose}
      footer={
        <>
          <button type="submit" form="professional-form" disabled={isSubmitting}>
            {isSubmitting ? "Guardando…" : "Guardar"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
        </>
      }
    >
      <form id="professional-form" className="form-stack" onSubmit={handleSubmit(onSubmit)} noValidate>
        {errors.root && <p className="error-text">{errors.root.message}</p>}
        <div className="field">
          <label htmlFor="pro-name">Nombre</label>
          <input
            id="pro-name"
           
            {...register("name", { validate: (v) => v.trim() !== "" || "El nombre es obligatorio." })}
          />
          {errors.name && <span className="error-text">{errors.name.message}</span>}
        </div>
        <div className="field">
          <label htmlFor="pro-phone">Teléfono de contacto</label>
          <input id="pro-phone" type="tel" placeholder="Opcional" {...register("contactPhone")} />
        </div>
      </form>
    </Dialog>
  );
}

function ScheduleDialog({ row, onClose, onSaved }: { row: Row; onClose: () => void; onSaved: () => void }) {
  const [week, setWeek] = useState<DraftDay[]>(() => toDraftWeek(row.schedule));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const invalid = weekProblem(week);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api.patch(
        `/professionals/${row.id}/schedule`,
        toApiWeek(week),
      );
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudo guardar el horario.");
      setBusy(false);
    }
  }

  return (
    <Dialog
      title="Horario"
      subtitle={`${row.name} · puede ser más corto que el del local, nunca más largo.`}
      width={500}
      onClose={onClose}
      footer={
        <>
          <button type="button" disabled={busy || !!invalid} onClick={save}>
            {busy ? "Guardando…" : "Guardar horario"}
          </button>
          <button type="button" className="secondary" onClick={onClose}>
            Cancelar
          </button>
          <span className="spacer" />
          <button
            type="button"
            className="ghost"
            onClick={() => setWeek((current) => current.map((day) => ({ ...day, ranges: current[0].ranges })))}
          >
            Copiar lunes a todos
          </button>
        </>
      }
    >
      {error && <p className="error-text">{error}</p>}
      {invalid && <p className="error-text">{invalid}</p>}
      <WeekRanges week={week} onChange={setWeek} closedLabel="No trabaja" />
    </Dialog>
  );
}

function ServicesDialog({
  row,
  services,
  onClose,
  onSaved,
}: {
  row: Row;
  services: Service[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [selected, setSelected] = useState(new Set(row.serviceIds));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Inactive services stay listed only if already assigned, so saving
  // doesn't silently drop them.
  const listed = services.filter((s) => s.active || row.serviceIds.includes(s.id));

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api.put(`/professionals/${row.id}/services`, [...selected]);
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "No se pudieron guardar los servicios.");
      setBusy(false);
    }
  }

  return (
    <Dialog
      title="Servicios que hace"
      subtitle={row.name}
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
      {listed.length === 0 ? (
        <p className="muted">Todavía no hay servicios cargados. Cargalos en Servicios.</p>
      ) : (
        <div className="checklist">
          {listed.map((s) => (
            <label key={s.id}>
              <input
                type="checkbox"
                checked={selected.has(s.id)}
                onChange={(e) => {
                  const next = new Set(selected);
                  if (e.target.checked) next.add(s.id);
                  else next.delete(s.id);
                  setSelected(next);
                }}
              />
              <span>
                {s.name}
                {!s.active && <span className="muted"> · inactivo</span>}
              </span>
              <small>{s.durationMinutes} min</small>
            </label>
          ))}
        </div>
      )}
    </Dialog>
  );
}

type SortKey = "occupancy";
type DialogState =
  | { kind: "form"; row: Row | null }
  | { kind: "schedule"; row: Row }
  | { kind: "services"; row: Row }
  | { kind: "deactivate"; row: Row }
  | null;

export default function ProfessionalsPage() {
  const navigate = useNavigate();
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
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los profesionales."));
  }

  useEffect(reload, []);

  // Backlog item 1: PATCH accepts `active`, so alguien dado de baja vuelve
  // sin re-crearlo (DELETE only sets active=false).
  function reactivate(row: Row) {
    api
      .patch(`/professionals/${row.id}`, { active: true })
      .then(() => {
        showToast(`${row.name} volvió a estar activo`);
        reload();
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudo activar al profesional."));
  }

  const header = (
    <div className="page-head">
      <div>
        <h1>Profesionales</h1>
        <p>Quiénes atienden, su horario y qué hacen.</p>
      </div>
      <div className="acts">
        <button type="button" onClick={() => setDialog({ kind: "form", row: null })}>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Nuevo profesional
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

  const activeServiceIds = new Set(data.services.filter((s) => s.active).map((s) => s.id));
  const q = query.trim().toLowerCase();
  const qDigits = q.replace(/\D/g, "");
  const filtered = data.rows.filter(
    (r) =>
      matchesActive(r.active, filter) &&
      (!q ||
        r.name.toLowerCase().includes(q) ||
        (qDigits.length > 1 && (r.contactPhone ?? "").replace(/\D/g, "").includes(qDigits))),
  );
  const view = paginate(sortRows(filtered, sort, (r) => r.occupancy ?? -1), page, PAGE_SIZE);
  const activeCount = data.rows.filter((r) => r.active).length;
  const incomplete = data.rows.filter(
    (r) => r.active && (!scheduleSummary(r.schedule) || !r.serviceIds.some((id) => activeServiceIds.has(id))),
  );

  return (
    <div className="list-page">
      {header}

      <div className="table-card">
        {incomplete.length > 0 && (
          <WarnBar>
            {incomplete.length === 1
              ? `${incomplete[0].name} no tiene horario o servicios, así que no aparece para reservar.`
              : `${incomplete.length} profesionales no tienen horario o servicios y no aparecen para reservar.`}
          </WarnBar>
        )}
        <div className="table-tools">
          <SearchBox
            value={query}
            placeholder="Buscar profesional"
            onChange={(v) => {
              setQuery(v);
              setPage(0);
            }}
          />
          <ActiveChips
            value={filter}
            counts={{ active: activeCount, inactive: data.rows.length - activeCount }}
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
            <TableEmpty icon="check" title="No hay nadie dado de baja" text="Todos los profesionales cargados están activos." />
          ) : (
            <TableEmpty
              icon="plus"
              title="Todavía no cargaste profesionales"
              text="Cargá al menos una persona para que la agenda tenga una columna."
              action={
                <button type="button" onClick={() => setDialog({ kind: "form", row: null })}>
                  Nuevo profesional
                </button>
              }
            />
          )
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>Profesional</th>
                  <th className="hide-sm">Horario</th>
                  <th className="hide-sm">Servicios</th>
                  <SortHeader label="Ocupación 7 días" sortKey="occupancy" sort={sort} onSort={setSort} className="hide-sm" />
                  <th className="hide-sm">Estado</th>
                  <th>
                    <span className="sr-only">Acciones</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {view.rows.map((row) => {
                  const color = colorOf(data.rows, row);
                  const hours = scheduleSummary(row.schedule);
                  const count = row.serviceIds.filter((id) => activeServiceIds.has(id)).length;
                  const pct = Math.round((row.occupancy ?? 0) * 100);
                  return (
                    <tr key={row.id} className={row.active ? "" : "off"}>
                      <td>
                        <IdCell
                          avatar={<Avatar name={row.name} color={color} round />}
                          title={row.name}
                          subtitle={row.contactPhone || "Sin teléfono"}
                        />
                      </td>
                      <td className="hide-sm">
                        {hours ?? (row.active ? <Badge variant="amber">Sin horario</Badge> : <span className="muted">—</span>)}
                      </td>
                      <td className="hide-sm">
                        {count > 0 ? (
                          `${count} ${count === 1 ? "servicio" : "servicios"}`
                        ) : row.active ? (
                          <Badge variant="amber">Ninguno</Badge>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td className="hide-sm">
                        {row.active ? (
                          <span className="occ-cell">
                            <span className="track" role="meter" aria-label={`Ocupación de ${row.name}`} aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
                              <i style={{ width: `${Math.min(pct, 100)}%`, background: color }} />
                            </span>
                            {pct}%
                          </span>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td className="hide-sm">
                        <ActiveBadge active={row.active} />
                      </td>
                      <td>
                        <div className="rowacts">
                          <button type="button" className="secondary sm hide-sm" onClick={() => setDialog({ kind: "form", row })}>
                            Editar
                          </button>
                          <RowMenu
                            items={[
                              { label: "Editar", icon: "pen", onSelect: () => setDialog({ kind: "form", row }) },
                              { label: "Definir horario", icon: "clock", onSelect: () => setDialog({ kind: "schedule", row }) },
                              { label: "Servicios que hace", icon: "list", onSelect: () => setDialog({ kind: "services", row }) },
                              ...(row.active
                                ? [
                                    {
                                      label: "Ver su agenda",
                                      icon: "cal" as const,
                                      onSelect: () => navigate("/agenda", { state: { professionalId: row.id } }),
                                    },
                                    null,
                                    {
                                      label: "Dar de baja",
                                      icon: "off" as const,
                                      danger: true,
                                      onSelect: () => setDialog({ kind: "deactivate", row }),
                                    },
                                  ]
                                : [
                                    null,
                                    {
                                      label: "Volver a activar",
                                      icon: "on" as const,
                                      onSelect: () => reactivate(row),
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
            <Pager {...view} onPage={setPage} />
          </>
        )}
      </div>

      {dialog?.kind === "form" && (
        <ProfessionalDialog
          initial={dialog.row}
          onClose={() => setDialog(null)}
          onSaved={(name) => {
            setDialog(null);
            showToast(dialog.row ? `${name} actualizado` : `${name} creado. Definí su horario y servicios.`);
            reload();
          }}
        />
      )}
      {dialog?.kind === "schedule" && (
        <ScheduleDialog
          row={dialog.row}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            showToast(`Horario de ${dialog.row.name} guardado`);
            reload();
          }}
        />
      )}
      {dialog?.kind === "services" && (
        <ServicesDialog
          row={dialog.row}
          services={data.services}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            showToast(`Servicios de ${dialog.row.name} guardados`);
            reload();
          }}
        />
      )}
      {dialog?.kind === "deactivate" && (
        <DeactivateDialog
          question={<>¿Dar de baja a <b>{dialog.row.name}</b>?</>}
          effect="Deja de aparecer en la agenda y para reservar. Sus turnos ya agendados no se tocan."
          onClose={() => setDialog(null)}
          onConfirm={async () => {
            await api.delete(`/professionals/${dialog.row.id}`);
            setDialog(null);
            showToast(`${dialog.row.name} quedó inactivo`);
            reload();
          }}
        />
      )}
    </div>
  );
}
