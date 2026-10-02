const integer = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

export function fmtInt(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? "-" : integer.format(v);
}

export function fmt(v: number | null | undefined, digits = 2): string {
  if (v == null || Number.isNaN(v)) {
    return "-";
  }
  return v.toLocaleString("en-IN", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function fmtPct(v: number | null | undefined, digits = 1): string {
  return v == null || Number.isNaN(v) ? "-" : `${fmt(v * 100, digits)}%`;
}

export function fmtBps(v: number | null | undefined, digits = 1): string {
  return v == null || Number.isNaN(v) ? "-" : `${fmt(v, digits)} bp`;
}

export function fmtCrore(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) {
    return "-";
  }
  if (Math.abs(v) >= 100_000) {
    return `Rs ${fmt(v / 100_000, 2)} lakh cr`;
  }
  return `Rs ${fmtInt(v)} cr`;
}

export function monthLabel(iso: string): string {
  const d = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  return d.toLocaleDateString("en-GB", { month: "short", year: "2-digit", timeZone: "UTC" });
}

export function dayLabel(iso: string): string {
  const d = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
}

export function humanize(code: string | null | undefined): string {
  if (!code) {
    return "-";
  }
  return code
    .toLowerCase()
    .split("_")
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(" ")
    .replace(/\bnbfc\b/i, "NBFC")
    .replace(/\bpsu\b/i, "PSU")
    .replace(/\bhfc\b/i, "HFC")
    .replace(/\big\b/i, "IG");
}
