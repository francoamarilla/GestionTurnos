import { api } from "../api/client";

// VOIDED = a charged appointment whose sale was annulled from Ventas.
export type AppointmentStatus = "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW" | "VOIDED";
export type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

export interface Professional {
  id: string;
  name: string;
  contactPhone?: string | null;
  active?: boolean;
}

export interface Service {
  id: string;
  name: string;
  description?: string | null;
  durationMinutes: number;
  price: number;
  active: boolean;
}

export interface Customer {
  id: string;
  name: string | null;
  phone: string | null;
  internalNotes?: string | null;
  active: boolean;
}

export interface Appointment {
  id: string;
  professionalId: string;
  serviceId: string | null; // null = sale off the catalog ("Otro")...
  description: string | null; // ...named by this instead
  customerId: string | null;
  startTime: string; // OffsetDateTime, already in the tenant's own timezone
  durationMinutes: number;
  price: number;
  status: AppointmentStatus;
  origin: "BOT" | "DASHBOARD" | "DIRECT_SALE";
  paymentMethod: PaymentMethod | null; // null = not recorded
}

export type PaymentMethod = "CASH" | "TRANSFER" | "CARD";

export interface TimeRange {
  openTime: string; // HH:mm:ss
  closeTime: string;
}

// A day with no ranges isn't worked; more than one range is a split day.
export interface DaySchedule {
  dayOfWeek: DayOfWeek;
  ranges: TimeRange[];
}

interface Page<T> {
  content: T[];
}

export interface Catalog {
  timezone: string;
  branchSchedule: DaySchedule[];
  closures: { date: string; reason: string | null }[];
  professionals: Professional[];
  // Per professional: weekly schedule and the service ids they offer.
  schedules: Map<string, DaySchedule[]>;
  assignments: Map<string, string[]>;
  services: Service[];
  customers: Customer[];
}

export async function loadCatalog(): Promise<Catalog> {
  const [establishment, closures, professionalsPage, services, customersPage] = await Promise.all([
    api.get<{ timezone: string; schedule: DaySchedule[] }>("/establishment"),
    api.get<{ date: string; reason: string | null }[]>("/establishment/closures"),
    api.get<Page<Professional>>("/professionals?active=true&size=100"),
    api.get<Service[]>("/services"),
    // ponytail: one page of up to 200 customers to label appointments -
    // same ceiling used elsewhere; the booking combobox searches server-side.
    api.get<Page<Customer>>("/customers?size=200"),
  ]);
  const professionals = professionalsPage.content;
  const [schedules, assignments] = await Promise.all([
    Promise.all(professionals.map((p) => api.get<DaySchedule[]>(`/professionals/${p.id}/schedule`))),
    Promise.all(professionals.map((p) => api.get<string[]>(`/professionals/${p.id}/services`))),
  ]);
  return {
    timezone: establishment.timezone,
    branchSchedule: establishment.schedule,
    closures,
    professionals,
    schedules: new Map(professionals.map((p, i) => [p.id, schedules[i]])),
    assignments: new Map(professionals.map((p, i) => [p.id, assignments[i]])),
    services,
    customers: customersPage.content,
  };
}

export async function loadAppointments(date: string): Promise<Appointment[]> {
  const list = await api.get<Appointment[]>(`/appointments?from=${date}&to=${date}`);
  // Direct sales are stored as appointments but aren't part of the agenda.
  return list.filter((a) => a.origin !== "DIRECT_SALE").sort((a, b) => a.startTime.localeCompare(b.startTime));
}

export const STATUS_CLASS: Record<AppointmentStatus, string> = {
  CONFIRMED: "confirmado",
  COMPLETED: "completado",
  NO_SHOW: "noshow",
  CANCELLED: "cancelado",
  VOIDED: "cancelado", // freed the slot, same look as cancelled
};

export const STATUS_LABEL: Record<AppointmentStatus, string> = {
  CONFIRMED: "Confirmado",
  COMPLETED: "Completado",
  NO_SHOW: "No asistió",
  CANCELLED: "Cancelado",
  VOIDED: "Anulado",
};

// Professional column accents, cycled in list order.
export const PROFESSIONAL_COLORS = ["var(--brick)", "var(--pine)", "var(--amber)", "var(--slate)"];

const DAY_OF_WEEK_BY_JS_INDEX: DayOfWeek[] = [
  "SUNDAY",
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
];
const DAY_NAMES = ["Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"];
const MONTH_NAMES = [
  "enero",
  "febrero",
  "marzo",
  "abril",
  "mayo",
  "junio",
  "julio",
  "agosto",
  "septiembre",
  "octubre",
  "noviembre",
  "diciembre",
];

function parseIsoDate(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(iso: string, days: number): string {
  const date = parseIsoDate(iso);
  date.setDate(date.getDate() + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

export function dayOfWeek(iso: string): DayOfWeek {
  return DAY_OF_WEEK_BY_JS_INDEX[parseIsoDate(iso).getDay()];
}

// "Martes 15 de septiembre"
export function longDate(iso: string): string {
  const date = parseIsoDate(iso);
  return `${DAY_NAMES[date.getDay()]} ${date.getDate()} de ${MONTH_NAMES[date.getMonth()]}`;
}

// Today's date and minutes-since-midnight in the tenant's timezone, not the
// browser's: the appointment times the API returns are in that zone.
export function nowInZone(timezone: string): { date: string; minutes: number } {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(new Date())
      .map((p) => [p.type, p.value]),
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, minutes: Number(parts.hour) * 60 + Number(parts.minute) };
}

export function timeOf(iso: string): string {
  return iso.slice(11, 16);
}

export function toMinutes(time: string): number {
  const [h, m] = time.slice(0, 5).split(":").map(Number);
  return h * 60 + m;
}

export function toTime(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

// The day's ranges in minutes, sorted. Empty = not worked that day.
export function dayRanges(schedule: DaySchedule[] | undefined, iso: string): { open: number; close: number }[] {
  const day = schedule?.find((s) => s.dayOfWeek === dayOfWeek(iso));
  return (day?.ranges ?? [])
    .map((r) => ({ open: toMinutes(r.openTime), close: toMinutes(r.closeTime) }))
    .sort((a, b) => a.open - b.open);
}

// First open to last close, gaps included: what a board or a summary spans.
// Null when the day isn't worked (same rule the backend's availability uses).
export function dayWindow(schedule: DaySchedule[] | undefined, iso: string): { open: number; close: number } | null {
  const ranges = dayRanges(schedule, iso);
  if (ranges.length === 0) return null;
  return { open: ranges[0].open, close: ranges[ranges.length - 1].close };
}

// Inside one of the day's ranges - the midday gap of a split day is not.
export function isWorked(schedule: DaySchedule[] | undefined, iso: string, minutes: number): boolean {
  return dayRanges(schedule, iso).some((r) => minutes >= r.open && minutes < r.close);
}

// "9:00 a 13:00 y 16:00 a 20:00"
export function rangesLabel(schedule: DaySchedule[] | undefined, iso: string): string | null {
  const ranges = dayRanges(schedule, iso);
  if (ranges.length === 0) return null;
  return ranges.map((r) => `${toTime(r.open)} a ${toTime(r.close)}`).join(" y ");
}

export function customerLabel(customer: Customer | undefined): string {
  return customer?.name ?? customer?.phone ?? "Sin cliente";
}
