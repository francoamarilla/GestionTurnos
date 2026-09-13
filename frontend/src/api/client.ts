// Thin fetch wrapper (spec section 9 leaves the HTTP client choice open -
// picking plain fetch, zero extra dependency) shared by every screen.
// Errors are RFC 7807 application/problem+json (spec section 8), parsed
// into ApiError so callers can show `detail`/`errors` without re-parsing.
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: Array<{ field?: string; message: string }>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | null;

  constructor(status: number, problem: ProblemDetail | null, message: string) {
    super(message);
    this.status = status;
    this.problem = problem;
  }
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "/api";

let authToken: string | null = null;

export function setAuthToken(token: string | null): void {
  authToken = token;
}

export async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (authToken) {
    headers.set("Authorization", `Bearer ${authToken}`);
  }

  const response = await fetch(`${BASE_URL}${path}`, { ...options, headers });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const data: unknown = text ? JSON.parse(text) : null;

  if (!response.ok) {
    const problem = (data && typeof data === "object" ? (data as ProblemDetail) : null) ?? null;
    throw new ApiError(response.status, problem, problem?.detail ?? problem?.title ?? response.statusText);
  }

  return data as T;
}

export const api = {
  get: <T>(path: string) => apiFetch<T>(path),
  post: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: "POST", body: body !== undefined ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: "PATCH", body: body !== undefined ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string) => apiFetch<T>(path, { method: "DELETE" }),
};
