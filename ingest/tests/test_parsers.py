from __future__ import annotations

from datetime import date, datetime
from pathlib import Path

import pytest

from hallmark_ingest.parsers import ccil, descriptor, fbil, nsdl, nse, ratings

D = date(2026, 10, 14)


def test_bhavcopy_is_typed_and_classified(fixtures: Path) -> None:
    df = nse.parse_bhavcopy(fixtures / "nse" / "cbm_trd20261014.csv", D)
    assert df.height == 40
    assert set(df.columns) >= {"isin", "wap", "way", "total_trade_value_lakh", "instrument_class"}
    assert df["isin"].str.len_chars().min() == 12
    first = df.row(0, named=True)
    assert first == first | {
        "isin": "INE916DA7TB2",
        "wap": 98.1488,
        "way": 8.36,
        "total_trade_value_lakh": 5000.0,
    }
    assert set(df["instrument_class"]) <= {"BOND", "CP", "CD", "GSEC"}


@pytest.mark.parametrize(
    ("isin", "expected"),
    [
        ("INE514E14TQ3", "CP"),
        ("INE040A16JC3", "CD"),
        ("IN0020070069", "GSEC"),
        ("IN2020180070", "GSEC"),
        ("INE423A07484", "BOND"),
        ("US0378331005", "OTHER"),
    ],
)
def test_instrument_class_from_isin(isin: str, expected: str) -> None:
    assert nse.instrument_class(isin) == expected


def test_settlement_files_carry_class_and_denomination(fixtures: Path) -> None:
    df = nse.parse_settlement(
        fixtures / "nse" / "cbm_list_man_14102026.csv",
        D,
        listing="LISTED",
        counterparty_class="MANDATORY",
        status="SETTLED",
    )
    assert df.height == 30
    assert (df["trade_date"] <= df["settlement_date"]).all()
    assert set(df["listing"]) == {"LISTED"} and set(df["counterparty_class"]) == {"MANDATORY"}
    row = df.filter(df["isin"] == "INE04VS07479").row(0, named=True)
    assert row["face_value_inr"] == 100_000 and row["denomination_class"] == "INSTITUTIONAL"


def test_fail_file_two_digit_years_are_parsed_as_2026(fixtures: Path) -> None:
    df = nse.parse_settlement(
        fixtures / "nse" / "cbm_fail_14102026.csv", D, listing=None, counterparty_class=None, status="FAILED"
    )
    assert df["trade_date"].min().year == 2026
    assert set(df["status"]) == {"FAILED"}


def test_money_market_settlement(fixtures: Path) -> None:
    df = nse.parse_settlement(
        fixtures / "nse" / "CD_SettlementOrderReport_14102026.csv",
        D,
        listing="LISTED",
        counterparty_class=None,
        status="SETTLED",
        instrument="CD",
    )
    assert df.height == 10 and set(df["instrument_class"]) == {"CD"}
    assert df["way"].max() < 20


def test_ntrp_master_skips_banner(fixtures: Path) -> None:
    df = nse.parse_ntrp_master(fixtures / "nse" / "Corporate_bond_report_14-Oct-2026.csv", D)
    assert df.height > 20
    axis = df.filter(df["isin"] == "INE238A08484").row(0, named=True)
    assert axis["coupon_rate"] == 7.88 and axis["maturity_date"] == date(2032, 12, 13)


def test_fbil_workbook(fixtures: Path) -> None:
    path = fixtures / "fbil" / "fbil_gsec_20261008.xlsx"
    prices = fbil.parse_gsec_prices(path, date(2026, 10, 8))
    curve = fbil.parse_par_curve(path, date(2026, 10, 8))
    assert prices.height == 124 and curve.height == 200
    assert set(prices["security_kind"]) == {"FIXED", "FRB"}
    assert prices.filter(prices["security_kind"] == "FRB").height == 5
    assert curve["tenor_years"].min() == 0.25 and curve["tenor_years"].max() == 50


def test_fbil_range_files_deduplicate(fixtures: Path) -> None:
    files = [fixtures / "fbil" / "tbill_2025.json", fixtures / "fbil" / "tbill_2025.json"]
    tb = fbil.parse_tbill_curve(files)
    assert tb.height == 60 and tb["tenor_days"].min() == 7
    fx = fbil.parse_fx_reference([fixtures / "fbil" / "fx_2025.json"])
    jpy = fx.filter(fx["currency"] == "JPY")
    assert jpy.is_empty() or (jpy["units"] == 100).all()


def test_nsdl_parsers(fixtures: Path) -> None:
    n = fixtures / "nsdl"
    inst = nsdl.parse_instrument(n / "INE053F08403_instrument.json", D)
    assert inst["ownership"] == "PSU" and inst["cin"] == "U65910DL1986GOI026363"
    rows = nsdl.parse_ratings(n / "INE583D07687_ratings.json", D)
    assert rows[0]["agency"] == "IND-RA" and rows[0]["rating_symbol"] == "A+" and rows[0]["rating_notch"] == 5
    terms, flows = nsdl.parse_cashflows(n / "INE296A07UB9_cashflows.json", D)
    assert terms is not None and terms["coupon_rate"] == 7.7

    assert len(flows) == 4 and sum(f["is_principal"] for f in flows) == 1
    red = nsdl.parse_redemption(n / "INE476A08282_redemption.json", D, "INE476A08282")
    assert red["first_call_date"] == date(2031, 9, 18)


def test_schedule_warnings_on_real_schedules(fixtures: Path) -> None:
    n = fixtures / "nsdl"
    clean, _ = nsdl.parse_cashflows(n / "INE053F08403_cashflows.json", D)
    assert clean is not None and clean["schedule_warnings"] is None
    perp, _ = nsdl.parse_cashflows(n / "INE476A08282_cashflows.json", D)
    assert perp is not None and "NO_PRINCIPAL" not in (perp["schedule_warnings"] or "")
    linked, flows = nsdl.parse_cashflows(n / "INE093JA76M5_cashflows.json", D)
    assert linked is not None and flows == []


def test_ccil_snapshots(fixtures: Path) -> None:
    nd = ccil.parse_ndsom(fixtures / "ccil" / "ndsom_watch_1600.json", D, datetime(2026, 10, 15, 16, 0))
    assert nd.height > 20 and nd["last_yield"].is_between(3, 10).all()
    far = ccil.parse_far_limits(fixtures / "ccil" / "far_holdings_1600.json", D)
    assert set(far["instrument"]) >= {"Central Government Securities"}


@pytest.mark.parametrize(
    ("text", "coupon", "maturity", "face", "kind"),
    [
        ("ADANI ENTERPRISES LIMITED SR I 8.60 NCD 12JN28 FVRS1000", 8.6, date(2028, 1, 12), 1000, "NCD"),
        (
            "UTKARSH SMALL FINANCE BANK LIMITED SR A1 11.75 BD 25SP33 FVRS1LAC",
            11.75,
            date(2033, 9, 25),
            100_000,
            "BD",
        ),
        ("IIFL SAMASTA FINANCE LIMITED 9.5 NCD 23Jl27 FVRS10000", 9.5, date(2027, 7, 23), 10_000, "NCD"),
        ("MIDLAND MICROFIN LIMITED 10.65 NCD 01JUN28 FVRS1LAC", 10.65, date(2028, 6, 1), 100_000, "NCD"),
        ("CANARA BANK SR 1 8.10 BD FVRS1CR", 8.1, None, 10_000_000, "BD"),
        (
            "APEX HOMES PRIVATE LIMITED SR 1 NCD 30OT27 FVRS81666.67",
            None,
            date(2027, 10, 30),
            81_666.67,
            "NCD",
        ),
        ("BAJAJ FINANCE LIMITED 91D CP 11NOV26", None, date(2026, 11, 11), None, "CP"),
    ],
)
def test_descriptor_grammar(
    text: str, coupon: float | None, maturity: date | None, face: float | None, kind: str
) -> None:
    d = descriptor.parse(text)
    assert d.coupon == coupon and d.maturity == maturity and d.face_value == face and d.kind == kind


def test_market_linked_descriptor_and_denominations() -> None:
    assert descriptor.parse(
        "ANAND RATHI GLOBAL FINANCE LIMITED SR SP79 II BR NCD 29DC26 FVRS1LAC"
    ).market_linked
    assert [descriptor.denomination_class(v) for v in (None, 1000, 10_000, 50_000, 100_000)] == [
        "UNKNOWN",
        "RETAIL",
        "RETAIL",
        "HNI",
        "INSTITUTIONAL",
    ]


@pytest.mark.parametrize(
    ("raw", "symbol", "scale", "notch"),
    [
        ("AA-", "AA-", "LONG", 4),
        ("CRISIL AAA/Stable", "AAA", "LONG", 1),
        ("Provisional CARE AA+ (CE)", "AA+", "LONG", 2),
        ("A1+", "A1+", "SHORT", 1),
        ("IND BBB-", "BBB-", "LONG", 10),
        ("PP-MLD AA", "AA", "LONG", 3),
        ("Withdrawn", None, None, None),
        ("D", "D", "LONG", 20),
    ],
)
def test_rating_normalisation(raw: str, symbol: str | None, scale: str | None, notch: int | None) -> None:
    r = ratings.normalise(raw)
    assert (r.symbol, r.scale, r.notch) == (symbol, scale, notch)


def test_rating_flags_and_buckets() -> None:
    r = ratings.normalise("Provisional CARE AA+ (CE)")
    assert r.provisional and r.credit_enhanced and r.investment_grade
    assert ratings.normalise("BB+").investment_grade is False
    assert [ratings.rating_bucket(n) for n in (None, 1, 7, 9, 15, 20)] == [
        "UNRATED",
        "AAA",
        "A-",
        "BBB",
        "SUB_IG",
        "D",
    ]
