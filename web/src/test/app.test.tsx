import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import { DataTable } from "../components/ui";
import { fmtBps, fmtCrore, fmtPct, humanize, monthLabel } from "../format";

describe("format", () => {
  it("formats market quantities", () => {
    expect(fmtBps(7.413)).toBe("7.4 bp");
    expect(fmtPct(0.946)).toBe("94.6%");
    expect(fmtCrore(6012.29)).toBe("Rs 6,012 cr");
    expect(fmtCrore(1_330_784)).toBe("Rs 13.31 lakh cr");
    expect(fmtBps(null)).toBe("-");
    expect(humanize("SETTLEMENT_FAILED")).toBe("Settlement failed");
    expect(humanize("PSU_FINANCIAL")).toBe("PSU financial");
    expect(monthLabel("2026-07-01")).toBe("Jul 26");
  });
});

describe("DataTable", () => {
  it("sorts by a column", () => {
    const rows = [
      { isin: "INE053F08403", n: 2 },
      { isin: "INE296A07UB9", n: 9 },
    ];
    render(<DataTable rows={rows} columns={[{ key: "isin", label: "ISIN" }, { key: "n", label: "N", numeric: true }]} rowKey={(r) => r.isin} initialSort={{ key: "n", desc: true }} />);
    const cells = screen.getAllByRole("cell");
    expect(cells[0]).toHaveTextContent("INE296A07UB9");
  });
});

describe("App", () => {
  afterEach(() => vi.restoreAllMocks());

  it("renders the shell and every tab", () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(() => new Promise(() => undefined));
    render(<App />);
    expect(screen.getByRole("heading", { name: "Hallmark" })).toBeInTheDocument();
    expect(screen.getAllByRole("tab")).toHaveLength(6);
    expect(screen.getByText("Loading")).toBeInTheDocument();
  });
});
