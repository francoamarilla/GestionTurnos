import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import LoginPage from "./auth/LoginPage";

function Home() {
  const { session, logout } = useAuth();
  return (
    <div style={{ padding: 32 }}>
      <h1>Turnos</h1>
      <p>
        Hola, {session?.establishmentName ?? "Super Admin"} ({session?.role}). Dashboard en construcción.
      </p>
      <button type="button" className="secondary" onClick={logout}>
        Cerrar sesión
      </button>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <RequireAuth>
                <Home />
              </RequireAuth>
            }
          />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
