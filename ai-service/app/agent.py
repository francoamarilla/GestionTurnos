"""The agent loop: LLM <-> tools until the model answers in text, or the budget runs out."""

import asyncio
import json
import logging
import time
import uuid
from datetime import datetime
from typing import Any

from app import guardrails, pricing, prompts, tools
from app.contracts import Action, TurnRequest, TurnResponse, Usage
from app.llm import LLMProvider, ToolCall

MAX_ITERATIONS = 6
MAX_OUTPUT_TOKENS = 1500

log = logging.getLogger("agent")


async def run_turn(req: TurnRequest, llm: LLMProvider, ctx: tools.ToolContext, now: datetime) -> TurnResponse:
    trace_id = uuid.uuid4().hex
    started = time.monotonic()
    system = prompts.build(req.business_name, req.timezone, now, req.config, req.pending_action)
    messages: list[dict[str, Any]] = [
        {"role": "user" if h.role == "USER" else "assistant", "content": h.content} for h in req.history
    ]
    messages.append({"role": "user", "content": req.message})
    specs = tools.specs(req.config)

    flags = ["possible_injection"] if guardrails.looks_like_injection(req.message) else []
    # What a time or price in the reply may come from: tools this turn, the
    # pending draft, and the customer's own words.
    evidence = [req.message, *(h.content for h in req.history if h.role == "USER")]
    if req.pending_action:
        evidence.append(req.pending_action.summary)

    usage = Usage()
    called: list[str] = []
    actions: list[Action] = []
    trace: list[dict[str, Any]] = []
    retried = False
    iteration = 0
    reply = ""
    while iteration < MAX_ITERATIONS:
        iteration += 1
        response = await llm.complete(system, messages, specs, MAX_OUTPUT_TOKENS)
        usage += response.usage
        trace.append({"llm": response.stop_reason, "usage": response.usage.model_dump()})

        if not response.tool_calls:
            if response.stop_reason == "refusal":
                flags.append("refusal")
                break
            reply = (response.text or "").strip()
            missing = guardrails.ungrounded(reply, "\n".join(evidence))
            if missing and not retried:
                # One regeneration, with a note only the model sees.
                retried = True
                flags.append("ungrounded_retry")
                messages.append(
                    llm.control_message(
                        f"Tu respuesta mencionaba {', '.join(missing)}, que no sale de ninguna herramienta. "
                        "Consultá las herramientas o no menciones ese dato."
                    )
                )
                continue
            if missing:
                flags.append("ungrounded_output")
                reply = guardrails.strip_ungrounded(reply, "\n".join(evidence))
            break

        messages.append(response.assistant_message)
        called += [c.name for c in response.tool_calls]
        results = await _run_tools(response.tool_calls, ctx, req)
        for call, execution in zip(response.tool_calls, results, strict=True):
            evidence.append(execution.result.content)
            if execution.action:
                actions.append(execution.action)
            trace.append({"tool": call.name, "args": call.arguments, "error": execution.result.is_error})
        messages.append(llm.tool_results_message([e.result for e in results]))
    else:
        flags.append("max_iterations")

    usage.cost_usd = pricing.cost_usd(llm.model, usage)
    log.info(
        json.dumps(
            {
                "trace_id": trace_id,
                "model": llm.model,
                "prompt_version": prompts.PROMPT_VERSION,
                "config_version": req.config.version,
                "latency_ms": int((time.monotonic() - started) * 1000),
                "iterations": iteration,
                "usage": usage.model_dump(),
                "flags": flags,
                "steps": trace,
            },
            ensure_ascii=False,
        )
    )
    return TurnResponse(
        reply=guardrails.cap_length(reply),
        usage=usage,
        iterations=iteration,
        tools_called=called,
        model=llm.model,
        prompt_version=prompts.PROMPT_VERSION,
        trace_id=trace_id,
        actions=actions,
        flags=flags,
    )


async def _run_tools(calls: list[ToolCall], ctx: tools.ToolContext, req: TurnRequest) -> list[tools.Execution]:
    # Reads in parallel; as soon as one call writes, all go one at a time,
    # in the order the model asked for them.
    if any(tools.TOOLS.get(c.name) is None or tools.TOOLS[c.name].write for c in calls):
        return [await tools.execute(c, ctx, req.config) for c in calls]
    return list(await asyncio.gather(*(tools.execute(c, ctx, req.config) for c in calls)))
