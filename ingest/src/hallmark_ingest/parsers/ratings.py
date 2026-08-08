from __future__ import annotations

import re
from dataclasses import dataclass

LONG_TERM: tuple[str, ...] = (
    "AAA",
    "AA+",
    "AA",
    "AA-",
    "A+",
    "A",
    "A-",
    "BBB+",
    "BBB",
    "BBB-",
    "BB+",
    "BB",
    "BB-",
    "B+",
    "B",
    "B-",
    "C+",
    "C",
    "C-",
    "D",
)
SHORT_TERM: tuple[str, ...] = ("A1+", "A1", "A2+", "A2", "A3+", "A3", "A4+", "A4", "D")

LONG_TERM_NOTCH: dict[str, int] = {sym: i + 1 for i, sym in enumerate(LONG_TERM)}
SHORT_TERM_NOTCH: dict[str, int] = {sym: i + 1 for i, sym in enumerate(SHORT_TERM)}

INVESTMENT_GRADE_MAX_NOTCH = LONG_TERM_NOTCH["BBB-"]

_PREFIXES = re.compile(
    r"^(?:\[?(?:CRISIL|ICRA|CARE|IND|BWR|BRICKWORK|ACUITE|IVR|INFOMERICS|FITCH)\]?\s*)+",
    re.IGNORECASE,
)
_PROVISIONAL = re.compile(r"\b(?:PROVISIONAL|PROV\.?)\b", re.IGNORECASE)
_PP_MLD = re.compile(r"\bPP-?MLD\b", re.IGNORECASE)
_WITHDRAWN = re.compile(r"WITHDRAWN|SUSPENDED|NOT\s*RATED|NOT\s*MEANINGFUL|ISSUER NOT COOPERATING", re.I)


@dataclass(frozen=True)
class NormalisedRating:
    raw: str
    symbol: str | None
    scale: str | None
    notch: int | None
    credit_enhanced: bool
    structured: bool
    provisional: bool
    withdrawn: bool

    @property
    def investment_grade(self) -> bool | None:
        if self.scale != "LONG" or self.notch is None:
            return None
        return self.notch <= INVESTMENT_GRADE_MAX_NOTCH


def normalise(raw: str | None) -> NormalisedRating:
    text = (raw or "").strip()
    upper = text.upper()
    withdrawn = bool(_WITHDRAWN.search(upper))
    provisional = bool(_PROVISIONAL.search(upper))
    credit_enhanced = "(CE)" in upper.replace(" ", "")
    structured = "(SO)" in upper.replace(" ", "")
    cleaned = _PROVISIONAL.sub(" ", upper)
    cleaned = _PP_MLD.sub(" ", cleaned)
    cleaned = cleaned.replace("(CE)", " ").replace("(SO)", " ").replace("(IND)", " ")
    cleaned = cleaned.split("/")[0]
    cleaned = _PREFIXES.sub("", cleaned.strip())
    tokens = re.sub(r"[^A-Z0-9+\-]", " ", cleaned).split()
    token = tokens[0] if tokens else ""

    symbol: str | None = None
    scale: str | None = None
    notch: int | None = None
    if not withdrawn:
        if token in SHORT_TERM_NOTCH and token != "D":
            symbol, scale, notch = token, "SHORT", SHORT_TERM_NOTCH[token]
        elif token in LONG_TERM_NOTCH:
            symbol, scale, notch = token, "LONG", LONG_TERM_NOTCH[token]
    return NormalisedRating(
        raw=text,
        symbol=symbol,
        scale=scale,
        notch=notch,
        credit_enhanced=credit_enhanced,
        structured=structured,
        provisional=provisional,
        withdrawn=withdrawn,
    )


def rating_bucket(notch: int | None) -> str:
    if notch is None:
        return "UNRATED"
    if notch <= LONG_TERM_NOTCH["A-"]:
        return LONG_TERM[notch - 1]
    if notch <= LONG_TERM_NOTCH["BBB-"]:
        return "BBB"
    if notch < LONG_TERM_NOTCH["D"]:
        return "SUB_IG"
    return "D"
