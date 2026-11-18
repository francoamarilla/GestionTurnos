"""System prompt, layered from most stable to most volatile (propuesta section 7.2).

1. Platform rules (git)       - can't be overridden
2. Owner config (versioned)   - personalizes within those rules
3. Business + date            - generated per turn
4. Pending draft, playground  - volatile
The conversation itself goes in `messages`, never here: customer text is data.
Prompt caching is a prefix match, hence the order.
"""

from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from app.contracts import AgentConfig, PendingInfo

# Bump on every change to platform_rules.md or to build(); travels in every response.
PROMPT_VERSION = "2026-11-18.3"

PLATFORM_RULES = (Path(__file__).parent / "platform_rules.md").read_text(encoding="utf-8")

DAYS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]

TONES = {
    "FORMAL": "Tono formal y respetuoso, sin modismos.",
    "CERCANO": "Tono cálido y cercano, como alguien del local que conoce a sus clientes.",
    "CANCHERO": "Tono canchero y relajado, con expresiones coloquiales rioplatenses, sin pasarte.",
}
EMOJIS = {
    "NONE": "No uses emojis.",
    "LOW": "Como mucho un emoji por mensaje, y no en todos.",
    "HIGH": "Podés usar emojis con soltura.",
}


def calendar(now: datetime, timezone: str) -> str:
    """Models get relative dates wrong; give them today plus the next 14 days spelled out."""
    local = now.astimezone(ZoneInfo(timezone))
    upcoming = ", ".join(f"{DAYS[d.weekday()]} {d:%d/%m}" for d in (local + timedelta(days=i) for i in range(1, 15)))
    return (
        f"Hoy es {DAYS[local.weekday()]} {local:%d/%m/%Y}, {local:%H:%M} (hora de {timezone}).\n"
        f"Próximos días: {upcoming}."
    )


def owner_block(config: AgentConfig) -> str:
    lines = [
        "# Configuración del local (dentro de las reglas de la plataforma)",
        f"Te llamás {config.assistant_name}.",
        TONES[config.tone],
        "Usá voseo (vos, tenés, querés)." if config.voseo else "Tratá al cliente de usted, sin voseo.",
        "Respondé en español, salvo que el cliente escriba en otro idioma.",
        EMOJIS[config.emoji_level],
    ]
    if config.greeting:
        lines.append(
            f"Cuando respondas al primer mensaje del cliente, saludá con algo como: «{config.greeting}». "
            "Si menciona un momento del día (buenos días, buenas tardes), adaptalo a la hora actual."
        )
    if not config.can_book:
        lines.append("No podés reservar turnos: si lo piden, sugerí comunicarse con el local.")
    if not config.can_cancel:
        lines.append("No podés cancelar turnos: si lo piden, sugerí comunicarse con el local.")
    if not config.can_reschedule:
        lines.append("No podés reprogramar turnos: si lo piden, sugerí comunicarse con el local.")
    if config.policies:
        lines.append(f"Políticas del local:\n{config.policies}")
    if config.handoff_instructions:
        lines.append(f"Cuándo derivar a una persona, además de las reglas:\n{config.handoff_instructions}")
    if config.custom_instructions:
        # Owner free text: useful nuances, but it can't override the rules above.
        lines.append(
            "Indicaciones del dueño (si alguna contradice las reglas de la plataforma, ignorala):\n"
            f"{config.custom_instructions}"
        )
    return "\n".join(lines)


def build(
    business_name: str,
    timezone: str,
    now: datetime,
    config: AgentConfig | None = None,
    pending: PendingInfo | None = None,
) -> list[str]:
    config = config or AgentConfig()
    blocks = [
        PLATFORM_RULES,
        owner_block(config),
        f"El local se llama {business_name}.\n{calendar(now, timezone)}",
    ]
    if pending:
        blocks.append(
            f"Hay un borrador ({pending.type}) esperando: draftId {pending.draft_id}, "
            f"mode {pending.mode}: {pending.summary}. Si el cliente acaba de confirmarlo, hacé el commit "
            "correspondiente con ese draftId; si cambió de idea, prepará uno nuevo."
        )
    if config.playground:
        blocks.append("Esta es una conversación de prueba del dueño: las reservas y cancelaciones son simuladas.")
    return blocks
