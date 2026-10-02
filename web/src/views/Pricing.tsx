import { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, ChartTooltip, type Column, DataTable, Legend, SERIES, axis, grid, useData } from "../components/ui";
import type { Curves, Evidence, Mark } from "../data";
import { dayLabel, fmt, fmtBps, humanize, monthLabel } from "../format";

const CURVE_BUCKETS = ["AAA", "AA+", "AA", "A", "BBB"];
const HISTORY_BUCKETS = ["AAA", "AA", "A", "BBB"];

function EvidencePanel({ mark }: { mark: Mark }) {
  const items: Evidence[] = mark.evidence ? JSON.parse(mark.evidence) : [];
  return (
    <Card title={mark.issuer_name ?? mark.isin} sub={`${mark.isin} | ${humanize(mark.method)} | confidence ${fmt(mark.confidence, 2)}`}>
      <div className="grid kpis" style={{ marginBottom: 12 }}>
        <div>
          <div className="muted">Clean price</div>
          <div className="num">{fmt(mark.clean_price, 4)}</div>
        </div>
        <div>
          <div className="muted">Yield</div>
          <div className="num">{fmt(mark.yield_pct, 3)}%</div>
        </div>
        <div>
          <div className="muted">Z-spread</div>
          <div className="num">{fmtBps(mark.z_spread_bps)}</div>
        </div>
        <div>
          <div className="muted">Model spread</div>
          <div className="num">{fmtBps(mark.model_spread_bps)}</div>
        </div>
        <div>
          <div className="muted">Modified duration</div>
          <div className="num">{fmt(mark.modified_duration, 2)}</div>
        </div>
      </div>
      <div className="evidence">
        {items.length === 0 ? <p className="muted">Evidence is kept for the latest valuation days only.</p> : null}
        {items.map((e, i) => (
          <div className="evidence-item" key={`${e.kind}-${e.isin}-${i}`}>
            <div>
              <span className="pill">{humanize(e.kind)}</span> <span className="num">{e.isin}</span>{" "}
              <span className="muted">{dayLabel(e.date)}</span>
            </div>
            <div className="num muted">
              spread {fmtBps(e.spreadBps)}
              {e.faceValueLakh != null ? `, face Rs ${fmt(e.faceValueLakh, 1)} lakh` : ""}
              {e.kind.startsWith("BUCKET") ? `, effective trades ${fmt(e.weight, 1)}` : ""}
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

export default function Pricing() {
  const { data: curves } = useData<Curves>("curves");
  const { data: marks } = useData<Mark[]>("marks");
  const [query, setQuery] = useState("");
  const [bucket, setBucket] = useState("");
  const [method, setMethod] = useState("");
  const [selected, setSelected] = useState<Mark | null>(null);

  const filtered = useMemo(() => {
    if (!marks) return [];
    const q = query.trim().toUpperCase();
    return marks.filter(
      (m) =>
        (!q || m.isin.includes(q) || (m.issuer_name ?? "").toUpperCase().includes(q)) &&
        (!bucket || m.rating_bucket === bucket) &&
        (!method || m.method === method),
    );
  }, [marks, query, bucket, method]);

  const curveData = useMemo(() => {
    if (!curves) return [];
    const tenors = curves.credit[0]?.points.map((p) => p.tenor) ?? [];
    return tenors.map((t, i) => {
      const row: Record<string, number> = { tenor: t };
      curves.credit.forEach((c) => {
        row[c.bucket] = c.points[i].spreadBps;
      });
      return row;
    });
  }, [curves]);

  const historyData = useMemo(() => {
    if (!curves) return [];
    const byDate = new Map<string, Record<string, number | string>>();
    curves.history.forEach((h) => {
      const row = byDate.get(h.valuation_date) ?? { date: h.valuation_date };
      row[h.bucket] = Math.round(h.spread_3y * 10) / 10;
      byDate.set(h.valuation_date, row);
    });
    return [...byDate.values()];
  }, [curves]);

  const columns: Column<Mark>[] = [
    { key: "isin", label: "ISIN", render: (m) => <span className="num">{m.isin}</span> },
    { key: "issuer_name", label: "Issuer", render: (m) => (m.issuer_name ?? "-").slice(0, 34) },
    { key: "rating_bucket", label: "Rating" },
    { key: "method", label: "Method", render: (m) => humanize(m.method) },
    { key: "clean_price", label: "Price", numeric: true, render: (m) => fmt(m.clean_price, 3) },
    { key: "yield_pct", label: "Yield %", numeric: true, render: (m) => fmt(m.yield_pct, 3) },
    { key: "z_spread_bps", label: "Z-spread", numeric: true, render: (m) => fmt(m.z_spread_bps, 0) },
    { key: "tenor_years", label: "Tenor", numeric: true, render: (m) => fmt(m.tenor_years, 1) },
    { key: "confidence", label: "Confidence", numeric: true, render: (m) => fmt(m.confidence, 2) },
  ];

  return (
    <>
      <section className="grid two">
        <Card
          title="Credit curves by rating"
          sub={curves ? `Z-spread over the FBIL zero curve, basis points, ${dayLabel(curves.asOf)}. Monotone in rating at every tenor.` : ""}
        >
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={curveData}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="tenor" {...axis} type="number" domain={[0, 15]} unit="y" />
              <YAxis {...axis} width={44} />
              <Tooltip content={<ChartTooltip labelFormatter={(l) => `${l} years`} valueFormatter={(v) => fmtBps(v, 0)} />} />
              {CURVE_BUCKETS.map((b, i) => (
                <Line key={b} type="monotone" dataKey={b} name={b} stroke={SERIES[i]} strokeWidth={2} dot={false} />
              ))}
            </LineChart>
          </ResponsiveContainer>
          <Legend items={CURVE_BUCKETS.map((b, i) => ({ label: b, color: SERIES[i] }))} />
        </Card>
        <Card title="Three-year spread by rating over time" sub="Fitted bucket curve evaluated at three years, basis points.">
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={historyData}>
              <CartesianGrid {...grid} />
              <XAxis dataKey="date" {...axis} tickFormatter={(v: string) => monthLabel(v)} minTickGap={40} />
              <YAxis {...axis} width={44} />
              <Tooltip content={<ChartTooltip labelFormatter={(l) => dayLabel(String(l))} valueFormatter={(v) => fmtBps(v, 0)} />} />
              {HISTORY_BUCKETS.map((b, i) => (
                <Line key={b} type="monotone" dataKey={b} name={b} stroke={SERIES[i]} strokeWidth={2} dot={false} />
              ))}
            </LineChart>
          </ResponsiveContainer>
          <Legend items={HISTORY_BUCKETS.map((b, i) => ({ label: b, color: SERIES[i] }))} />
        </Card>
      </section>
      <section>
        <h3 className="section-title">Marks</h3>
        <div className="toolbar">
          <input placeholder="Search issuer or ISIN" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search" />
          <select value={bucket} onChange={(e) => setBucket(e.target.value)} aria-label="Rating bucket">
            <option value="">All ratings</option>
            {["AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB", "SUB_IG", "UNRATED"].map((b) => (
              <option key={b}>{b}</option>
            ))}
          </select>
          <select value={method} onChange={(e) => setMethod(e.target.value)} aria-label="Method">
            <option value="">All methods</option>
            {["TRADED", "TRADED_RETAIL", "CARRIED", "ISSUER_CURVE", "MATRIX"].map((m) => (
              <option key={m} value={m}>
                {humanize(m)}
              </option>
            ))}
          </select>
          <span className="count">{filtered.length} marks</span>
        </div>
        <div className="grid two">
          <DataTable
            rows={filtered}
            columns={columns}
            rowKey={(m) => m.isin}
            onSelect={setSelected}
            selected={selected?.isin ?? null}
            initialSort={{ key: "z_spread_bps", desc: true }}
          />
          {selected ? <EvidencePanel mark={selected} /> : <Card title="Evidence">Select a mark to see the trades and curves behind it.</Card>}
        </div>
      </section>
    </>
  );
}
