import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, ChartTooltip, Kpi, axis, grid, useData } from "../components/ui";
import type { Overview as OverviewData } from "../data";
import { dayLabel, fmt, fmtBps, fmtInt, fmtPct, humanize } from "../format";

export default function Overview() {
  const { data, error } = useData<OverviewData>("overview");
  if (error) return <p className="muted">Snapshot unavailable: {error}</p>;
  if (!data) return <p className="muted">Loading</p>;
  const c = data.coverage;
  const bondSettlement = data.recon.filter(
    (r) => r.recon_type === "TRADE_VS_SETTLEMENT" && r.instrument_class === "BOND" && r.result !== "PENDING",
  );
  const bondTotal = bondSettlement.reduce((s, r) => s + r.outcomes, 0);
  const bondMatched = bondSettlement.filter((r) => r.result === "MATCHED").reduce((s, r) => s + r.outcomes, 0);
  const yields = data.recon.filter((r) => r.recon_type === "YIELD_RECOMPUTE");
  const yieldTotal = yields.reduce((s, r) => s + r.outcomes, 0);
  const yieldMatched = yields.filter((r) => r.result !== "BREAK").reduce((s, r) => s + r.outcomes, 0);
  const comparison = [
    { name: "Hallmark mark", value: data.backtest.model.median_abs, mae: data.backtest.model.mae },
    { name: "Last traded yield", value: data.backtest.lastTradedYield.median_abs, mae: data.backtest.lastTradedYield.mae },
    {
      name: "Last spread, full G-sec move",
      value: data.backtest.lastSpreadCarried.median_abs,
      mae: data.backtest.lastSpreadCarried.mae,
    },
  ];

  return (
    <>
      <section>
        <p className="prose">
          On a typical day only a few hundred of India's roughly 26,000 outstanding corporate bonds and NCDs trade.
          Hallmark produces a daily evaluated mark for every recently traded bond from public data alone: NSE trade
          reports and clearing settlements, FBIL G-sec benchmarks and NSDL depository reference data. Each mark states
          its method, a confidence score and the trades behind it, and every mark is scored out of sample against the
          next institutional trade. Snapshot as of <strong>{dayLabel(data.asOf)} 2026</strong>.
        </p>
      </section>
      <section className="grid kpis">
        <Kpi label="Reported trade rows" value={fmtInt(c.reported_rows)} note={`${c.first_date} to ${c.last_date}`} />
        <Kpi label="Settlement records" value={fmtInt(c.settlement_rows)} note="NSE Clearing, settled and failed" />
        <Kpi label="Securities in golden store" value={fmtInt(c.securities)} note={`${fmtInt(c.priceable)} priceable`} />
        <Kpi label="Marks per day" value={fmtInt(c.marks_per_day)} note={`${c.valuation_days} valuation days`} />
        <Kpi
          label="Median error, carried marks"
          value={fmtBps(data.backtest.byMethod.find((m) => m.method === "CARRIED")?.median_abs)}
          note={`Out of sample from ${data.testFrom}`}
        />
        <Kpi label="Bond trades reconciled" value={fmtPct(bondTotal ? bondMatched / bondTotal : null)} note="Reported against settled" />
        <Kpi label="Yields reproduced" value={fmtPct(yieldTotal ? yieldMatched / yieldTotal : null)} note="Within 25 bp or explained" />
      </section>
      <section className="grid two">
        <Card
          title="Out-of-sample error against naive baselines"
          sub={`Median absolute yield error on ${fmtInt(data.backtest.model.n)} institutional trades in bonds that had traded in the previous 30 days.`}
        >
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={comparison} layout="vertical" margin={{ left: 40, right: 24 }}>
              <CartesianGrid {...grid} horizontal={false} vertical />
              <XAxis type="number" {...axis} unit=" bp" />
              <YAxis type="category" dataKey="name" {...axis} width={170} />
              <Tooltip
                cursor={{ fill: "var(--surface-2)" }}
                content={<ChartTooltip valueFormatter={(v) => fmtBps(v)} />}
              />
              <Bar dataKey="value" name="Median abs. error" radius={[0, 4, 4, 0]} barSize={22}>
                {comparison.map((d, i) => (
                  <Cell key={d.name} fill={i === 0 ? "var(--series-1)" : "var(--text-muted)"} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
          <p className="sub">
            Mean absolute error: {comparison.map((d) => `${d.name} ${fmt(d.mae, 1)} bp`).join(", ")}.
          </p>
        </Card>
        <Card title={`Marks by method on ${dayLabel(data.asOf)}`} sub="Average confidence in brackets.">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart
              data={data.methodMix.map((m) => ({ ...m, label: `${humanize(m.method)} (${fmt(m.avg_confidence, 2)})` }))}
              layout="vertical"
              margin={{ left: 40, right: 24 }}
            >
              <CartesianGrid {...grid} horizontal={false} vertical />
              <XAxis type="number" {...axis} />
              <YAxis type="category" dataKey="label" {...axis} width={170} />
              <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip valueFormatter={fmtInt} />} />
              <Bar dataKey="n" name="Marks" fill="var(--series-1)" radius={[0, 4, 4, 0]} barSize={22} />
            </BarChart>
          </ResponsiveContainer>
        </Card>
      </section>
    </>
  );
}
