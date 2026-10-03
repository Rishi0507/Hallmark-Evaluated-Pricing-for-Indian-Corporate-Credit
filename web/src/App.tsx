import { useEffect, useState } from "react";
import Backtest from "./views/Backtest";
import Distribution from "./views/Distribution";
import Market from "./views/Market";
import Overview from "./views/Overview";
import Pricing from "./views/Pricing";
import Reconciliation from "./views/Reconciliation";

export const TABS = [
  { id: "overview", label: "Overview", view: Overview },
  { id: "pricing", label: "Marks and curves", view: Pricing },
  { id: "backtest", label: "Backtest", view: Backtest },
  { id: "recon", label: "Reconciliation", view: Reconciliation },
  { id: "distribution", label: "Distribution", view: Distribution },
  { id: "market", label: "Market", view: Market },
] as const;

type TabId = (typeof TABS)[number]["id"];

function fromHash(): TabId {
  const id = window.location.hash.replace("#", "");
  return (TABS.find((t) => t.id === id)?.id ?? "overview") as TabId;
}

export default function App() {
  const [tab, setTab] = useState<TabId>(fromHash);
  useEffect(() => {
    const onHash = () => setTab(fromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const View = TABS.find((t) => t.id === tab)!.view;
  return (
    <>
      <header className="top">
        <div className="top-inner">
          <div className="brand">
            <h1>Hallmark</h1>
            <p>Evaluated pricing, trade flow and reconciliation for Indian corporate bonds, from public data</p>
          </div>
          <nav className="tabs" role="tablist">
            {TABS.map((t) => (
              <button
                key={t.id}
                role="tab"
                aria-selected={t.id === tab}
                onClick={() => {
                  window.location.hash = t.id;
                  setTab(t.id);
                }}
              >
                {t.label}
              </button>
            ))}
          </nav>
        </div>
      </header>
      <main>
        <View />
      </main>
      <footer>
        Sources: NSE debt segment reports and clearing settlement files, FBIL G-sec, T-bill and FX benchmarks, NSDL
        India Bond Info. Figures are computed from those public files; no values are simulated.
      </footer>
    </>
  );
}
