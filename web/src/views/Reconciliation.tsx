import { useMemo } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, ChartTooltip, type Column, DataTable, Kpi, Legend, SERIES, axis, grid, useData } from "../components/ui";
import type { Recon, ReconRow } from "../data";
import { dayLabel, fmt, fmtCrore, fmtInt, fmtPct, humanize, monthLabel } from "../format";

type BreakRow = Recon["breaks"][number];
type YieldRow = Recon["yieldBreaks"][number];

function severityClass(s: string) {
  return s === "HIGH" ? "critical" : s === "MEDIUM" ? "warning" : "good";
}

export default function Reconciliation() {
  const { data } = useData<Recon>("recon");
  const daily = useMemo(() => {
    if (!data) return [];
    const byDate = new Map<string, Record<string, number | string>>();
    data.daily.forEach((d) => {
      const row = byDate.get(d.business_date) ?? { date: d.business_date, MATCHED: 0, BREAK: 0, PENDING: 0 };
      row[d.result] = d.n;
      byDate.set(d.business_date, row);
    });
    return [...byDate.values()];
  }, [data]);
  if (!data) return <p className="muted">Loading</p>;

  const total = (rows: ReconRow[]) => rows.reduce((s, r) => s + r.outcomes, 0);
  const bond = data.summary.filter((r) => r.recon_type === "TRADE_VS_SETTLEMENT" && r.instrument_class === "BOND");
  const bondClosed = bond.filter((r) => r.result !== "PENDING");
  const yields = data.summary.filter((r) => r.recon_type === "YIELD_RECOMPUTE");
  const refdata = data.summary.filter((r) => r.recon_type === "REFDATA_SOURCES");

  const summaryColumns: Column<ReconRow>[] = [
    { key: "recon_type", label: "Control", render: (r) => humanize(r.recon_type) },
    { key: "instrument_class", label: "Class" },
    { key: "result", label: "Result", render: (r) => humanize(r.result) },
    { key: "break_type", label: "Break type", render: (r) => (r.break_type === "NONE" ? "-" : humanize(r.break_type)) },
    { key: "outcomes", label: "Count", numeric: true, render: (r) => fmtInt(r.outcomes) },
    { key: "amount_crore", label: "Face value", numeric: true, render: (r) => fmtCrore(r.amount_crore) },
  ];
  const breakColumns: Column<BreakRow>[] = [
    { key: "business_date", label: "Trade date", render: (r) => dayLabel(r.business_date) },
    { key: "isin", label: "ISIN", render: (r) => <span className="num">{r.isin}</span> },
    { key: "issuer_name", label: "Issuer", render: (r) => (r.issuer_name ?? "-").slice(0, 30) },
    { key: "break_type", label: "Break", render: (r) => humanize(r.break_type) },
    { key: "severity", label: "Severity", render: (r) => <span className={`status ${severityClass(r.severity)}`}>{humanize(r.severity)}</span> },
    { key: "amount_lakh", label: "Rs lakh", numeric: true, render: (r) => fmt(r.amount_lakh, 1) },
  ];
  const yieldColumns: Column<YieldRow>[] = [
    { key: "trade_date", label: "Trade date", render: (r) => dayLabel(r.trade_date) },
    { key: "isin", label: "ISIN", render: (r) => <span className="num">{r.isin}</span> },
    { key: "issuer_name", label: "Issuer", render: (r) => (r.issuer_name ?? "-").slice(0, 30) },
    { key: "break_type", label: "Finding", render: (r) => humanize(r.break_type) },
    {
      key: "details",
      label: "NSE vs recomputed",
      render: (r) => {
        const d = JSON.parse(r.details);
        return <span className="num">{`${fmt(d.reportedYieldPct, 2)} / ${fmt(d.recomputedYieldPct, 2)}`}</span>;
      },
    },
  ];

  return (
    <>
      <section className="grid kpis">
        <Kpi label="Bond trade dates matched" value={fmtPct(total(bond.filter((r) => r.result === "MATCHED")) / total(bondClosed))} note={`${fmtInt(total(bondClosed))} with the window closed`} />
        <Kpi label="Settlement fails" value={fmtInt(total(bond.filter((r) => r.break_type === "SETTLEMENT_FAILED")))} note="Bond trade dates" />
        <Kpi label="NSE yields reproduced" value={fmtPct(total(yields.filter((r) => r.result !== "BREAK")) / total(yields))} note="Matched or explained" />
        <Kpi label="Schedule defects found" value={fmtInt(total(yields.filter((r) => r.break_type.startsWith("SCHEDULE"))))} note="Depository data issues" />
        <Kpi label="NSDL and NSE terms agree" value={fmtPct(total(refdata.filter((r) => r.result === "MATCHED")) / total(refdata))} note="Coupon and maturity" />
      </section>
      <section className="grid two">
        <Card title="Bond trades: reported against settled" sub="Trade dates per day by outcome; recent days stay pending until the seven-day window closes.">
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={daily}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="date" {...axis} tickFormatter={(v: string) => monthLabel(v)} minTickGap={40} />
              <YAxis {...axis} width={40} />
              <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip labelFormatter={(l) => dayLabel(String(l))} valueFormatter={fmtInt} />} />
              <Bar dataKey="MATCHED" name="Matched" stackId="a" fill={SERIES[0]} />
              <Bar dataKey="BREAK" name="Break" stackId="a" fill={SERIES[1]} />
              <Bar dataKey="PENDING" name="Pending" stackId="a" fill={SERIES[2]} />
            </BarChart>
          </ResponsiveContainer>
          <Legend items={[{ label: "Matched", color: SERIES[0] }, { label: "Break", color: SERIES[1] }, { label: "Pending", color: SERIES[2] }]} />
        </Card>
        <Card title="What the controls found" sub="All outcomes, April to October 2026. Most CD trades are reported outside the NSE bhavcopy and show as settled-not-reported; G-secs that settle at CCIL show as not settled.">
          <div style={{ maxHeight: 300, overflowY: "auto" }}>
            <DataTable rows={data.summary} columns={summaryColumns} rowKey={(r) => `${r.recon_type}-${r.instrument_class}-${r.result}-${r.break_type}`} initialSort={{ key: "outcomes", desc: true }} />
          </div>
        </Card>
      </section>
      <section className="grid two">
        <div>
          <h3 className="section-title">Recent bond settlement breaks</h3>
          <DataTable rows={data.breaks} columns={breakColumns} rowKey={(r) => `${r.isin}-${r.business_date}`} maxRows={60} />
        </div>
        <div>
          <h3 className="section-title">Yield recompute findings</h3>
          <DataTable rows={data.yieldBreaks} columns={yieldColumns} rowKey={(r) => `${r.isin}-${r.trade_date}`} maxRows={60} />
        </div>
      </section>
    </>
  );
}
