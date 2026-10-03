import { useMemo } from "react";
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, ChartTooltip, type Column, DataTable, Legend, SERIES, axis, grid, useData } from "../components/ui";
import type { Distribution as DistributionData } from "../data";
import { fmt, fmtInt, humanize, monthLabel } from "../format";

type Watch = DistributionData["watchlist"][number];

export default function Distribution() {
  const { data } = useData<DistributionData>("distribution");
  const monthly = useMemo(() => {
    if (!data) return [];
    const byMonth = new Map<string, Record<string, number | string>>();
    data.monthly.forEach((m) => {
      const row = byMonth.get(m.month) ?? { month: m.month, retailFace: 0, retailYieldNum: 0, retailLegs: 0, nonMandatoryRetail: 0 };
      if (m.denomination_class === "RETAIL") {
        row.retailFace = (row.retailFace as number) + m.face_crore;
        row.retailLegs = (row.retailLegs as number) + m.legs;
        row.retailYieldNum = (row.retailYieldNum as number) + (m.yield ?? 0) * m.face_crore;
      }
      byMonth.set(m.month, row);
    });
    return [...byMonth.values()].map((r) => ({
      ...r,
      retailYield: (r.retailFace as number) > 0 ? (r.retailYieldNum as number) / (r.retailFace as number) : null,
    }));
  }, [data]);
  const actions = useMemo(() => {
    if (!data) return [];
    const byMonth = new Map<string, Record<string, number | string>>();
    data.ratingActions.forEach((a) => {
      const row = byMonth.get(a.month) ?? { month: a.month, DOWNGRADE: 0, UPGRADE: 0 };
      row[a.action_type] = a.actions;
      byMonth.set(a.month, row);
    });
    return [...byMonth.values()];
  }, [data]);
  if (!data) return <p className="muted">Loading</p>;

  const watchColumns: Column<Watch>[] = [
    { key: "issuer_name", label: "Issuer", render: (r) => r.issuer_name.slice(0, 36) },
    { key: "sector_group", label: "Sector", render: (r) => humanize(r.sector_group) },
    { key: "rating_bucket", label: "Rating" },
    { key: "retail_face_crore_90d", label: "Retail flow, 90d (Rs cr)", numeric: true, render: (r) => fmt(r.retail_face_crore_90d, 1) },
    { key: "retail_legs_90d", label: "Settlements", numeric: true, render: (r) => fmtInt(r.retail_legs_90d) },
    { key: "retail_yield", label: "Yield %", numeric: true, render: (r) => fmt(r.retail_yield, 2) },
    { key: "downgrades_365d", label: "Downgrades, 1y", numeric: true },
    { key: "negative_rating_signals", label: "Negative outlook or watch", numeric: true },
    { key: "fails_180d", label: "Settlement fails, 180d", numeric: true },
  ];

  return (
    <>
      <section>
        <p className="prose">
          NSE Clearing separates settlements between entities that SEBI requires to clear centrally (mutual funds,
          FPIs, banks and other regulated institutions) and all others. Combined with the face value of each ISIN, this
          shows how much corporate credit reaches individual investors: bonds with a face value of Rs 10,000 or less are
          the denominations sold through brokers and online bond platforms. The views below follow that retail
          distribution channel and the credit quality it carries.
        </p>
      </section>
      <section className="grid two">
        <Card title="Retail-denomination bonds settled per month" sub="Face value of bonds with a face value of Rs 10,000 or less, Rs crore.">
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={monthly}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="month" {...axis} tickFormatter={(v: string) => monthLabel(v)} minTickGap={20} />
              <YAxis {...axis} width={50} tickFormatter={(v: number) => fmtInt(v)} />
              <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip labelFormatter={(l) => monthLabel(String(l))} valueFormatter={(v) => fmtInt(v)} />} />
              <Bar dataKey="retailFace" name="Rs crore" fill={SERIES[0]} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </Card>
        <Card title="Yield paid on retail-denomination flow" sub="Value-weighted settlement yield, percent.">
          <ResponsiveContainer width="100%" height={240}>
            <LineChart data={monthly}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="month" {...axis} tickFormatter={(v: string) => monthLabel(v)} minTickGap={20} />
              <YAxis {...axis} width={40} domain={["auto", "auto"]} />
              <Tooltip content={<ChartTooltip labelFormatter={(l) => monthLabel(String(l))} valueFormatter={(v) => `${fmt(v, 2)}%`} />} />
              <Line dataKey="retailYield" name="Yield" stroke={SERIES[0]} strokeWidth={2} dot={{ r: 4 }} />
            </LineChart>
          </ResponsiveContainer>
        </Card>
        <Card title="Retail-denomination flow by rating, 2026" sub="Rs crore settled; most of it sits in single-A and BBB paper.">
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={data.retailByRating}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="rating_bucket" {...axis} />
              <YAxis {...axis} width={50} tickFormatter={(v: number) => fmtInt(v)} />
              <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip valueFormatter={(v) => fmtInt(v)} />} />
              <Bar dataKey="face_crore" name="Rs crore" fill={SERIES[0]} radius={[4, 4, 0, 0]} barSize={28} />
            </BarChart>
          </ResponsiveContainer>
          <p className="sub">
            Value-weighted yields: {data.retailByRating.slice(0, 6).map((r) => `${r.rating_bucket} ${fmt(r.yield, 2)}%`).join(", ")}.
          </p>
        </Card>
        <Card title="Long-term rating actions on traded bonds" sub="Count of actions by month, NSDL rating history.">
          <ResponsiveContainer width="100%" height={240}>
            <LineChart data={actions}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="month" {...axis} tickFormatter={(v: string) => monthLabel(v)} minTickGap={20} />
              <YAxis {...axis} width={40} />
              <Tooltip content={<ChartTooltip labelFormatter={(l) => monthLabel(String(l))} valueFormatter={fmtInt} />} />
              <Line dataKey="UPGRADE" name="Upgrades" stroke={SERIES[0]} strokeWidth={2} dot={false} />
              <Line dataKey="DOWNGRADE" name="Downgrades" stroke={SERIES[1]} strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
          <Legend items={[{ label: "Upgrades", color: SERIES[0] }, { label: "Downgrades", color: SERIES[1] }]} />
        </Card>
      </section>
      <section>
        <h3 className="section-title">Issuers reaching retail investors: monitoring list</h3>
        <p className="sub muted">
          Issuers with retail-denomination settlements in the last 90 days, ordered by negative rating signals and
          retail volume. Figures are factual counts from public NSE and NSDL data.
        </p>
        <DataTable rows={data.watchlist} columns={watchColumns} rowKey={(r) => r.issuer_key} maxRows={60} />
      </section>
    </>
  );
}
