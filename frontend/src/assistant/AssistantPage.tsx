import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { Badge } from "../components/Badge";
import { ChatThread, type ChatReply } from "../chat/ChatThread";

// The owner's AI assistant screen (propuesta section 7 and 10): how it talks
// and what it may do (versioned: edit a draft, try it, publish), what it
// talked about, and what it cost this month.
interface ConfigView {
  version: number;
  status: string;
  assistantName: string;
  tone: "FORMAL" | "CERCANO" | "CANCHERO";
  voseo: boolean;
  emojiLevel: "NONE" | "LOW" | "HIGH";
  greeting: string | null;
  customInstructions: string | null;
  policies: string | null;
  handoffInstructions: string | null;
  canBook: boolean;
  canCancel: boolean;
  canReschedule: boolean;
  confirmationMode: "ALWAYS" | "STANDARD" | "DATE_TIME_ONLY";
  aiEnabled: boolean;
  monthlyBudgetUsd: number;
}

interface ConfigResponse {
  published: ConfigView;
  draft: ConfigView | null;
  versions: { version: number; status: string; createdAt: string; publishedAt: string | null }[];
}

interface Usage {
  monthCostUsd: number;
  monthlyBudgetUsd: number;
  percentOfBudget: number;
  nearLimit: boolean;
  overLimit: boolean;
  turns: number;
  bookings: number;
  handoffs: number;
}

interface ConversationSummary {
  id: string;
  mode: "AI" | "FALLBACK" | "HUMAN";
  customerName: string | null;
  customerPhone: string | null;
  lastMessage: string;
  lastMessageAt: string;
  handoffReason: string | null;
}

interface MessageView {
  role: "USER" | "ASSISTANT";
  content: string;
  createdAt: string;
}

const MODE_LABEL = { AI: "IA", FALLBACK: "Menú", HUMAN: "Derivada" } as const;

function message(e: unknown) {
  return e instanceof ApiError ? e.message : "Algo salió mal.";
}

export default function AssistantPage() {
  const { session } = useAuth();
  const chatLink = session?.tenantId ? `${window.location.origin}/chat/${session.tenantId}` : null;

  return (
    <div className="settings-page assistant-page">
      <div className="page-head">
        <div>
          <h1>Asistente</h1>
          <p>Cómo atiende tu asistente de IA, qué puede hacer y cómo le está yendo.</p>
        </div>
      </div>
      <div className="sections">
        <UsagePanel chatLink={chatLink} />
        <ConfigPanel />
        <PlaygroundPanel />
        <ConversationsPanel />
      </div>
    </div>
  );
}

function UsagePanel({ chatLink }: { chatLink: string | null }) {
  const [usage, setUsage] = useState<Usage | null>(null);
  useEffect(() => {
    api.get<Usage>("/assistant/usage").then(setUsage, () => setUsage(null));
  }, []);
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Este mes</h2>
          {chatLink && (
            <p>
              Link del chat para tus clientes: <a href={chatLink} target="_blank" rel="noreferrer">{chatLink}</a>
            </p>
          )}
        </div>
      </div>
      <div className="panel-body">
        {!usage ? (
          <p className="muted">Cargando…</p>
        ) : (
          <div className="assistant-stats">
            <div>
              <strong>US$ {Number(usage.monthCostUsd).toFixed(2)}</strong>
              <span className="muted">de US$ {Number(usage.monthlyBudgetUsd).toFixed(2)} ({usage.percentOfBudget}%)</span>
              {usage.overLimit ? (
                <Badge variant="brick">Sin presupuesto: atiende el menú</Badge>
              ) : usage.nearLimit ? (
                <Badge variant="amber">Cerca del límite</Badge>
              ) : null}
            </div>
            <div><strong>{usage.turns}</strong><span className="muted">mensajes respondidos</span></div>
            <div><strong>{usage.bookings}</strong><span className="muted">turnos reservados por la IA</span></div>
            <div><strong>{usage.handoffs}</strong><span className="muted">derivadas a una persona</span></div>
          </div>
        )}
      </div>
    </section>
  );
}

function ConfigPanel() {
  const [data, setData] = useState<ConfigResponse | null>(null);
  const [form, setForm] = useState<ConfigView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback((r: ConfigResponse) => {
    setData(r);
    setForm(r.draft ?? r.published);
  }, []);

  useEffect(() => {
    api.get<ConfigResponse>("/assistant/config").then(load, (e) => setError(message(e)));
  }, [load]);

  async function run(action: () => Promise<ConfigResponse>, done: string) {
    setSaving(true);
    setError(null);
    try {
      load(await action());
      setNotice(done);
    } catch (e) {
      setError(message(e));
    } finally {
      setSaving(false);
    }
  }

  if (!form || !data) {
    return (
      <section className="panel">
        <div className="panel-body">{error ? <p className="error-text">{error}</p> : <p className="muted">Cargando…</p>}</div>
      </section>
    );
  }

  const set = <K extends keyof ConfigView>(key: K, value: ConfigView[K]) => setForm({ ...form, [key]: value });
  const text = (key: "greeting" | "customInstructions" | "policies" | "handoffInstructions") => form[key] ?? "";

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Configuración</h2>
          <p>
            Publicada: versión {data.published.version || "inicial"}.{" "}
            {data.draft ? `Tenés un borrador (versión ${data.draft.version}) sin publicar.` : "Los cambios se guardan como borrador."}
          </p>
        </div>
      </div>
      <div className="panel-body">
        {error && <p className="error-text">{error}</p>}
        {notice && <p className="muted">{notice}</p>}
        <div className="field-row">
          <label className="field">
            <span>Nombre del asistente</span>
            <input value={form.assistantName} maxLength={100} onChange={(e) => set("assistantName", e.target.value)} />
          </label>
          <label className="field">
            <span>Tono</span>
            <select value={form.tone} onChange={(e) => set("tone", e.target.value as ConfigView["tone"])}>
              <option value="FORMAL">Formal</option>
              <option value="CERCANO">Cercano</option>
              <option value="CANCHERO">Canchero</option>
            </select>
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>Emojis</span>
            <select value={form.emojiLevel} onChange={(e) => set("emojiLevel", e.target.value as ConfigView["emojiLevel"])}>
              <option value="NONE">Ninguno</option>
              <option value="LOW">Pocos</option>
              <option value="HIGH">Muchos</option>
            </select>
          </label>
          <label className="field">
            <span>Cuándo pide confirmación antes de reservar</span>
            <select
              value={form.confirmationMode}
              onChange={(e) => set("confirmationMode", e.target.value as ConfigView["confirmationMode"])}
            >
              <option value="STANDARD">Si el pedido no es preciso (recomendado)</option>
              <option value="DATE_TIME_ONLY">Solo si hay dudas con el día o la hora</option>
              <option value="ALWAYS">Siempre</option>
            </select>
          </label>
        </div>
        <div className="checklist">
          <label><input type="checkbox" checked={form.voseo} onChange={(e) => set("voseo", e.target.checked)} /> Usar voseo (vos, tenés)</label>
          <label><input type="checkbox" checked={form.canBook} onChange={(e) => set("canBook", e.target.checked)} /> Puede reservar turnos</label>
          <label><input type="checkbox" checked={form.canCancel} onChange={(e) => set("canCancel", e.target.checked)} /> Puede cancelar turnos</label>
          <label><input type="checkbox" checked={form.canReschedule} onChange={(e) => set("canReschedule", e.target.checked)} /> Puede reprogramar turnos</label>
          <label><input type="checkbox" checked={form.aiEnabled} onChange={(e) => set("aiEnabled", e.target.checked)} /> Asistente de IA activo (apagado = menú de botones)</label>
        </div>
        <label className="field">
          <span>Saludo</span>
          <input value={text("greeting")} maxLength={500} placeholder="¡Hola! Soy Tito, ¿en qué te ayudo?"
            onChange={(e) => set("greeting", e.target.value)} />
        </label>
        <label className="field">
          <span>Políticas del local</span>
          <textarea rows={3} value={text("policies")} maxLength={2000}
            placeholder="Tolerancia de 10 minutos. Se cobra seña para coloración."
            onChange={(e) => set("policies", e.target.value)} />
        </label>
        <label className="field">
          <span>Cuándo derivar a una persona</span>
          <textarea rows={2} value={text("handoffInstructions")} maxLength={1000}
            placeholder="Si preguntan por eventos o grupos grandes."
            onChange={(e) => set("handoffInstructions", e.target.value)} />
        </label>
        <label className="field">
          <span>Otras indicaciones</span>
          <textarea rows={3} value={text("customInstructions")} maxLength={2000}
            placeholder="Si preguntan por estacionamiento, hay uno en la esquina."
            onChange={(e) => set("customInstructions", e.target.value)} />
          <span className="help">No pueden saltear las reglas de la plataforma: el asistente nunca inventa horarios ni precios.</span>
        </label>
        <label className="field">
          <span>Presupuesto mensual (US$)</span>
          <input type="number" min={0} max={1000} step={1} value={form.monthlyBudgetUsd}
            onChange={(e) => set("monthlyBudgetUsd", Number(e.target.value))} />
          <span className="help">Al llegar al 100%, el chat pasa al menú de botones hasta el mes siguiente.</span>
        </label>
        <div className="form-actions">
          <button type="button" className="secondary" disabled={saving}
            onClick={() => run(() => api.put<ConfigResponse>("/assistant/config/draft", form), "Borrador guardado. Probalo abajo antes de publicar.")}>
            Guardar borrador
          </button>
          {data.draft && (
            <>
              <button type="button" disabled={saving}
                onClick={() => run(() => api.post<ConfigResponse>("/assistant/config/publish"), "Publicado: tus clientes ya ven esta versión.")}>
                Publicar
              </button>
              <button type="button" className="secondary" disabled={saving}
                onClick={() => run(() => api.delete<ConfigResponse>("/assistant/config/draft"), "Borrador descartado.")}>
                Descartar borrador
              </button>
            </>
          )}
        </div>
      </div>
    </section>
  );
}

function PlaygroundPanel() {
  const [sessionId, setSessionId] = useState(() => crypto.randomUUID());
  const send = (message: string | null): Promise<ChatReply> =>
    api.post<ChatReply>("/assistant/playground", { sessionId, message: message ?? "" });
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Probar</h2>
          <p>Chateá con el borrador (o la versión publicada). Las reservas y cancelaciones acá son simuladas.</p>
        </div>
        <button type="button" className="secondary sm" onClick={() => setSessionId(crypto.randomUUID())}>
          Nueva prueba
        </button>
      </div>
      <div className="panel-body">
        <ChatThread key={sessionId} send={send} placeholder="Escribí como si fueras un cliente" />
      </div>
    </section>
  );
}

function ConversationsPanel() {
  const [onlyHandoffs, setOnlyHandoffs] = useState(false);
  const [rows, setRows] = useState<ConversationSummary[] | null>(null);
  const [open, setOpen] = useState<{ id: string; messages: MessageView[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<ConversationSummary[]>(`/assistant/conversations${onlyHandoffs ? "?mode=HUMAN" : ""}`)
      .then(setRows, (e) => setError(message(e)));
  }, [onlyHandoffs]);
  useEffect(() => {
    load();
  }, [load]);

  async function show(id: string) {
    setOpen({ id, messages: await api.get<MessageView[]>(`/assistant/conversations/${id}/messages`) });
  }

  async function resume(id: string) {
    await api.post(`/assistant/conversations/${id}/resume`);
    setOpen(null);
    load();
  }

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Conversaciones</h2>
          <p>Lo que hablaron tus clientes con el asistente. Las derivadas esperan que alguien del local responda.</p>
        </div>
        <label className="inline-check">
          <input type="checkbox" checked={onlyHandoffs} onChange={(e) => setOnlyHandoffs(e.target.checked)} /> Solo derivadas
        </label>
      </div>
      <div className="panel-body">
        {error && <p className="error-text">{error}</p>}
        {!rows ? (
          <p className="muted">Cargando…</p>
        ) : rows.length === 0 ? (
          <div className="table-empty">Todavía no hay conversaciones.</div>
        ) : (
          <ul className="conversation-list">
            {rows.map((c) => (
              <li key={c.id}>
                <button type="button" className="link" onClick={() => void show(c.id)}>
                  <strong>{c.customerName ?? c.customerPhone ?? "Cliente sin identificar"}</strong>
                  <span className="muted">{new Date(c.lastMessageAt).toLocaleString("es-AR", { dateStyle: "short", timeStyle: "short", hour12: false })}</span>
                </button>
                <Badge variant={c.mode === "HUMAN" ? "brick" : c.mode === "FALLBACK" ? "amber" : "pine"}>
                  {MODE_LABEL[c.mode]}
                </Badge>
                <p className="muted">{c.handoffReason ? `Motivo: ${c.handoffReason}` : c.lastMessage}</p>
                {open?.id === c.id && (
                  <div className="conversation-messages">
                    {open.messages.map((m, i) => (
                      <p key={i} className={`chat-bubble ${m.role === "USER" ? "user" : "assistant"}`}>{m.content}</p>
                    ))}
                    {c.mode === "HUMAN" && (
                      <button type="button" className="secondary sm" onClick={() => void resume(c.id)}>
                        Devolver al asistente
                      </button>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
