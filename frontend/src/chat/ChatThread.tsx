import { useEffect, useRef, useState, type FormEvent } from "react";

// One chat UI for the public customer chat and the owner's playground.
// The backend decides who answered (AI, button bot, person); this just
// renders text, the bot's buttons, and the "Cancelar este turno" action
// under an automatic booking.
export interface ChatOption {
  id: string;
  label: string;
}

export interface ChatAction {
  type: "booking_created" | "booking_cancelled" | "booking_rescheduled" | "handoff";
  appointmentId?: string | null;
  summary?: string | null;
}

export interface ChatReply {
  reply: string;
  options: ChatOption[];
  actions: ChatAction[];
  mode: "AI" | "FALLBACK" | "HUMAN";
}

interface Line {
  from: "user" | "assistant";
  text: string;
  options?: ChatOption[];
  actions?: ChatAction[];
}

interface Props {
  send: (message: string | null, selectionId: string | null) => Promise<ChatReply>;
  cancel?: (appointmentId: string) => Promise<string>;
  greeting?: string | null;
  placeholder?: string;
  disabled?: boolean;
}

export function ChatThread({ send, cancel, greeting, placeholder = "Escribí un mensaje", disabled }: Props) {
  const [lines, setLines] = useState<Line[]>([]);
  const [draft, setDraft] = useState("");
  const [waiting, setWaiting] = useState(false);
  const [cancelled, setCancelled] = useState<Set<string>>(new Set());
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [lines, waiting]);

  async function deliver(message: string | null, selection: ChatOption | null) {
    if (waiting) return;
    setLines((l) => [...l, { from: "user", text: message ?? selection?.label ?? "" }]);
    setWaiting(true);
    try {
      const r = await send(message, selection?.id ?? null);
      setLines((l) => [...l, { from: "assistant", text: r.reply, options: r.options, actions: r.actions }]);
    } catch (e) {
      const status = (e as { status?: number }).status;
      const text =
        status === 429
          ? "Mandaste muchos mensajes seguidos. Esperá un minuto y probá de nuevo."
          : status === 404
            ? "No encontramos este local."
            : status === 403
              ? "No pudimos verificar que no seas un robot. Recargá la página."
              : "No pudimos enviar tu mensaje. Probá de nuevo.";
      setLines((l) => [...l, { from: "assistant", text }]);
    } finally {
      setWaiting(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const message = draft.trim();
    if (!message) return;
    setDraft("");
    void deliver(message, null);
  }

  async function cancelBooking(appointmentId: string) {
    if (!cancel) return;
    const text = await cancel(appointmentId).catch(() => "No se pudo cancelar. Probá de nuevo.");
    setCancelled((s) => new Set(s).add(appointmentId));
    setLines((l) => [...l, { from: "assistant", text }]);
  }

  const lastIndex = lines.length - 1;
  return (
    <div className="chat-box">
      <div className="chat-thread" aria-live="polite">
        {greeting && <p className="chat-bubble assistant">{greeting}</p>}
        {lines.length === 0 && !greeting && (
          <p className="chat-empty">Escribí tu consulta: precios, horarios, turnos…</p>
        )}
        {lines.map((line, i) => (
          <div key={i} className={`chat-line ${line.from}`}>
            <p className={`chat-bubble ${line.from}`}>{line.text}</p>
            {i === lastIndex && line.options && line.options.length > 0 && (
              <div className="chat-options">
                {line.options.map((o) => (
                  <button key={o.id} type="button" className="secondary sm" disabled={waiting}
                    onClick={() => void deliver(null, o)}>
                    {o.label}
                  </button>
                ))}
              </div>
            )}
            {line.actions
              ?.filter((a) => a.type === "booking_created" && a.appointmentId && cancel)
              .map((a) => (
                <div key={a.appointmentId} className="chat-options">
                  <button type="button" className="secondary sm" disabled={cancelled.has(a.appointmentId!)}
                    onClick={() => void cancelBooking(a.appointmentId!)}>
                    {cancelled.has(a.appointmentId!) ? "Turno cancelado" : "Cancelar este turno"}
                  </button>
                </div>
              ))}
          </div>
        ))}
        {waiting && <p className="chat-bubble assistant typing">escribiendo…</p>}
        <div ref={endRef} />
      </div>
      <form className="chat-input" onSubmit={submit}>
        <input
          aria-label="Mensaje"
          value={draft}
          maxLength={1000}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
        />
        <button type="submit" disabled={disabled || waiting || !draft.trim()}>
          Enviar
        </button>
      </form>
    </div>
  );
}
