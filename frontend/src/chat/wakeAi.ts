// On Render's free plan the AI service sleeps when idle, and a request from
// the backend (another Render service) gets a 429 instead of waking it, so
// the first message fell back to the button bot. A browser request does
// wake it: the chat pings it on open, while the customer is still typing.
// no-cors: we only need the request to arrive, not to read the answer.
const AI_WAKE_URL = import.meta.env.VITE_AI_WAKE_URL as string | undefined;

export function wakeAi(): void {
  if (AI_WAKE_URL) fetch(AI_WAKE_URL, { mode: "no-cors" }).catch(() => {});
}
