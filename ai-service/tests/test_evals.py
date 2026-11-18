"""The eval harness itself: scenarios load, the fake core enforces the rules, graders grade."""

import asyncio
import re
from typing import Any

from conftest import ScriptedLLM, calls, last_tool_result, text

from evals.run import load_scenarios, run_scenario

ANA = ("identify_customer", {"name": "Ana López", "phone": "1155550001"})
MARCOS = ("identify_customer", {"name": "Marcos", "phone": "1166660002"})


def scenario(id_: str) -> dict[str, Any]:
    return load_scenarios(ids=[id_])[0]


def commit_from_prepare(tool: str) -> Any:
    return lambda messages: calls((tool, {"draft_id": last_tool_result(messages)["draftId"]}))


def test_every_scenario_loads_and_has_expectations() -> None:
    scenarios = load_scenarios()
    assert len(scenarios) >= 25
    assert len({s["id"] for s in scenarios}) == len(scenarios)
    assert all(s["turns"] and s["expect"] for s in scenarios)
    assert len(load_scenarios(tag="smoke")) >= 6


def test_precise_booking_passes() -> None:
    llm = ScriptedLLM(
        [
            calls(ANA),
            calls(
                (
                    "prepare_booking",
                    {
                        "service_text": "corte",
                        "professional_text": "Juan",
                        "date_text": "jueves 19",
                        "time_text": "10:30",
                    },
                )
            ),
            commit_from_prepare("commit_booking"),
            text("¡Listo! Corte con Juan Pérez el jue 19/11 a las 10:30, $8000."),
        ]
    )

    result = asyncio.run(run_scenario(scenario("booking_precise_auto"), llm, use_judge=False))

    assert result.passed, result.failures


def test_confirm_draft_cannot_be_committed_in_the_same_turn() -> None:
    # A model that tries to commit a CONFIRM draft right away is refused by
    # the core (409), and only the commit after the yes goes through.
    llm = ScriptedLLM(
        [
            calls(MARCOS),
            calls(("prepare_booking", {"service_text": "barba", "date_text": "mañana", "time_text": "tipo 10"})),
            commit_from_prepare("commit_booking"),
            text("Te reservo barba mañana a las 10:00, ¿confirmás?"),
            # Turn 2: the pending draft reaches the model through the system prompt.
            lambda messages: calls(("commit_booking", {"draft_id": _draft_id_in(llm.systems[-1][-1])})),
            text("¡Listo! Barba mañana a las 10:00."),
        ]
    )

    result = asyncio.run(run_scenario(scenario("booking_approximate_time_confirms_then_books"), llm, use_judge=False))

    assert result.passed, result.failures


def test_wrong_behaviour_fails_with_a_reason() -> None:
    llm = ScriptedLLM([text("El corte sale $9.500."), text("El corte sale $9.500.")])

    result = asyncio.run(run_scenario(scenario("info_price"), llm, use_judge=False))

    assert not result.passed
    assert any("list_services" in f for f in result.failures)


def test_someone_elses_appointment_stays_untouched() -> None:
    llm = ScriptedLLM(
        [
            calls(MARCOS),
            calls(("prepare_cancellation", {"appointment_id": "a-ana"})),
            text("No encuentro ese turno entre los tuyos."),
            calls(("commit_cancellation", {"draft_id": "d-made-up"})),
            text("No pude cancelar ese turno."),
        ]
    )

    result = asyncio.run(run_scenario(scenario("injection_other_customers_appointment"), llm, use_judge=False))

    assert result.passed, result.failures


def _draft_id_in(system_block: str) -> str:
    match = re.search(r"draftId (\S+),", system_block)
    assert match, system_block
    return match.group(1)
