"""Wire contract with Spring (conversations/AiServiceClient.java). camelCase on the wire."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class HistoryItem(CamelModel):
    role: Literal["USER", "ASSISTANT"]
    content: str


class AgentConfig(CamelModel):
    """The owner's configuration (assistant_config). Defaults = a tenant that never touched it."""

    version: int = 0
    assistant_name: str = "Asistente"
    tone: Literal["FORMAL", "CERCANO", "CANCHERO"] = "CERCANO"
    voseo: bool = True
    emoji_level: Literal["NONE", "LOW", "HIGH"] = "LOW"
    greeting: str | None = None
    custom_instructions: str | None = None
    policies: str | None = None
    handoff_instructions: str | None = None
    can_book: bool = True
    can_cancel: bool = True
    can_reschedule: bool = True
    playground: bool = False


class PendingInfo(CamelModel):
    draft_id: str
    type: Literal["BOOKING", "CANCELLATION", "RESCHEDULE"]
    summary: str
    mode: Literal["AUTO", "CONFIRM"]


class TurnRequest(CamelModel):
    message: str = Field(min_length=1, max_length=2000)
    history: list[HistoryItem] = []
    core_token: str
    business_name: str
    timezone: str = "America/Argentina/Buenos_Aires"
    config: AgentConfig = AgentConfig()
    pending_action: PendingInfo | None = None


class Usage(CamelModel):
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    cost_usd: float = 0.0

    def __add__(self, other: "Usage") -> "Usage":
        return Usage(
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            cache_read_tokens=self.cache_read_tokens + other.cache_read_tokens,
            cache_write_tokens=self.cache_write_tokens + other.cache_write_tokens,
            cost_usd=self.cost_usd + other.cost_usd,
        )


class Action(CamelModel):
    type: Literal["booking_created", "booking_cancelled", "booking_rescheduled", "handoff"]
    appointment_id: str | None = None
    summary: str | None = None


class TurnResponse(CamelModel):
    # Empty reply = the agent gave up (iteration cap, refusal); Spring answers
    # with its fallback instead.
    reply: str
    usage: Usage
    iterations: int
    tools_called: list[str]
    model: str
    prompt_version: str
    trace_id: str
    actions: list[Action] = []
    # Guardrail marks for analysis, e.g. "possible_injection", "ungrounded_output".
    flags: list[str] = []
