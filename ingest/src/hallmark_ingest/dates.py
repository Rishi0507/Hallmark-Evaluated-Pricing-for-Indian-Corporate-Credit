from __future__ import annotations

from collections.abc import Iterator
from datetime import date, timedelta


def weekdays(start: date, end: date) -> Iterator[date]:
    day = start
    while day <= end:
        if day.weekday() < 5:
            yield day
        day += timedelta(days=1)


def month_end_weekdays(start: date, end: date) -> list[date]:
    result: dict[tuple[int, int], date] = {}
    for day in weekdays(start, end):
        result[(day.year, day.month)] = day
    out = sorted(result.values())
    if end.weekday() < 5 and end not in out:
        out.append(end)
    return out


def year_slices(start: date, end: date) -> Iterator[tuple[date, date]]:
    year = start.year
    while year <= end.year:
        lo = max(start, date(year, 1, 1))
        hi = min(end, date(year, 12, 31))
        yield lo, hi
        year += 1


def parse_flexible(value: str) -> date:
    from datetime import datetime

    text = value.strip()
    for fmt in ("%d-%b-%Y", "%d-%b-%y", "%Y-%m-%d", "%d-%m-%Y", "%d/%m/%Y", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    raise ValueError(f"unrecognised date: {value!r}")
