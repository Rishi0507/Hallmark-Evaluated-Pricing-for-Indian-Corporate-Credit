import { type ReactNode, useEffect, useMemo, useState } from "react";
import { load } from "../data";

export function useData<T>(name: string): { data: T | null; error: string | null } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    load<T>(name)
      .then((d) => live && setData(d))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [name]);
  return { data, error };
}

export function Kpi({ label, value, note }: { label: string; value: ReactNode; note?: ReactNode }) {
  return (
    <div className="card kpi">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {note ? <div className="note">{note}</div> : null}
    </div>
  );
}

export function Card({ title, sub, children }: { title: string; sub?: ReactNode; children: ReactNode }) {
  return (
    <div className="card">
      <h2>{title}</h2>
      {sub ? <p className="sub">{sub}</p> : null}
      {children}
    </div>
  );
}

export function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div className="legend" aria-label="Legend">
      {items.map((i) => (
        <span key={i.label} style={{ ["--swatch" as string]: i.color }}>
          {i.label}
        </span>
      ))}
    </div>
  );
}

interface TooltipEntry {
  name?: string | number;
  value?: number | string | (number | string)[];
  color?: string;
}

export function ChartTooltip({
  active,
  payload,
  label,
  labelFormatter,
  valueFormatter,
}: {
  active?: boolean;
  payload?: readonly TooltipEntry[];
  label?: string | number;
  labelFormatter?: (l: string | number) => string;
  valueFormatter?: (v: number) => string;
}) {
  if (!active || !payload || payload.length === 0) {
    return null;
  }
  return (
    <div className="chart-tooltip">
      <div className="t">{label != null && labelFormatter ? labelFormatter(label) : label}</div>
      {payload.map((p) => (
        <div key={String(p.name)} className="num">
          <span style={{ color: p.color }}>&#9632;</span> {p.name}:{" "}
          {typeof p.value === "number" && valueFormatter ? valueFormatter(p.value) : String(p.value)}
        </div>
      ))}
    </div>
  );
}

export interface Column<T> {
  key: keyof T & string;
  label: string;
  render?: (row: T) => ReactNode;
  numeric?: boolean;
  sortValue?: (row: T) => number | string;
}

export function DataTable<T>({
  rows,
  columns,
  rowKey,
  onSelect,
  selected,
  maxRows = 200,
  initialSort,
}: {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  onSelect?: (row: T) => void;
  selected?: string | null;
  maxRows?: number;
  initialSort?: { key: keyof T & string; desc: boolean };
}) {
  const [sort, setSort] = useState(initialSort ?? null);
  const sorted = useMemo(() => {
    if (!sort) {
      return rows;
    }
    const col = columns.find((c) => c.key === sort.key);
    const value = (r: T) => (col?.sortValue ? col.sortValue(r) : (r[sort.key] as unknown as number | string));
    return [...rows].sort((a, b) => {
      const va = value(a);
      const vb = value(b);
      if (va == null) return 1;
      if (vb == null) return -1;
      const cmp = va < vb ? -1 : va > vb ? 1 : 0;
      return sort.desc ? -cmp : cmp;
    });
  }, [rows, columns, sort]);
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                className={c.numeric ? "right" : undefined}
                onClick={() => setSort({ key: c.key, desc: sort?.key === c.key ? !sort.desc : true })}
                aria-sort={sort?.key === c.key ? (sort.desc ? "descending" : "ascending") : "none"}
              >
                {c.label}
                {sort?.key === c.key ? (sort.desc ? " ↓" : " ↑") : ""}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sorted.slice(0, maxRows).map((r) => {
            const k = rowKey(r);
            return (
              <tr
                key={k}
                className={selected === k ? "selected" : undefined}
                onClick={onSelect ? () => onSelect(r) : undefined}
                style={onSelect ? { cursor: "pointer" } : undefined}
              >
                {columns.map((c) => (
                  <td key={c.key} className={c.numeric ? "right num" : undefined}>
                    {c.render ? c.render(r) : String(r[c.key] ?? "-")}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export const axis = {
  stroke: "var(--text-muted)",
  tick: { fill: "var(--text-secondary)", fontSize: 11 },
  tickLine: false,
  axisLine: { stroke: "var(--border)" },
};

export const grid = { stroke: "var(--grid)", strokeDasharray: "0", vertical: false };

export const SERIES = ["var(--series-1)", "var(--series-2)", "var(--series-3)", "var(--series-4)", "var(--series-5)"];
