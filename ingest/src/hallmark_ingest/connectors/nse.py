from __future__ import annotations

from datetime import date

from hallmark_ingest.connectors.base import DailyFile

ARCHIVE = "https://nsearchives.nseindia.com"
SOURCE = "nse"

SETTLEMENT_CLASSES: dict[str, tuple[str, str]] = {
    "list_man": ("LISTED", "MANDATORY"),
    "list_non_man": ("LISTED", "NON_MANDATORY"),
    "unlist_man": ("UNLISTED", "MANDATORY"),
    "unlist_non_man": ("UNLISTED", "NON_MANDATORY"),
}


def _ddmmyyyy(d: date) -> str:
    return d.strftime("%d%m%Y")


def _settlement(suffix: str) -> DailyFile:
    return DailyFile(
        source=SOURCE,
        name=f"cbm_settle_{suffix}",
        url_for=lambda d: f"{ARCHIVE}/content/cbm/cbm_{suffix}_{_ddmmyyyy(d)}.csv",
        filename_for=lambda d: f"cbm_{suffix}_{_ddmmyyyy(d)}.csv",
        required_marker=b"ISIN",
    )


BHAVCOPY = DailyFile(
    source=SOURCE,
    name="cbm_bhavcopy",
    url_for=lambda d: f"{ARCHIVE}/archives/debt/cbm/cbm_trd{d:%Y%m%d}.csv",
    filename_for=lambda d: f"cbm_trd{d:%Y%m%d}.csv",
    required_marker=b"ISIN",
)

FAILS = DailyFile(
    source=SOURCE,
    name="cbm_fail",
    url_for=lambda d: f"{ARCHIVE}/content/cbm/cbm_fail_{_ddmmyyyy(d)}.csv",
    filename_for=lambda d: f"cbm_fail_{_ddmmyyyy(d)}.csv",
    required_marker=b"ISIN",
)

NTRP_MASTER = DailyFile(
    source=SOURCE,
    name="ntrp_master",
    url_for=lambda d: f"{ARCHIVE}/content/debt/Corporate_bond_report_{d:%d-%b-%Y}.csv",
    filename_for=lambda d: f"Corporate_bond_report_{d:%d-%b-%Y}.csv",
    required_marker=b"Negotiated Trade Reporting",
)

CP_SETTLEMENT = DailyFile(
    source=SOURCE,
    name="cp_settle",
    url_for=lambda d: f"{ARCHIVE}/content/cd/CP_SettlementOrderReport_{_ddmmyyyy(d)}.csv",
    filename_for=lambda d: f"CP_SettlementOrderReport_{_ddmmyyyy(d)}.csv",
    required_marker=b"ISIN",
)

CD_SETTLEMENT = DailyFile(
    source=SOURCE,
    name="cd_settle",
    url_for=lambda d: f"{ARCHIVE}/content/cd/CD_SettlementOrderReport_{_ddmmyyyy(d)}.csv",
    filename_for=lambda d: f"CD_SettlementOrderReport_{_ddmmyyyy(d)}.csv",
    required_marker=b"ISIN",
)

SETTLEMENT_FILES: tuple[DailyFile, ...] = tuple(_settlement(s) for s in SETTLEMENT_CLASSES)

DAILY_FILES: tuple[DailyFile, ...] = (
    BHAVCOPY,
    *SETTLEMENT_FILES,
    FAILS,
    CP_SETTLEMENT,
    CD_SETTLEMENT,
)
