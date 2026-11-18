import type { ReactNode } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ToastProvider } from "../components/Toast";
import { BrandLogo } from "../components/BrandLogo";
import { AssistantLauncher } from "./AssistantLauncher";

// Icon paths copied from the mockups' sidebar (Mockups/*.html).
function Icon({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      {children}
    </svg>
  );
}

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
}

const ADMIN_NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: "Día a día",
    items: [
      { to: "/", label: "Inicio", end: true, icon: <Icon><path d="M3 10h18M3 5h18v15H3z" /><path d="M8 3v4M16 3v4" /></Icon> },
      { to: "/agenda", label: "Agenda", icon: <Icon><path d="M4 4h16v16H4z" /><path d="M4 9h16M9 9v11" /></Icon> },
      { to: "/sales", label: "Ventas", icon: <Icon><path d="M3 7h18v12H3z" /><path d="M3 11h18M8 4v3" /></Icon> },
    ],
  },
  {
    label: "Tu negocio",
    items: [
      { to: "/services", label: "Servicios", icon: <Icon><path d="M4 6h16M4 12h16M4 18h10" /></Icon> },
      {
        to: "/professionals",
        label: "Profesionales",
        icon: <Icon><circle cx="12" cy="8" r="3.4" /><path d="M5 20c1.2-3.6 4-5.2 7-5.2S17.8 16.4 19 20" /></Icon>,
      },
      {
        to: "/customers",
        label: "Clientes",
        icon: (
          <Icon>
            <circle cx="9" cy="8" r="3" />
            <path d="M3 20c1-3.2 3.4-4.7 6-4.7S14 16.8 15 20" />
            <path d="M16 7.5a3 3 0 0 1 0 5.5M18 20c-.3-1.6-.8-2.8-1.5-3.8" />
          </Icon>
        ),
      },
    ],
  },
  {
    label: "Control",
    items: [
      { to: "/statistics", label: "Estadísticas", icon: <Icon><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></Icon> },
      {
        to: "/assistant",
        label: "Asistente",
        icon: <Icon><path d="M4 5h16v11H9l-5 4z" /><path d="M8 10h8M8 13h5" /></Icon>,
      },
      {
        to: "/settings",
        label: "Configuración",
        icon: (
          <Icon>
            <circle cx="12" cy="12" r="3" />
            <path d="M12 3v2M12 19v2M4.2 7l1.7 1M18.1 16l1.7 1M4.2 17l1.7-1M18.1 8l1.7-1" />
          </Icon>
        ),
      },
    ],
  },
];

// Super Admin only manages establishments (spec section 7: "sin pulir"),
// none of the tenant-scoped screens above apply to that role/session.
const SUPER_ADMIN_NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: "Plataforma",
    items: [
      {
        to: "/",
        label: "Establecimientos",
        end: true,
        icon: <Icon><path d="M4 20V8l8-4 8 4v12M4 20h16M9 20v-6h6v6" /></Icon>,
      },
    ],
  },
];

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0].toUpperCase())
    .join("");
}

export function AppLayout() {
  const { session, logout } = useAuth();
  const navGroups = session?.role === "SUPER_ADMIN" ? SUPER_ADMIN_NAV_GROUPS : ADMIN_NAV_GROUPS;
  const accountName = session?.establishmentName ?? "Super Admin";
  return (
    <div className="app-shell">
      <nav className="sidebar" aria-label="Principal">
        <div className="sidebar-brand">
          <BrandLogo withMark />
        </div>
        {navGroups.map((group) => (
          <div key={group.label} className="nav-group">
            <span className="nav-group-label">{group.label}</span>
            {group.items.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end}>
                {item.icon}
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}
        <div className="sidebar-footer">
          <div className="avatar" aria-hidden="true">
            {initials(accountName)}
          </div>
          <div className="sidebar-user">
            <b title={accountName}>{accountName}</b>
          </div>
          <button type="button" className="icon" onClick={logout} aria-label="Cerrar sesión" title="Cerrar sesión">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10" />
            </svg>
          </button>
        </div>
      </nav>
      <main className="main-content">
        <ToastProvider>
          <Outlet />
        </ToastProvider>
      </main>
      {session?.tenantId && <AssistantLauncher businessName={accountName} />}
    </div>
  );
}
