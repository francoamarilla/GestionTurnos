import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import LoginPage from "./auth/LoginPage";
import { AppLayout } from "./layout/AppLayout";
import { Placeholder } from "./layout/Placeholder";

function Home() {
  const { session } = useAuth();
  return <Placeholder title="Inicio" note={`Hola, ${session?.establishmentName ?? "Super Admin"} (${session?.role}).`} />;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            element={
              <RequireAuth>
                <AppLayout />
              </RequireAuth>
            }
          >
            <Route path="/" element={<Home />} />
            <Route path="/agenda" element={<Placeholder title="Agenda" />} />
            <Route path="/services" element={<Placeholder title="Servicios" />} />
            <Route path="/professionals" element={<Placeholder title="Profesionales" />} />
            <Route path="/customers" element={<Placeholder title="Clientes" />} />
            <Route path="/sales" element={<Placeholder title="Ventas" />} />
            <Route path="/statistics" element={<Placeholder title="Estadísticas" />} />
            <Route path="/settings" element={<Placeholder title="Configuración" />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
