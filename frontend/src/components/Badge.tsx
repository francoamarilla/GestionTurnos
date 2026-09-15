type BadgeVariant = "success" | "warning" | "error" | "neutral";

export function Badge({ variant, children }: { variant: BadgeVariant; children: React.ReactNode }) {
  return <span className={`badge badge-${variant}`}>{children}</span>;
}

export type AppointmentStatus = "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";

// success = Confirmado, warning = No asistió, error = Cancelado,
// neutral = Completado - the app's own semantic mapping, not a generic
// "green means done" convention.
const APPOINTMENT_STATUS: Record<AppointmentStatus, { variant: BadgeVariant; label: string }> = {
  CONFIRMED: { variant: "success", label: "Confirmado" },
  COMPLETED: { variant: "neutral", label: "Completado" },
  CANCELLED: { variant: "error", label: "Cancelado" },
  NO_SHOW: { variant: "warning", label: "No asistió" },
};

export function AppointmentStatusBadge({ status }: { status: AppointmentStatus }) {
  const { variant, label } = APPOINTMENT_STATUS[status];
  return <Badge variant={variant}>{label}</Badge>;
}

export function ActiveBadge({ active }: { active: boolean }) {
  return <Badge variant={active ? "success" : "neutral"}>{active ? "Activo" : "Inactivo"}</Badge>;
}
