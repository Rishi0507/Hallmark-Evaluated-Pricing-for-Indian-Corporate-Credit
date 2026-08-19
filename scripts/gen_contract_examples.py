from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ingest" / "src"))

from hallmark_ingest import events as ev
from hallmark_ingest.lake import Lake
from hallmark_ingest.publish import SchemaValidator


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--date", default="2026-10-14")
    parser.add_argument("--isin", action="append", default=[])
    parser.add_argument("--data-dir", default=str(ROOT / "data"))
    args = parser.parse_args()

    lake = Lake(Path(args.data_dir))
    day = date.fromisoformat(args.date)
    out = ROOT / "contracts" / "examples"
    out.mkdir(parents=True, exist_ok=True)
    validator = SchemaValidator()

    examples: dict[str, list[dict[str, object]]] = {}
    curve = ev.curve_event(lake, day, lake.read_silver("tbill_curve"))
    if curve:
        examples["MarketCurve"] = [curve]
    reported = ev.reported_events(lake, day)
    settlements = ev.settlement_events(lake, day)
    wanted = set(args.isin)
    examples["ReportedTrade"] = [e for e in reported if e["key"] in wanted][:3] or reported[:3]
    examples["SettlementRecord"] = [e for e in settlements if e["key"] in wanted][:3] or settlements[:3]
    examples["SettlementRecord"] += [e for e in settlements if e["payload"]["status"] == "FAILED"][:1]
    examples["EndOfDay"] = [
        ev.eod_event(day, {"reported": len(reported), "settlements": len(settlements)}, True)
    ]
    securities = list(ev.security_events(lake, wanted or None))
    examples["SecurityMasterRecord"] = securities

    for name, items in examples.items():
        for item in items:
            validator.validate(item)
        target = out / f"{name}.json"
        target.write_text(json.dumps(items, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"{name}: {len(items)} example(s) -> {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
