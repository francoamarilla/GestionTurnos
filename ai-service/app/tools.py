"""Agent tools: each one is a typed call to Spring's /internal/agent/* API.

The tenant, the conversation and the customer travel inside the AGENT token
Spring issued for this turn (the customer is looked up from the
conversation). No tool takes an id the model could forge to reach another
business; appointment ids are checked against the identified customer.
"""

import json
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import httpx
from pydantic import BaseModel, Field, ValidationError

from app.contracts import Action, AgentConfig
from app.llm import ToolCall, ToolResult, ToolSpec


@dataclass
class ToolContext:
    core: httpx.AsyncClient
    core_token: str

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        response = await self.core.get(path, params=params, headers=self._auth())
        response.raise_for_status()
        return response.json()

    async def post(self, path: str, body: dict[str, Any] | None = None) -> Any:
        response = await self.core.post(path, json=body or {}, headers=self._auth())
        response.raise_for_status()
        return response.json()

    def _auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.core_token}"}


# ---- argument models (what the model fills in) ------------------------------


class NoArgs(BaseModel):
    pass


class ProfessionalsArgs(BaseModel):
    service_id: str = Field(description="id del servicio, de list_services")


class SearchArgs(BaseModel):
    service_id: str = Field(description="id del servicio, de list_services")
    professional_id: str | None = Field(None, description="id del profesional; vacío = cualquiera")
    date_from: str = Field(description="primer día a buscar, YYYY-MM-DD")
    date_to: str | None = Field(None, description="último día a buscar, YYYY-MM-DD (máx. 14 días)")
    time_from: str | None = Field(None, description="hora mínima HH:MM, para franjas como 'a la tarde'")
    time_to: str | None = Field(None, description="hora máxima HH:MM")


class IdentifyArgs(BaseModel):
    name: str = Field(description="nombre del cliente")
    phone: str = Field(description="teléfono del cliente, como lo escribió")


class PrepareBookingArgs(BaseModel):
    service_text: str = Field(description="el servicio con las palabras del cliente, ej. 'corte'")
    professional_text: str | None = Field(
        None, description="el profesional como lo dijo el cliente, 'cualquiera', o vacío si no dijo"
    )
    date_text: str = Field(description="el día con las palabras literales del cliente, ej. 'el jueves', 'mañana'")
    time_text: str = Field(description="la hora con las palabras literales del cliente, ej. '10:30', 'tipo 10'")


class DraftArgs(BaseModel):
    draft_id: str = Field(description="draftId devuelto por el prepare_*")


class AppointmentArgs(BaseModel):
    appointment_id: str = Field(description="id del turno, de get_my_appointments")


class PrepareRescheduleArgs(BaseModel):
    appointment_id: str = Field(description="id del turno a mover, de get_my_appointments")
    date_text: str = Field(description="el nuevo día con las palabras del cliente")
    time_text: str = Field(description="la nueva hora con las palabras del cliente")
    professional_text: str | None = Field(None, description="otro profesional, solo si el cliente lo pidió")


class HandoffArgs(BaseModel):
    reason: str = Field(description="por qué hace falta una persona, en pocas palabras")


# ---- registry -----------------------------------------------------------------

Handler = Callable[[Any, ToolContext], Awaitable[Any]]


@dataclass
class Tool:
    name: str
    description: str
    args: type[BaseModel]
    handler: Handler
    # Writes run one at a time; reads may run in parallel.
    write: bool = False
    # Owner switch (assistant_config) that must be on to offer this tool.
    capability: str | None = None
    # What the widget should show after a successful call.
    action: str | None = None

    def spec(self) -> ToolSpec:
        return ToolSpec(self.name, self.description, self.args.model_json_schema())


def _camel(args: BaseModel) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in args.model_dump(exclude_none=True).items():
        head, *rest = key.split("_")
        out[head + "".join(p.title() for p in rest)] = value
    return out


TOOLS: dict[str, Tool] = {
    t.name: t
    for t in [
        Tool(
            "get_business_info",
            "Datos del local: nombre, dirección, teléfono, horario semanal y con cuántas horas "
            "de anticipación se puede cancelar.",
            NoArgs,
            lambda a, ctx: ctx.get("/internal/agent/business-info"),
        ),
        Tool(
            "list_services",
            "Servicios activos con id, duración en minutos y precio.",
            NoArgs,
            lambda a, ctx: ctx.get("/internal/agent/services"),
        ),
        Tool(
            "list_professionals",
            "Profesionales que hacen un servicio.",
            ProfessionalsArgs,
            lambda a, ctx: ctx.get("/internal/agent/professionals", {"serviceId": a.service_id}),
        ),
        Tool(
            "search_availability",
            "Horarios libres reales para un servicio entre dos fechas, opcionalmente en una franja horaria. "
            "Usala para '¿qué tenés el jueves a la tarde?' o para ofrecer opciones.",
            SearchArgs,
            lambda a, ctx: ctx.post("/internal/agent/availability/search", _camel(a)),
        ),
        Tool(
            "identify_customer",
            "Registra quién es el cliente (nombre y teléfono). Necesario antes de reservar, cancelar o ver sus turnos.",
            IdentifyArgs,
            lambda a, ctx: ctx.post("/internal/agent/customer/identify", _camel(a)),
            write=True,
        ),
        Tool(
            "get_my_appointments",
            "Próximos turnos del cliente identificado, con su id.",
            NoArgs,
            lambda a, ctx: ctx.get("/internal/agent/customer/appointments"),
        ),
        Tool(
            "get_customer_profile",
            "Lo que el local sabe del cliente: nombre, último servicio, profesional habitual.",
            NoArgs,
            lambda a, ctx: ctx.get("/internal/agent/customer/profile"),
        ),
        Tool(
            "prepare_booking",
            "Arma un borrador de reserva con las palabras literales del cliente. Devuelve draftId, "
            "summary y mode (AUTO o CONFIRM), o un error con lo que falta.",
            PrepareBookingArgs,
            lambda a, ctx: ctx.post("/internal/agent/bookings/prepare", _camel(a)),
            write=True,
            capability="can_book",
        ),
        Tool(
            "commit_booking",
            "Confirma un borrador de reserva y crea el turno. Con mode CONFIRM, solo después de que el "
            "cliente dijo que sí.",
            DraftArgs,
            lambda a, ctx: ctx.post(f"/internal/agent/bookings/{a.draft_id}/commit"),
            write=True,
            capability="can_book",
            action="booking_created",
        ),
        Tool(
            "prepare_cancellation",
            "Arma un borrador para cancelar un turno del cliente. Siempre hay que confirmarlo con el cliente.",
            AppointmentArgs,
            lambda a, ctx: ctx.post("/internal/agent/cancellations/prepare", _camel(a)),
            write=True,
            capability="can_cancel",
        ),
        Tool(
            "commit_cancellation",
            "Cancela el turno del borrador, después de que el cliente confirmó.",
            DraftArgs,
            lambda a, ctx: ctx.post(f"/internal/agent/cancellations/{a.draft_id}/commit"),
            write=True,
            capability="can_cancel",
            action="booking_cancelled",
        ),
        Tool(
            "prepare_reschedule",
            "Arma un borrador para mover un turno del cliente a otro día u hora (mismo servicio).",
            PrepareRescheduleArgs,
            lambda a, ctx: ctx.post("/internal/agent/reschedules/prepare", _camel(a)),
            write=True,
            capability="can_reschedule",
        ),
        Tool(
            "commit_reschedule",
            "Confirma el borrador de reprogramación. Con mode CONFIRM, solo después del sí del cliente.",
            DraftArgs,
            lambda a, ctx: ctx.post(f"/internal/agent/reschedules/{a.draft_id}/commit"),
            write=True,
            capability="can_reschedule",
            action="booking_rescheduled",
        ),
        Tool(
            "handoff_to_human",
            "Pasa la conversación a una persona del local: quejas, temas sensibles, pedidos que no podés "
            "resolver, o cuando el cliente pide hablar con alguien.",
            HandoffArgs,
            lambda a, ctx: ctx.post("/internal/agent/handoff", _camel(a)),
            write=True,
            action="handoff",
        ),
    ]
}


def available(config: AgentConfig) -> list[Tool]:
    """The owner's switches decide which tools the model even sees (Spring enforces them again)."""
    return [t for t in TOOLS.values() if t.capability is None or getattr(config, t.capability)]


def specs(config: AgentConfig) -> list[ToolSpec]:
    return [t.spec() for t in available(config)]


@dataclass
class Execution:
    result: ToolResult
    action: Action | None = None


async def execute(call: ToolCall, ctx: ToolContext, config: AgentConfig) -> Execution:
    """Never raises: every failure goes back to the model as an error result so it can recover."""
    tool = next((t for t in available(config) if t.name == call.name), None)
    if tool is None:
        return Execution(ToolResult(call.id, f"Unknown or disabled tool: {call.name}", is_error=True))
    try:
        args = tool.args.model_validate(call.arguments)
    except ValidationError as e:
        return Execution(ToolResult(call.id, f"Invalid arguments: {e}", is_error=True))
    try:
        result = await tool.handler(args, ctx)
    except httpx.HTTPStatusError as e:
        detail = e.response.text[:500]
        return Execution(
            ToolResult(call.id, json.dumps({"error": e.response.status_code, "detail": detail}), is_error=True)
        )
    except httpx.HTTPError as e:
        return Execution(ToolResult(call.id, json.dumps({"error": "core_unreachable", "detail": str(e)}), True))
    return Execution(ToolResult(call.id, json.dumps(result, ensure_ascii=False)), _action(tool, result))


def _action(tool: Tool, result: Any) -> Action | None:
    if tool.action is None or not isinstance(result, dict) or "error" in result or result.get("simulated"):
        return None
    if tool.action == "handoff" or result.get("appointmentId") or result.get("cancelled"):
        return Action(
            type=tool.action,  # type: ignore[arg-type]
            appointment_id=result.get("appointmentId"),
            summary=result.get("summary"),
        )
    return None
