from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date

MONTHS: dict[str, int] = {
    "JN": 1,
    "FB": 2,
    "MR": 3,
    "AP": 4,
    "MY": 5,
    "JU": 6,
    "JL": 7,
    "AG": 8,
    "SP": 9,
    "OT": 10,
    "NV": 11,
    "DC": 12,
    "JAN": 1,
    "FEB": 2,
    "MAR": 3,
    "APR": 4,
    "MAY": 5,
    "JUN": 6,
    "JUL": 7,
    "AUG": 8,
    "SEP": 9,
    "OCT": 10,
    "NOV": 11,
    "DEC": 12,
}

INSTRUMENT_CODES = ("NCD", "BD", "BOND", "BONDS", "LOA", "DEB", "CP", "CD", "PTC", "STRPP")

_FACE = re.compile(r"FVRS\s*([0-9]+(?:\.[0-9]+)?)\s*(LAC|LAKH|CR)?", re.IGNORECASE)
_MATURITY = re.compile(r"\b(\d{2})([A-Za-z]{2,3})(\d{2})\b")
_COUPON = re.compile(r"(?<![\w.])(\d{1,2}(?:\.\d{1,4})?)\s+(?:" + "|".join(INSTRUMENT_CODES) + r")\b")
_KIND = re.compile(r"\b(" + "|".join(INSTRUMENT_CODES) + r")\b")
_ISSUER_STOP = re.compile(
    r"\s(?:SR|SER|SERIES|TR|TRANCHE|OP|OPT|CAT|\d{1,2}(?:\.\d+)?\s+(?:"
    + "|".join(INSTRUMENT_CODES)
    + r")|BR\s|\d+D\s+CP|CD\s)\b"
)


@dataclass(frozen=True)
class Descriptor:
    raw: str
    issuer: str | None
    coupon: float | None
    kind: str | None
    maturity: date | None
    face_value: float | None
    market_linked: bool


def parse_face_value(text: str) -> float | None:
    match = _FACE.search(text)
    if not match:
        return None
    value = float(match.group(1))
    unit = (match.group(2) or "").upper()
    if unit in {"LAC", "LAKH"}:
        value *= 100_000
    elif unit == "CR":
        value *= 10_000_000
    return value


def parse_maturity(text: str) -> date | None:

    body = _FACE.split(text)[0] if _FACE.search(text) else text
    for match in reversed(list(_MATURITY.finditer(body))):
        day, mon, yy = match.groups()
        month = MONTHS.get(mon.upper())
        if month is None:
            continue
        try:
            return date(2000 + int(yy), month, int(day))
        except ValueError:
            continue
    return None


_SERIES_TOKENS = {"SR", "SER", "SERIES", "TR", "TRANCHE", "OP", "OPT", "CAT"}


def _coupon(raw: str) -> float | None:

    for match in _COUPON.finditer(raw):
        before = raw[: match.start()].split()
        if before and before[-1].upper() in _SERIES_TOKENS:
            continue
        return float(match.group(1))
    return None


def parse(text: str) -> Descriptor:
    raw = " ".join(text.split())
    kind_match = _KIND.search(raw)
    issuer_match = _ISSUER_STOP.search(" " + raw)
    issuer = raw[: issuer_match.start() - 1].strip() if issuer_match and issuer_match.start() > 1 else None
    return Descriptor(
        raw=raw,
        issuer=issuer or None,
        coupon=_coupon(raw),
        kind=kind_match.group(1) if kind_match else None,
        maturity=parse_maturity(raw),
        face_value=parse_face_value(raw),
        market_linked=" BR " in f" {raw} " or "MLD" in raw,
    )


def denomination_class(face_value: float | None) -> str:
    if face_value is None:
        return "UNKNOWN"
    if face_value <= 10_000:
        return "RETAIL"
    if face_value < 100_000:
        return "HNI"
    return "INSTITUTIONAL"
