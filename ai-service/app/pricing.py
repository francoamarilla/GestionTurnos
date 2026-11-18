"""USD per million tokens, to put a cost on every turn (ai_turn.cost_usd, eval reports).

Cache reads bill at ~0.1x input, 5-minute cache writes at 1.25x input.
Unknown models cost 0 here: add them before trusting the budget.
"""

from app.contracts import Usage

# model -> (input, output) USD per 1M tokens
PRICES: dict[str, tuple[float, float]] = {
    "claude-haiku-5-5": (0.10, 0.50),
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-opus-5-5": (4.00, 20.00),
    "claude-haiku-4-5": (1.00, 5.00),
}


def cost_usd(model: str, usage: Usage) -> float:
    price_in, price_out = PRICES.get(model, (0.0, 0.0))
    return (
        usage.input_tokens * price_in
        + usage.cache_read_tokens * price_in * 0.1
        + usage.cache_write_tokens * price_in * 1.25
        + usage.output_tokens * price_out
    ) / 1_000_000
