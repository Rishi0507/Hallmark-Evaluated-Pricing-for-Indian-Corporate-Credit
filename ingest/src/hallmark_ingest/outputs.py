from __future__ import annotations

from pathlib import Path

import polars as pl

from hallmark_ingest.lake import Lake

CSV_OUTPUTS: dict[str, str] = {
    "marks.csv": "svc_marks",
    "backtest.csv": "svc_backtest",
    "curve_fits.csv": "svc_curve_fits",
    "sector_offsets.csv": "svc_sector_offsets",
    "observations.csv": "svc_observations",
    "lifecycle.csv": "svc_lifecycle",
    "recon_outcomes.csv": "svc_recon_outcomes",
    "recon_yield.csv": "svc_recon_yield",
}


def load_outputs(lake: Lake, source: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    for filename, dataset in CSV_OUTPUTS.items():
        path = source / filename
        if not path.exists():
            continue
        frame = pl.read_csv(path, infer_schema_length=100_000, try_parse_dates=True)
        lake.write_silver(dataset, "part=all", frame)
        counts[dataset] = frame.height
    golden = source / "golden.jsonl"
    if golden.exists():
        frame = pl.read_ndjson(golden, infer_schema_length=None).select(
            "isin",
            "issuerName",
            "issuerKey",
            "ownership",
            "sectorGroup",
            "industry",
            "instrumentType",
            "couponRate",
            "couponBasis",
            "maturityDate",
            "faceValue",
            "denominationClass",
            "firstCallDate",
            "pricingEligible",
            "ineligibleReason",
            pl.col("ratings").list.len().alias("ratingRecords"),
            pl.col("scheduleWarnings").list.join(",").alias("scheduleWarnings"),
            pl.col("conflicts").list.len().alias("conflictCount"),
        )
        lake.write_silver("svc_golden", "part=all", frame)
        counts["svc_golden"] = frame.height
    return counts
