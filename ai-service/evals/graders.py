"""Deterministic checks first; an LLM judge only for tone and quality (propuesta section 9.3)."""

import json
import os
import re
from dataclasses import dataclass, field
from typing import Any

from anthropic import AsyncAnthropic

JUDGE_MODEL = os.environ.get("EVAL_JUDGE_MODEL", "claude-sonnet-5-5")


@dataclass
class Transcript:
    """Everything a grader may look at after a scenario ran."""

    replies: list[str]
    tools: list[str]
    flags: list[str]
    costs: list[float]
    created: list[dict[str, Any]]
    created_per_turn: list[int]
    cancelled: list[str]
    rescheduled: list[dict[str, Any]]
    handoffs: list[str]
    transcript: list[tuple[str, str]] = field(default_factory=list)


def _subsequence(expected: list[str], actual: list[str]) -> bool:
    it = iter(actual)
    return all(name in it for name in expected)


def deterministic(expect: dict[str, Any], t: Transcript) -> list[str]:
    failures: list[str] = []
    last = t.replies[-1] if t.replies else ""

    if (order := expect.get("tools_called_in_order")) and not _subsequence(order, t.tools):
        failures.append(f"tools not called in order {order}: {t.tools}")
    for name in expect.get("tools_called", []):
        if name not in t.tools:
            failures.append(f"expected tool {name}, got {t.tools}")
    for name in expect.get("tools_not_called", []):
        if name in t.tools:
            failures.append(f"tool {name} must not be called")

    if "booking_created" in expect:
        want = expect["booking_created"]
        if want is None:
            if t.created:
                failures.append(f"no booking expected, got {t.created}")
        elif not any(all(b.get(k) == v for k, v in want.items()) for b in t.created):
            failures.append(f"booking {want} not created; got {t.created}")
    if "bookings" in expect and len(t.created) != expect["bookings"]:
        failures.append(f"expected {expect['bookings']} bookings, got {len(t.created)}")
    if "booked_on_turn" in expect:
        turn = expect["booked_on_turn"]
        first = next((i + 1 for i, n in enumerate(t.created_per_turn) if n > 0), None)
        if first != turn:
            failures.append(f"expected the booking on turn {turn}, happened on turn {first}")

    if "asked_confirmation" in expect:
        asked = "?" in last and not t.created
        if expect["asked_confirmation"] and not asked:
            failures.append("expected a question and no booking yet")
        if expect["asked_confirmation"] is False and not t.created:
            failures.append("expected a direct booking without asking")

    if "cancelled" in expect and sorted(t.cancelled) != sorted(expect["cancelled"]):
        failures.append(f"expected cancelled {expect['cancelled']}, got {t.cancelled}")
    if "rescheduled_to" in expect and not any(r["start"] == expect["rescheduled_to"] for r in t.rescheduled):
        failures.append(f"expected a reschedule to {expect['rescheduled_to']}, got {t.rescheduled}")
    if "handoff" in expect and bool(t.handoffs) != expect["handoff"]:
        failures.append(f"expected handoff={expect['handoff']}")

    if expect.get("grounded") and "ungrounded_output" in t.flags:
        failures.append("the reply carried times or prices no tool returned")
    if (pattern := expect.get("reply_matches")) and not re.search(pattern, last, re.IGNORECASE):
        failures.append(f"reply doesn't match /{pattern}/: {last!r}")
    if (pattern := expect.get("reply_not_matches")) and re.search(pattern, last, re.IGNORECASE):
        failures.append(f"reply must not match /{pattern}/")
    if (cap := expect.get("max_turn_cost_usd")) and t.costs and max(t.costs) > cap:
        failures.append(f"turn cost {max(t.costs):.4f} > {cap}")
    if not last.strip():
        failures.append("empty reply (Spring would fall back to the button bot)")
    return failures


JUDGE_SCHEMA = {
    "type": "object",
    "properties": {"pass": {"type": "boolean"}, "reason": {"type": "string"}},
    "required": ["pass", "reason"],
    "additionalProperties": False,
}


async def judge(rubric: str, t: Transcript, client: AsyncAnthropic | None = None) -> str | None:
    """Returns a failure message, or None when the rubric holds."""
    client = client or AsyncAnthropic()
    conversation = "\n".join(f"{who}: {text}" for who, text in t.transcript)
    response = await client.messages.create(
        model=JUDGE_MODEL,
        max_tokens=2000,
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": JUDGE_SCHEMA}},
        messages=[
            {
                "role": "user",
                "content": "Evaluá la respuesta final de un asistente de barbería contra el criterio.\n\n"
                f"<conversacion>\n{conversation}\n</conversacion>\n\n<criterio>\n{rubric}\n</criterio>\n\n"
                "pass = true solo si el criterio se cumple por completo.",
            }
        ],
    )
    raw = "".join(b.text for b in response.content if b.type == "text")
    verdict = json.loads(raw)
    return None if verdict["pass"] else f"judge: {verdict['reason']}"
