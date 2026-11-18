// Hues match tokens.css: primary = brand accent, the rest are status colors.
type BadgeVariant = "primary" | "brick" | "pine" | "amber" | "slate";

export function Badge({ variant, children }: { variant: BadgeVariant; children: React.ReactNode }) {
  return (
    <span className={`badge badge-${variant}`}>
      <i aria-hidden="true" />
      {children}
    </span>
  );
}

// VOIDED = a charged appointment whose sale was annulled from Ventas.
export type AppointmentStatus = "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW" | "VOIDED";

// Mapping from Mockups/agenda-mockup.html, with the brand accent in place
// of the old brick: primary = Confirmado, pine = Completado,
// amber = No asistió, slate = Cancelado.
const APPOINTMENT_STATUS: Record<AppointmentStatus, { variant: BadgeVariant; label: string }> = {
  CONFIRMED: { variant: "primary", label: "Confirmado" },
  COMPLETED: { variant: "pine", label: "Completado" },
  CANCELLED: { variant: "slate", label: "Cancelado" },
  NO_SHOW: { variant: "amber", label: "No asistió" },
  VOIDED: { variant: "slate", label: "Anulado" },
};

export function AppointmentStatusBadge({ status }: { status: AppointmentStatus }) {
  const { variant, label } = APPOINTMENT_STATUS[status];
  return <Badge variant={variant}>{label}</Badge>;
}

// Mapping from Mockups/tablas-mockup.html: .badge.on / .badge.off.
export function ActiveBadge({ active }: { active: boolean }) {
  return <Badge variant={active ? "pine" : "slate"}>{active ? "Activo" : "Inactivo"}</Badge>;
}
