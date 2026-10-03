# Spark jobs

PySpark batch jobs over the silver lake.

| Job | Output | Description |
|---|---|---|
| `rating_transitions` | `rating_transitions/` | One-year rating migration matrix by instrument and agency pair, from a cohort date |
| `issuer_liquidity` | `issuer_liquidity/` | Settled face, ISINs, settlement days and retail share per issuer and month |

```bash
pip install "pyspark>=4.0,<5" pandas pytest
python -m hallmark_spark.jobs --silver ../../data/silver --out ../../data/gold/spark --cohort 2025-10-01
pytest tests
```

Spark 4 needs Java 17 or 21. Writing Parquet on Windows also needs the Hadoop native binaries (`winutils.exe`), so the full job is run on Linux or in CI. The tests build DataFrames from real records with Arrow and do not write files.
