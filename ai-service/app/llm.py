"""LLM layer: normalized types + one provider (Anthropic).

The loop only touches these types. Messages stay provider-native and opaque
(`assistant_message` is appended back as-is), which keeps thinking blocks
intact across tool calls - the API requires them unchanged.
"""

from dataclasses import dataclass, field
from typing import Any, Protocol, cast

from anthropic import AsyncAnthropic
from anthropic.types import MessageParam, TextBlockParam, ToolParam

from app.contracts import Usage


@dataclass
class ToolSpec:
    name: str
    description: str
    input_schema: dict[str, Any]


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class ToolResult:
    call_id: str
    content: str
    is_error: bool = False


@dataclass
class LLMResponse:
    text: str | None
    tool_calls: list[ToolCall]
    stop_reason: str
    usage: Usage
    assistant_message: dict[str, Any] = field(default_factory=dict)


class LLMProvider(Protocol):
    model: str

    async def complete(
        self, system: list[str], messages: list[dict[str, Any]], tools: list[ToolSpec], max_tokens: int
    ) -> LLMResponse: ...

    def tool_results_message(self, results: list[ToolResult]) -> dict[str, Any]: ...

    def control_message(self, text: str) -> dict[str, Any]:
        """An operator note mid-conversation (e.g. a guardrail retry), never shown to the customer."""
        ...


class AnthropicProvider:
    def __init__(self, model: str, effort: str = "low", client: AsyncAnthropic | None = None) -> None:
        self.model = model
        self.effort = effort
        self.client = client or AsyncAnthropic()

    async def complete(
        self, system: list[str], messages: list[dict[str, Any]], tools: list[ToolSpec], max_tokens: int
    ) -> LLMResponse:
        blocks: list[TextBlockParam] = [{"type": "text", "text": text} for text in system]
        # Breakpoints after the platform rules (same for every tenant) and after
        # the owner's config (same per tenant and version). ponytail: below the
        # model's minimum cacheable prefix this silently doesn't cache; check
        # usage.cache_read_tokens if the rules shrink.
        for block in blocks[:2]:
            block["cache_control"] = {"type": "ephemeral"}
        tool_params: list[ToolParam] = [
            {"name": t.name, "description": t.description, "input_schema": t.input_schema} for t in tools
        ]
        response = await self.client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            system=blocks,
            messages=cast(list[MessageParam], messages),
            tools=tool_params,
            output_config={"effort": cast(Any, self.effort)},
        )
        u = response.usage
        return LLMResponse(
            text="".join(b.text for b in response.content if b.type == "text") or None,
            tool_calls=[
                ToolCall(id=b.id, name=b.name, arguments=dict(b.input))
                for b in response.content
                if b.type == "tool_use"
            ],
            stop_reason=str(response.stop_reason),
            usage=Usage(
                input_tokens=u.input_tokens,
                output_tokens=u.output_tokens,
                cache_read_tokens=u.cache_read_input_tokens or 0,
                cache_write_tokens=u.cache_creation_input_tokens or 0,
            ),
            assistant_message={"role": "assistant", "content": response.content},
        )

    def tool_results_message(self, results: list[ToolResult]) -> dict[str, Any]:
        # All results of one turn go in ONE user message (keeps parallel calls working).
        return {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": r.call_id,
                    "content": r.content,
                    "is_error": r.is_error,
                }
                for r in results
            ],
        }

    def control_message(self, text: str) -> dict[str, Any]:
        # Mid-conversation system message: an operator channel the customer
        # can't write to, and it doesn't invalidate the cached prefix.
        return {"role": "system", "content": text}
