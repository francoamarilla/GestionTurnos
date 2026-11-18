"""Shared test doubles: a scripted LLM (no network, no API key)."""

import json
from collections.abc import Callable
from typing import Any

from app.contracts import Usage
from app.llm import LLMResponse, ToolCall, ToolResult, ToolSpec

# A step is a fixed response, or a function of the messages so far (to read a draftId, say).
Step = LLMResponse | Callable[[list[dict[str, Any]]], LLMResponse]


def last_tool_result(messages: list[dict[str, Any]]) -> dict[str, Any]:
    return dict(json.loads(messages[-1]["content"][-1]["content"]))


class ScriptedLLM:
    model = "claude-haiku-5-5"

    def __init__(self, responses: list[Step]) -> None:
        self.responses = responses
        self.calls: list[list[dict[str, Any]]] = []
        self.tool_names: list[list[str]] = []
        self.systems: list[list[str]] = []

    async def complete(
        self, system: list[str], messages: list[dict[str, Any]], tools: list[ToolSpec], max_tokens: int
    ) -> LLMResponse:
        self.calls.append(list(messages))
        self.tool_names.append([t.name for t in tools])
        self.systems.append(system)
        step = self.responses.pop(0)
        return step if isinstance(step, LLMResponse) else step(messages)

    def tool_results_message(self, results: list[ToolResult]) -> dict[str, Any]:
        return {"role": "user", "content": [r.__dict__ for r in results]}

    def control_message(self, text: str) -> dict[str, Any]:
        return {"role": "system", "content": text}


def text(reply: str, stop: str = "end_turn") -> LLMResponse:
    return LLMResponse(text=reply, tool_calls=[], stop_reason=stop, usage=Usage(input_tokens=1000, output_tokens=100))


def calls(*specs: str | tuple[str, dict[str, Any]]) -> LLMResponse:
    tool_calls = []
    for i, spec in enumerate(specs):
        name, args = (spec, {}) if isinstance(spec, str) else spec
        tool_calls.append(ToolCall(id=f"call_{i}", name=name, arguments=args))
    return LLMResponse(
        text=None,
        tool_calls=tool_calls,
        stop_reason="tool_use",
        usage=Usage(input_tokens=1000, output_tokens=50),
        assistant_message={"role": "assistant", "content": "tool calls"},
    )
