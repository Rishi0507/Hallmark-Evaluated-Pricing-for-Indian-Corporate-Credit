from __future__ import annotations

import json
from pathlib import Path

DRAFT = "https://json-schema.org/draft/2020-12/schema"
OUT = Path(__file__).resolve().parents[1] / "contracts" / "schemas"

DATE = {"type": "string", "format": "date"}
NDATE = {"type": ["string", "null"], "format": "date"}
NUM = {"type": "number"}
NNUM = {"type": ["number", "null"]}
STR = {"type": "string"}
NSTR = {"type": ["string", "null"]}
ISIN = {"type": "string", "pattern": "^IN[A-Z0-9]{9}[0-9]$"}
INSTRUMENT = {"enum": ["BOND", "CP", "CD", "GSEC", "OTHER"]}


def envelope(name: str, payload: dict[str, object], description: str) -> dict[str, object]:
    return {
        "$schema": DRAFT,
        "$id": f"https://hallmark.dev/schemas/{name}.json",
        "title": name,
        "description": description,
        "type": "object",
        "required": ["eventId", "eventType", "schemaVersion", "source", "key", "producedAt", "payload"],
        "properties": {
            "eventId": {
                "type": "string",
                "description": "Deterministic SHA-256 of type, key, business date and payload; used for de-duplication.",
            },
            "eventType": {"const": name},
            "schemaVersion": {"const": 1},
            "source": STR,
            "key": STR,
            "businessDate": NDATE,
            "producedAt": {"type": "string", "format": "date-time"},
            "payload": payload,
        },
        "additionalProperties": False,
    }


def obj(required: list[str], properties: dict[str, object]) -> dict[str, object]:
    return {"type": "object", "required": required, "properties": properties}


RATING = obj(
    ["agency", "isCurrent"],
    {
        "agency": STR,
        "rawRating": NSTR,
        "symbol": NSTR,
        "scale": {"enum": ["LONG", "SHORT", None]},
        "notch": {"type": ["integer", "null"], "minimum": 1},
        "outlook": NSTR,
        "action": NSTR,
        "ratingDate": NDATE,
        "changeDate": NDATE,
        "watch": NSTR,
        "isCurrent": {"type": "boolean"},
        "creditEnhanced": {"type": "boolean"},
        "structured": {"type": "boolean"},
        "provisional": {"type": "boolean"},
        "withdrawn": {"type": "boolean"},
    },
)

CASHFLOW = obj(
    ["paymentDate", "amountPerUnit", "principal"],
    {
        "paymentDate": DATE,
        "dueDate": NDATE,
        "amountPerUnit": NUM,
        "principal": {"type": "boolean"},
        "event": NSTR,
    },
)

SCHEMAS: dict[str, dict[str, object]] = {
    "SecurityMasterRecord": envelope(
        "SecurityMasterRecord",
        obj(
            ["isin", "source", "snapshotDate"],
            {
                "isin": ISIN,
                "source": {"enum": ["NSDL", "NSE_NTRP"]},
                "snapshotDate": DATE,
                "description": NSTR,
                "issuerName": NSTR,
                "cin": NSTR,
                "lei": NSTR,
                "ownership": {"enum": ["PSU", "PRIVATE", None]},
                "issuerNature": NSTR,
                "sector": NSTR,
                "industry": NSTR,
                "basicIndustry": NSTR,
                "instrumentType": NSTR,
                "couponRate": NNUM,
                "couponBasis": NSTR,
                "paymentFrequency": NSTR,
                "dayCount": NSTR,
                "maturityDate": NDATE,
                "issueDate": NDATE,
                "faceValue": NNUM,
                "listingStatus": NSTR,
                "stepUp": {"type": ["boolean", "null"]},
                "stepUpTerms": NSTR,
                "firstCallDate": NDATE,
                "firstPutDate": NDATE,
                "scheduleWarnings": {"type": "array", "items": STR},
                "ratings": {"type": "array", "items": RATING},
                "cashflows": {"type": "array", "items": CASHFLOW},
            },
        ),
        "Reference data for one ISIN from one source. The refdata service merges sources into a golden record.",
    ),
    "MarketCurve": envelope(
        "MarketCurve",
        obj(
            ["asOfDate", "source", "curveType", "points"],
            {
                "asOfDate": DATE,
                "source": STR,
                "curveType": {"enum": ["GSEC_PAR"]},
                "compounding": {"enum": ["SEMI_ANNUAL"]},
                "points": {
                    "type": "array",
                    "minItems": 4,
                    "items": obj(["tenorYears", "parYield"], {"tenorYears": NUM, "parYield": NUM}),
                },
                "tbill": {
                    "type": "array",
                    "items": obj(["tenorDays", "yield"], {"tenorDays": {"type": "integer"}, "yield": NUM}),
                },
            },
        ),
        "FBIL G-sec par yield curve (semi-annual compounding, percent) and T-bill curve for one business date.",
    ),
    "ReportedTrade": envelope(
        "ReportedTrade",
        obj(
            ["isin", "tradeDate", "venue", "totalTradeValueLakh"],
            {
                "isin": ISIN,
                "tradeDate": DATE,
                "venue": {"const": "NSE"},
                "instrumentClass": INSTRUMENT,
                "lastPrice": NNUM,
                "lastYield": NNUM,
                "lastTradeValueLakh": NNUM,
                "totalTradeValueLakh": NUM,
                "wap": NNUM,
                "way": NNUM,
            },
        ),
        "Per-ISIN summary of trades reported on NSE for a trade date. Values in Rs lakh of face value, "
        "prices per 100 of face value, yields in percent.",
    ),
    "SettlementRecord": envelope(
        "SettlementRecord",
        obj(
            ["isin", "tradeDate", "settlementDate", "status", "nominalValueInr"],
            {
                "isin": ISIN,
                "description": NSTR,
                "tradeDate": DATE,
                "settlementDate": DATE,
                "instrumentClass": INSTRUMENT,
                "listing": {"enum": ["LISTED", "UNLISTED", None]},
                "counterpartyClass": {"enum": ["MANDATORY", "NON_MANDATORY", None]},
                "status": {"enum": ["SETTLED", "FAILED"]},
                "quantity": {"type": ["integer", "null"]},
                "nominalValueInr": NUM,
                "wap": NNUM,
                "way": NNUM,
                "faceValueInr": NNUM,
                "denominationClass": {"enum": ["RETAIL", "HNI", "INSTITUTIONAL", "UNKNOWN"]},
            },
        ),
        "Clearing corporation settlement (or fail) for one ISIN and trade date on one settlement date.",
    ),
    "EndOfDay": envelope(
        "EndOfDay",
        obj(
            ["businessDate", "counts"],
            {
                "businessDate": DATE,
                "counts": {"type": "object", "additionalProperties": {"type": "integer"}},
                "curveAvailable": {"type": "boolean"},
            },
        ),
        "Control event published after every input for a business date has been published.",
    ),
    "GoldenSecurity": envelope(
        "GoldenSecurity",
        obj(
            ["isin", "fieldSources"],
            {
                "isin": ISIN,
                "description": NSTR,
                "issuerName": NSTR,
                "issuerKey": NSTR,
                "cin": NSTR,
                "lei": NSTR,
                "ownership": NSTR,
                "sectorGroup": NSTR,
                "industry": NSTR,
                "instrumentType": NSTR,
                "couponRate": NNUM,
                "couponBasis": NSTR,
                "maturityDate": NDATE,
                "faceValue": NNUM,
                "denominationClass": NSTR,
                "firstCallDate": NDATE,
                "firstPutDate": NDATE,
                "stepUp": {"type": ["boolean", "null"]},
                "pricingEligible": {"type": "boolean"},
                "ineligibleReason": NSTR,
                "scheduleWarnings": {"type": "array", "items": STR},
                "ratings": {"type": "array", "items": RATING},
                "cashflows": {"type": "array", "items": CASHFLOW},
                "fieldSources": {"type": "object", "additionalProperties": {"type": "string"}},
                "conflicts": {
                    "type": "array",
                    "items": obj(
                        [],
                        {
                            "field": STR,
                            "chosenSource": STR,
                            "chosenValue": NSTR,
                            "otherSource": STR,
                            "otherValue": NSTR,
                        },
                    ),
                },
            },
        ),
        "Merged security record with field-level lineage and recorded source conflicts.",
    ),
    "TradeObservation": envelope(
        "TradeObservation",
        obj(
            ["isin", "tradeDate", "cleanPrice", "yieldPct", "faceValueLakh", "sizeClass"],
            {
                "isin": ISIN,
                "tradeDate": DATE,
                "cleanPrice": NUM,
                "yieldPct": NUM,
                "faceValueLakh": NUM,
                "sizeClass": {"enum": ["INSTITUTIONAL", "RETAIL"]},
                "flags": {"type": "array", "items": STR},
                "recomputedYieldPct": NNUM,
                "settledMandatoryShare": NNUM,
            },
        ),
        "A validated price observation used by the pricing service.",
    ),
    "TradeLifecycle": envelope(
        "TradeLifecycle",
        obj(
            ["isin", "tradeDate", "state"],
            {
                "isin": ISIN,
                "tradeDate": DATE,
                "state": {
                    "enum": [
                        "REPORTED",
                        "SETTLING",
                        "SETTLED",
                        "PARTIALLY_SETTLED",
                        "FAILED",
                        "UNSETTLED",
                        "OVER_SETTLED",
                        "SETTLED_UNREPORTED",
                    ]
                },
                "reportedFaceLakh": NNUM,
                "settledFaceLakh": NUM,
                "failedFaceLakh": NUM,
                "settledShare": NNUM,
                "lastSettlementDate": NDATE,
            },
        ),
        "State of one ISIN and trade date in the reporting to settlement lifecycle.",
    ),
    "Mark": envelope(
        "Mark",
        obj(
            ["isin", "valuationDate", "method", "cleanPrice", "yieldPct", "zSpreadBps", "confidence"],
            {
                "isin": ISIN,
                "valuationDate": DATE,
                "method": {"enum": ["TRADED", "TRADED_RETAIL", "CARRIED", "ISSUER_CURVE", "MATRIX"]},
                "cleanPrice": NUM,
                "dirtyPrice": NUM,
                "accrued": NUM,
                "yieldPct": NUM,
                "zSpreadBps": NUM,
                "gSpreadBps": NNUM,
                "modifiedDuration": NUM,
                "ratingBucket": STR,
                "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                "evidence": {
                    "type": "array",
                    "items": obj(
                        [],
                        {
                            "kind": STR,
                            "isin": STR,
                            "date": DATE,
                            "weight": NUM,
                            "spreadBps": NUM,
                            "faceValueLakh": NNUM,
                        },
                    ),
                },
            },
        ),
        "End-of-day evaluated mark with method, confidence and supporting evidence.",
    ),
    "ReconBreak": envelope(
        "ReconBreak",
        obj(
            ["breakId", "reconType", "breakType", "status", "businessKey"],
            {
                "breakId": STR,
                "reconType": {"enum": ["TRADE_VS_SETTLEMENT", "YIELD_RECOMPUTE", "REFDATA_SOURCES"]},
                "breakType": STR,
                "status": {"enum": ["OPEN", "ACKNOWLEDGED", "RESOLVED"]},
                "businessKey": STR,
                "businessDate": DATE,
                "severity": {"enum": ["LOW", "MEDIUM", "HIGH"]},
                "amountLakh": NNUM,
                "details": {"type": "object"},
            },
        ),
        "A reconciliation exception and its workflow state.",
    ),
}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, schema in SCHEMAS.items():
        (OUT / f"{name}.json").write_text(json.dumps(schema, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {len(SCHEMAS)} schemas to {OUT}")


if __name__ == "__main__":
    main()
