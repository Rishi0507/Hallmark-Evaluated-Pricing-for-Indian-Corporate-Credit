import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, ChartTooltip, Legend, SERIES, axis, grid, useData } from "../components/ui";
import type { MarketDay } from "../data";
import { dayLabel, fmt, fmtInt, monthLabel } from "../format";

export default function Market() {
  const { data } = useData<MarketDay[]>("market");
  if (!data) return <p className="muted">Loading</p>;
  const tick = (v: string) => monthLabel(v);
  return (
    <section className="grid two">
      <Card title="FBIL G-sec par yields" sub="Annualised par yield at 1, 5 and 10 years, percent.">
        <ResponsiveContainer width="100%" height={260}>
          <LineChart data={data}>
            <CartesianGrid {...grid} />
            <XAxis dataKey="business_date" {...axis} tickFormatter={tick} minTickGap={40} />
            <YAxis {...axis} domain={["auto", "auto"]} width={40} />
            <Tooltip content={<ChartTooltip labelFormatter={(l) => dayLabel(String(l))} valueFormatter={(v) => fmt(v, 2)} />} />
            <Line type="monotone" dataKey="gsec_1y" name="1 year" stroke={SERIES[0]} strokeWidth={2} dot={false} />
            <Line type="monotone" dataKey="gsec_5y" name="5 years" stroke={SERIES[1]} strokeWidth={2} dot={false} />
            <Line type="monotone" dataKey="gsec_10y" name="10 years" stroke={SERIES[2]} strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
        <Legend items={[{ label: "1 year", color: SERIES[0] }, { label: "5 years", color: SERIES[1] }, { label: "10 years", color: SERIES[2] }]} />
      </Card>
      <Card title="USD/INR reference rate" sub="FBIL reference rate, rupees per US dollar.">
        <ResponsiveContainer width="100%" height={260}>
          <LineChart data={data.filter((d) => d.usd_inr != null)}>
            <CartesianGrid {...grid} />
            <XAxis dataKey="business_date" {...axis} tickFormatter={tick} minTickGap={40} />
            <YAxis {...axis} domain={["auto", "auto"]} width={40} />
            <Tooltip content={<ChartTooltip labelFormatter={(l) => dayLabel(String(l))} valueFormatter={(v) => fmt(v, 2)} />} />
            <Line type="monotone" dataKey="usd_inr" name="USD/INR" stroke={SERIES[0]} strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </Card>
      <Card title="Corporate bonds traded per day" sub="Distinct ISINs in the NSE bhavcopy.">
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={data}>
            <CartesianGrid {...grid} />
            <XAxis dataKey="business_date" {...axis} tickFormatter={tick} minTickGap={40} />
            <YAxis {...axis} width={40} />
            <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip labelFormatter={(l) => dayLabel(String(l))} valueFormatter={fmtInt} />} />
            <Bar dataKey="bonds_traded" name="ISINs traded" fill={SERIES[0]} />
          </BarChart>
        </ResponsiveContainer>
      </Card>
      <Card title="Reported face value per day" sub="Rs crore of face value reported on NSE.">
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={data}>
            <CartesianGrid {...grid} />
            <XAxis dataKey="business_date" {...axis} tickFormatter={tick} minTickGap={40} />
            <YAxis {...axis} width={50} tickFormatter={(v: number) => fmtInt(v)} />
            <Tooltip cursor={{ fill: "var(--surface-2)" }} content={<ChartTooltip labelFormatter={(l) => dayLabel(String(l))} valueFormatter={fmtInt} />} />
            <Bar dataKey="reported_face_crore" name="Rs crore" fill={SERIES[0]} />
          </BarChart>
        </ResponsiveContainer>
      </Card>
    </section>
  );
}
