// Hues match the mockup tokens (tokens.css).
type BadgeVariant = "brick" | "pine" | "amber" | "slate";

export function Badge({ variant, children }: { variant: BadgeVariant; children: React.ReactNode }) {
  return (
    <span className={`badge badge-${variant}`}>
      <i aria-hidden="true" />
      {children}
    </span>
  );
}

export type AppointmentStatus = "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";

// Mapping from Mockups/agenda-mockup.html: brick = Confirmado,
// pine = Completado, amber = No asistió, slate = Cancelado.
const APPOINTMENT_STATUS: Record<AppointmentStatus, { variant: BadgeVariant; label: string }> = {
  CONFIRMED: { variant: "brick", label: "Confirmado" },
  COMPLETED: { variant: "pine", label: "Completado" },
  CANCELLED: { variant: "slate", label: "Cancelado" },
  NO_SHOW: { variant: "amber", label: "No asistió" },
};

export function AppointmentStatusBadge({ status }: { status: AppointmentStatus }) {
  const { variant, label } = APPOINTMENT_STATUS[status];
  return <Badge variant={variant}>{label}</Badge>;
}

// Mapping from Mockups/tablas-mockup.html: .badge.on / .badge.off.
export function ActiveBadge({ active }: { active: boolean }) {
  return <Badge variant={active ? "pine" : "slate"}>{active ? "Activo" : "Inactivo"}</Badge>;
}
