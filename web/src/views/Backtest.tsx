import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, ChartTooltip, type Column, DataTable, Legend, SERIES, axis, grid, useData } from "../components/ui";
import type { Backtest as BacktestData } from "../data";
import { fmt, fmtBps, fmtInt, monthLabel } from "../format";

type Bucket = BacktestData["byBucket"][number];

export default function Backtest() {
  const { data } = useData<BacktestData>("backtest");
  if (!data) return <p className="muted">Loading</p>;
  const carried = data.monthly
    .filter((m) => m.method === "CARRIED")
    .map((m) => ({
      month: m.month,
      model: m.mae_bps,
      lastYield: m.last_yield_mae_bps,
      carriedSpread: m.carried_spread_mae_bps,
    }));
  const methods = ["CARRIED", "ISSUER_CURVE", "MATRIX"];
  const byMonth = new Map<string, Record<string, number | string>>();
  data.monthly.forEach((m) => {
    const row = byMonth.get(m.month) ?? { month: m.month };
    row[m.method] = m.median_abs_error_bps;
    byMonth.set(m.month, row);
  });
  const bucketColumns: Column<Bucket>[] = [
    { key: "rating_bucket", label: "Rating bucket" },
    { key: "n", label: "Predictions", numeric: true, render: (r) => fmtInt(r.n) },
    { key: "median_abs", label: "Median abs. error", numeric: true, render: (r) => fmtBps(r.median_abs) },
    { key: "mae", label: "Mean abs. error", numeric: true, render: (r) => fmtBps(r.mae) },
  ];
  return (
    <>
      <section>
        <p className="prose">
          Each day the model is fitted on trades up to the previous business day and asked to price every bond that
          then trades in institutional size. The prediction is scored against the traded yield. Baselines use the same
          trades: the last traded yield unchanged, and the last traded spread carried onto the new G-sec curve. The
          carry parameters were chosen on April to June 2026; July onwards is out of sample.
        </p>
      </section>
      <section className="grid two">
        <Card title="Carried marks against baselines" sub="Mean absolute yield error by month, basis points.">
          <ResponsiveContainer width="100%" height={250}>
            <LineChart data={carried}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="month" {...axis} tickFormatter={(v: string) => monthLabel(v)} />
              <YAxis {...axis} width={40} />
              <Tooltip content={<ChartTooltip labelFormatter={(l) => monthLabel(String(l))} valueFormatter={(v) => fmtBps(v)} />} />
              <Line dataKey="model" name="Hallmark mark" stroke={SERIES[0]} strokeWidth={2} dot={{ r: 4 }} />
              <Line dataKey="lastYield" name="Last traded yield" stroke={SERIES[1]} strokeWidth={2} dot={{ r: 4 }} />
              <Line dataKey="carriedSpread" name="Last spread, full G-sec move" stroke={SERIES[2]} strokeWidth={2} dot={{ r: 4 }} />
            </LineChart>
          </ResponsiveContainer>
          <Legend
            items={[
              { label: "Hallmark mark", color: SERIES[0] },
              { label: "Last traded yield", color: SERIES[1] },
              { label: "Last spread, full G-sec move", color: SERIES[2] },
            ]}
          />
        </Card>
        <Card title="Error by marking method" sub="Median absolute yield error by month, basis points.">
          <ResponsiveContainer width="100%" height={250}>
            <LineChart data={[...byMonth.values()]}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="month" {...axis} tickFormatter={(v: string) => monthLabel(v)} />
              <YAxis {...axis} width={40} />
              <Tooltip content={<ChartTooltip labelFormatter={(l) => monthLabel(String(l))} valueFormatter={(v) => fmtBps(v)} />} />
              {methods.map((m, i) => (
                <Line key={m} dataKey={m} name={m.replace("_", " ").toLowerCase()} stroke={SERIES[i]} strokeWidth={2} dot={{ r: 4 }} />
              ))}
            </LineChart>
          </ResponsiveContainer>
          <Legend items={methods.map((m, i) => ({ label: m.replace("_", " ").toLowerCase(), color: SERIES[i] }))} />
        </Card>
        <Card title="Confidence is calibrated" sub="Median absolute error by confidence band, out of sample.">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={data.calibration}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="band" {...axis} />
              <YAxis {...axis} width={40} />
              <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip valueFormatter={(v) => fmtBps(v)} />} />
              <Bar dataKey="median_abs" name="Median abs. error" fill={SERIES[0]} radius={[4, 4, 0, 0]} barSize={36} />
            </BarChart>
          </ResponsiveContainer>
          <p className="sub">{data.calibration.map((c) => `${c.band}: ${fmtInt(c.n)} trades`).join(", ")}.</p>
        </Card>
        <Card title="Distribution of errors" sub="Predicted minus traded yield, basis points, clipped at 100.">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={data.errorHistogram} barCategoryGap={1}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="bin" {...axis} />
              <YAxis {...axis} width={44} />
              <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip labelFormatter={(l) => `${l} bp`} valueFormatter={fmtInt} />} />
              <Bar dataKey="n" name="Predictions" fill={SERIES[0]} />
            </BarChart>
          </ResponsiveContainer>
        </Card>
      </section>
      <section>
        <h3 className="section-title">By rating bucket, out of sample</h3>
        <DataTable rows={data.byBucket} columns={bucketColumns} rowKey={(r) => r.rating_bucket} />
        <p className="sub muted">Error grows as ratings fall; sub-investment-grade and unrated paper trades rarely and dispersed: {fmt(data.byBucket.find((b) => b.rating_bucket === "UNRATED")?.median_abs, 1)} bp median for unrated.</p>
      </section>
    </>
  );
}
