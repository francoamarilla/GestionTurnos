import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";

interface TenantResponse {
  id: string;
  businessName: string;
  status: "ACTIVE" | "SUSPENDED" | "TRIAL";
  whatsAppStatus: string | null;
}

const STATUS_LABEL: Record<TenantResponse["status"], string> = {
  ACTIVE: "Activo",
  SUSPENDED: "Suspendido",
  TRIAL: "En prueba",
};

// spec section 7: "Pantalla mínima... No se invierte en una UI pulida para
// este rol en el MVP" - a read-only list, no ABM here.
export default function TenantsAdminPage() {
  const [tenants, setTenants] = useState<TenantResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<TenantResponse[]>("/admin/tenants")
      .then(setTenants)
      .catch((err) => setError(err instanceof ApiError ? err.message : "No se pudieron cargar los establecimientos."));
  }, []);

  return (
    <div>
      <h1>Establecimientos</h1>
      {error && <p className="error-text">{error}</p>}
      {tenants === null ? (
        <p>Cargando...</p>
      ) : tenants.length === 0 ? (
        <p>Todavía no hay establecimientos cargados.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Nombre</th>
              <th>Estado</th>
              <th>WhatsApp</th>
            </tr>
          </thead>
          <tbody>
            {tenants.map((t) => (
              <tr key={t.id}>
                <td>{t.businessName}</td>
                <td>{STATUS_LABEL[t.status]}</td>
                <td>{t.whatsAppStatus ?? "No configurado"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
