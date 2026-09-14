import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

const NAV_ITEMS = [
  { to: "/", label: "Inicio", end: true },
  { to: "/agenda", label: "Agenda" },
  { to: "/services", label: "Servicios" },
  { to: "/professionals", label: "Profesionales" },
  { to: "/customers", label: "Clientes" },
  { to: "/sales", label: "Ventas" },
  { to: "/statistics", label: "Estadísticas" },
  { to: "/settings", label: "Configuración" },
];

export function AppLayout() {
  const { session, logout } = useAuth();
  return (
    <div className="app-shell">
      <nav className="sidebar">
        <div className="sidebar-brand">Turnos</div>
        <ul>
          {NAV_ITEMS.map((item) => (
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
