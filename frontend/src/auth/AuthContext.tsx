import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, setAuthToken, setUnauthorizedHandler } from "../api/client";

export type AdminRole = "ADMIN" | "SUPER_ADMIN";

interface Session {
  accessToken: string;
  role: AdminRole;
  tenantId: string | null;
  establishmentName: string | null;
  // epoch ms - the JWT's own expiration (spec section 11: ~24h, no refresh
  // token), so we know locally when to treat the session as stale without
  // waiting for a 401.
  expiresAt: number;
}

interface LoginResponse {
  accessToken: string;
  expiresIn: number;
  role: string;
  tenantId: string | null;
  establishmentName: string | null;
}

interface AuthContextValue {
  session: Session | null;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const STORAGE_KEY = "turnos.session";

const AuthContext = createContext<AuthContextValue | null>(null);

function loadSession(): Session | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as Session;
    if (!parsed.expiresAt || parsed.expiresAt <= Date.now()) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession());

  useEffect(() => {
    setAuthToken(session?.accessToken ?? null);
  }, [session]);

  useEffect(() => {
    // Any 401 from any screen (not just an expired-JWT edge case, spec
    // section 11 has no refresh token) drops the session and, via
    // RequireAuth, bounces back to /login.
    setUnauthorizedHandler(() => {
      localStorage.removeItem(STORAGE_KEY);
      setSession(null);
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  async function login(email: string, password: string) {
    const response = await api.post<LoginResponse>("/auth/login", { email, password });
    const next: Session = {
      accessToken: response.accessToken,
      role: response.role as AdminRole,
      tenantId: response.tenantId,
      establishmentName: response.establishmentName,
      expiresAt: Date.now() + response.expiresIn * 1000,
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    setSession(next);
  }

  function logout() {
    localStorage.removeItem(STORAGE_KEY);
    setSession(null);
  }

  return <AuthContext.Provider value={{ session, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
