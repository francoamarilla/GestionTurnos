"""Cheap, deterministic guardrails around the model (propuesta section 8).

Input: obvious prompt-injection phrasing is flagged for analysis, not
blocked - the real protection is that tools can't reach other customers or
businesses, whatever the model is talked into.

Output (grounding): any time or price in the reply must appear in what the
tools returned this turn, in the pending draft, or in the customer's own
words. Otherwise the loop regenerates once; if it happens again, the
sentences carrying the invented data are dropped.
"""

import re

MAX_REPLY_CHARS = 1200

_INJECTION = re.compile(
    r"ignor(a|á|e|ar)\w*\s+(tus|las|todas|tu|the|all|previous)|olvid(a|á)\w*\s+(tus|las)\s+(instrucciones|reglas)"
    r"|system prompt|prompt del sistema|tus instrucciones|you are now|jailbreak|modo desarrollador"
    r"|developer mode|act(u|ú)a como|pretend to be",
    re.IGNORECASE,
)
# Digit lookarounds, not \b: ISO timestamps glue the time to a "T".
_TIME = re.compile(r"(?<!\d)(\d{1,2})[:.](\d{2})(?!\d)")
_PRICE = re.compile(r"\$\s?(\d[\d.,]*)")
_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_SENTENCE = re.compile(r"(?<=[.!?])\s+|\n+")


def looks_like_injection(text: str) -> bool:
    return bool(_INJECTION.search(text))


def _price_value(raw: str) -> float | None:
    """es-AR money: '8.000' = 8000, '8.000,50' = 8000.5, '8000.00' = 8000."""
    s = raw.rstrip(".,")
    if "," in s:
        s = s.replace(".", "").replace(",", ".")
    elif re.fullmatch(r"\d{1,3}(\.\d{3})+", s):
        s = s.replace(".", "")
    try:
        return float(s)
    except ValueError:
        return None


def _times(text: str) -> set[tuple[int, int]]:
    return {(int(h), int(m)) for h, m in _TIME.findall(text)}


def ungrounded(reply: str, evidence: str) -> list[str]:
    """Times and prices in `reply` that `evidence` doesn't back."""
    known_times = _times(evidence)
    known_numbers = {float(n) for n in _NUMBER.findall(evidence)}
    known_numbers |= {v for v in (_price_value(p) for p in _PRICE.findall(evidence)) if v is not None}
    missing = [f"{h}:{m:02d}" for h, m in _times(reply) if (h, m) not in known_times]
    for raw in _PRICE.findall(reply):
        value = _price_value(raw)
        if value is not None and value not in known_numbers:
            missing.append(f"${raw}")
    return missing


def strip_ungrounded(reply: str, evidence: str) -> str:
    kept = [s for s in _SENTENCE.split(reply) if s.strip() and not ungrounded(s, evidence)]
    return " ".join(kept).strip()


def cap_length(reply: str) -> str:
    return reply if len(reply) <= MAX_REPLY_CHARS else reply[: MAX_REPLY_CHARS - 1].rstrip() + "…"
