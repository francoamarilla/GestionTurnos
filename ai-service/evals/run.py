"""Run the eval scenarios against one or more models and print a success/cost/latency table.

    python -m evals.run                         # all scenarios, AI_MODEL (default claude-haiku-5-5)
    python -m evals.run --tag smoke             # the CI subset
    python -m evals.run --model claude-haiku-5-5 --model claude-sonnet-5-5 --out report.json
    python -m evals.run --min-pass 0.85         # exit 1 below that success rate

Needs ANTHROPIC_API_KEY. Every run spends real tokens (cents for the smoke set).
"""

import argparse
import asyncio
import json
import os
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import yaml

from app.agent import run_turn
from app.contracts import AgentConfig, HistoryItem, PendingInfo, TurnRequest
from app.llm import AnthropicProvider, LLMProvider
from app.tools import ToolContext
from evals.fake_core import FakeCore
from evals.graders import Transcript, deterministic, judge

HERE = Path(__file__).parent
DEFAULT_NOW = "2026-11-17T17:30:00-03:00"


def load_scenarios(tag: str | None = None, ids: list[str] | None = None) -> list[dict[str, Any]]:
    scenarios: list[dict[str, Any]] = []
    for path in sorted((HERE / "scenarios").glob("*.yaml")):
        scenarios += yaml.safe_load(path.read_text(encoding="utf-8"))
    if tag:
        scenarios = [s for s in scenarios if tag in s.get("tags", [])]
    if ids:
        scenarios = [s for s in scenarios if s["id"] in ids]
    return scenarios


def load_fixture(name: str) -> dict[str, Any]:
    return dict(json.loads((HERE / "fixtures" / f"{name}.json").read_text(encoding="utf-8")))


@dataclass
class Result:
    id: str
    model: str
    passed: bool
    failures: list[str]
    cost_usd: float
    latency_s: float
    replies: list[str]


async def run_scenario(scenario: dict[str, Any], llm: LLMProvider, use_judge: bool = True) -> Result:
    fixture = load_fixture(scenario.get("fixture", "agenda_semana_normal"))
    fixture["business"] = {**fixture["business"], **scenario.get("core", {})}
    now = datetime.fromisoformat(scenario.get("now", DEFAULT_NOW))
    core = FakeCore(fixture, now)
    config = AgentConfig.model_validate(scenario.get("config", {}))

    history: list[HistoryItem] = []
    t = Transcript([], [], [], [], [], [], [], [], [])
    started = time.monotonic()
    async with httpx.AsyncClient(base_url="http://core", transport=core.transport()) as client:
        for i, message in enumerate(scenario["turns"]):
            core.new_user_turn()
            core.s.now = now + timedelta(minutes=i)  # a minute per turn
            pending = core.pending()
            req = TurnRequest(
                message=message,
                history=history,
                core_token="eval",
                business_name=fixture["business"]["name"],
                timezone=fixture["business"]["timezone"],
                config=config,
                pending_action=PendingInfo.model_validate(pending) if pending else None,
            )
            resp = await run_turn(req, llm, ToolContext(client, "eval"), now=core.s.now)
            history += [HistoryItem(role="USER", content=message), HistoryItem(role="ASSISTANT", content=resp.reply)]
            t.replies.append(resp.reply)
            t.tools += resp.tools_called
            t.flags += resp.flags
            t.costs.append(resp.usage.cost_usd)
            t.created_per_turn.append(len(core.s.created) - sum(t.created_per_turn))
            t.transcript += [("cliente", message), ("asistente", resp.reply)]
    t.created, t.cancelled = core.s.created, core.s.cancelled
    t.rescheduled, t.handoffs = core.s.rescheduled, core.s.handoffs

    failures = deterministic(scenario["expect"], t)
    if use_judge and (rubric := scenario["expect"].get("judge")):
        verdict = await judge(rubric, t)
        if verdict:
            failures.append(verdict)
    return Result(
        id=scenario["id"],
        model=llm.model,
        passed=not failures,
        failures=failures,
        cost_usd=sum(t.costs),
        latency_s=time.monotonic() - started,
        replies=t.replies,
    )


async def run_all(scenarios: list[dict[str, Any]], models: list[str], concurrency: int) -> list[Result]:
    gate = asyncio.Semaphore(concurrency)

    async def one(scenario: dict[str, Any], model: str) -> Result:
        async with gate:
            try:
                return await run_scenario(
                    scenario, AnthropicProvider(model=model, effort=os.environ.get("AI_EFFORT", "low"))
                )
            except Exception as e:  # a crash is a failed scenario, not a failed run
                return Result(scenario["id"], model, False, [f"crashed: {e!r}"], 0.0, 0.0, [])

    return list(await asyncio.gather(*(one(s, m) for s in scenarios for m in models)))


def print_table(results: list[Result], models: list[str]) -> None:
    ids = list(dict.fromkeys(r.id for r in results))
    by = {(r.id, r.model): r for r in results}
    width = max(len(i) for i in ids) + 2
    print("scenario".ljust(width) + "".join(m[:22].ljust(24) for m in models))
    for i in ids:
        print(i.ljust(width) + "".join(("PASS" if by[(i, m)].passed else "FAIL").ljust(24) for m in models))
    print()
    for m in models:
        rs = [r for r in results if r.model == m]
        rate = sum(r.passed for r in rs) / len(rs)
        print(
            f"{m}: {rate:.0%} passed ({sum(r.passed for r in rs)}/{len(rs)}), "
            f"avg cost ${sum(r.cost_usd for r in rs) / len(rs):.5f}/scenario, "
            f"avg latency {sum(r.latency_s for r in rs) / len(rs):.1f}s"
        )
    for r in results:
        if not r.passed:
            print(f"\n[{r.model}] {r.id}:\n  - " + "\n  - ".join(r.failures))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", action="append", help="repeat to compare models")
    parser.add_argument("--tag")
    parser.add_argument("--id", action="append", dest="ids")
    parser.add_argument("--out", help="write a JSON report here")
    parser.add_argument("--min-pass", type=float, default=0.0)
    parser.add_argument("--concurrency", type=int, default=4)
    args = parser.parse_args()

    models = args.model or [os.environ.get("AI_MODEL", "claude-haiku-5-5")]
    scenarios = load_scenarios(args.tag, args.ids)
    results = asyncio.run(run_all(scenarios, models, args.concurrency))
    print_table(results, models)
    if args.out:
        Path(args.out).write_text(json.dumps([asdict(r) for r in results], ensure_ascii=False, indent=2), "utf-8")
    worst = min(sum(r.passed for r in results if r.model == m) / len(scenarios) for m in models)
    sys.exit(0 if worst >= args.min_pass else 1)


if __name__ == "__main__":
    main()
