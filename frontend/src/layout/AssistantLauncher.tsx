import { useState } from "react";
import { api } from "../api/client";
import { ChatThread, type ChatReply } from "../chat/ChatThread";

// The owner's test chat, always at hand: a floating button with the brand
// isotype (bottom right, like a site's WhatsApp button) that unfolds a chat
// window docked in the corner, Messenger-style. It talks to the playground
// endpoint: the draft config if there is one, bookings simulated.
//
// The window stays mounted while closed, so closing and reopening keeps the
// conversation; "Nueva conversación" (⋮ menu) starts over.
export function AssistantLauncher({ businessName }: { businessName: string }) {
  const [open, setOpen] = useState(false);
  const [sessionId, setSessionId] = useState(() => crypto.randomUUID());

  const send = (message: string | null): Promise<ChatReply> =>
    api.post<ChatReply>("/assistant/playground", { sessionId, message: message ?? "" });

  return (
    <>
      <div className={open ? "assist-dock open" : "assist-dock"} id="assistant-chat" role="dialog" aria-label="Probar el asistente">
        <ChatThread
          key={sessionId}
          send={send}
          title={businessName}
          placeholder="Escribí como si fueras un cliente"
          notice="Probá tu asistente como si fueras un cliente. Usa el borrador de la configuración y las reservas son simuladas."
          onRestart={() => setSessionId(crypto.randomUUID())}
          onClose={() => setOpen(false)}
        />
      </div>
      <button
        type="button"
        className="assist-launcher"
        aria-label={open ? "Cerrar el chat del asistente" : "Abrir el chat del asistente"}
        title={open ? "Cerrar chat" : "Probar el asistente"}
        aria-controls="assistant-chat"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <img className="theme-light" src="/brand/isotipo.svg" alt="" />
        <img className="theme-dark" src="/brand/isotipo-oscuro.svg" alt="" />
      </button>
    </>
  );
}
