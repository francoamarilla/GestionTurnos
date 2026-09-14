import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import LoginPage from "./auth/LoginPage";
import { AppLayout } from "./layout/AppLayout";
import { Placeholder } from "./layout/Placeholder";
import HomeDashboard from "./home/HomeDashboard";
import ServicesPage from "./services/ServicesPage";
import ProfessionalsPage from "./professionals/ProfessionalsPage";

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
            <Route path="/" element={<HomeDashboard />} />
            <Route path="/agenda" element={<Placeholder title="Agenda" />} />
            <Route path="/services" element={<ServicesPage />} />
            <Route path="/professionals" element={<ProfessionalsPage />} />
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
