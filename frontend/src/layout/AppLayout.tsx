import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

const ADMIN_NAV_ITEMS = [
  { to: "/", label: "Inicio", end: true },
  { to: "/agenda", label: "Agenda" },
  { to: "/services", label: "Servicios" },
  { to: "/professionals", label: "Profesionales" },
  { to: "/customers", label: "Clientes" },
  { to: "/sales", label: "Ventas" },
  { to: "/statistics", label: "Estadísticas" },
  { to: "/settings", label: "Configuración" },
];

// Super Admin only manages establishments (spec section 7: "sin pulir"),
// none of the tenant-scoped screens above apply to that role/session.
const SUPER_ADMIN_NAV_ITEMS = [{ to: "/", label: "Establecimientos", end: true }];

export function AppLayout() {
  const { session, logout } = useAuth();
  const navItems = session?.role === "SUPER_ADMIN" ? SUPER_ADMIN_NAV_ITEMS : ADMIN_NAV_ITEMS;
  return (
    <div className="app-shell">
      <nav className="sidebar">
        <div className="sidebar-brand">Turnos</div>
        <ul>
          {navItems.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} end={item.end}>
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
        <div className="sidebar-footer">
          <div className="sidebar-user">{session?.establishmentName ?? "Super Admin"}</div>
          <button type="button" className="secondary" onClick={logout}>
            Cerrar sesión
          </button>
        </div>
      </nav>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
