import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import LoginPage from "./auth/LoginPage";
import { AppLayout } from "./layout/AppLayout";
import HomeDashboard from "./home/HomeDashboard";
import ServicesPage from "./services/ServicesPage";
import ProfessionalsPage from "./professionals/ProfessionalsPage";
import CustomersPage from "./customers/CustomersPage";
import AgendaPage from "./agenda/AgendaPage";
import SalesPage from "./sales/SalesPage";
import StatisticsPage from "./statistics/StatisticsPage";
import SettingsPage from "./settings/SettingsPage";
import ChatPage from "./chat/ChatPage";
import AssistantPage from "./assistant/AssistantPage";

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/chat/:tenantId" element={<ChatPage />} />
          <Route
            element={
              <RequireAuth>
                <AppLayout />
              </RequireAuth>
            }
          >
            <Route path="/" element={<HomeDashboard />} />
            <Route path="/agenda" element={<AgendaPage />} />
            <Route path="/services" element={<ServicesPage />} />
            <Route path="/professionals" element={<ProfessionalsPage />} />
            <Route path="/customers" element={<CustomersPage />} />
            <Route path="/sales" element={<SalesPage />} />
            <Route path="/statistics" element={<StatisticsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/assistant" element={<AssistantPage />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
