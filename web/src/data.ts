export interface MethodRow {
  method: string;
  n: number;
  avg_confidence?: number;
  mae?: number;
  median_abs?: number;
  within_25?: number;
}

export interface ErrorStats {
  n: number;
  mae: number;
  median_abs: number;
  p90: number;
  bias: number;
  within_25: number;
}

export interface ReconRow {
  recon_type: string;
  instrument_class: string;
  result: string;
  break_type: string;
  outcomes: number;
  amount_crore: number | null;
}

export interface Overview {
  asOf: string;
  testFrom: string;
  coverage: {
    reported_rows: number;
    settlement_rows: number;
    first_date: string;
    last_date: string;
    curve_days: number;
    securities: number;
    priceable: number;
    valuation_days: number;
    marks_per_day: number;
  };
  methodMix: MethodRow[];
  backtest: {
    byMethod: MethodRow[];
    model: ErrorStats;
    lastTradedYield: ErrorStats;
    lastSpreadCarried: ErrorStats;
  };
  recon: ReconRow[];
}

export interface MarketDay {
  business_date: string;
  bonds_traded: number;
  reported_face_crore: number;
  gsec_1y: number | null;
  gsec_5y: number | null;
  gsec_10y: number | null;
  usd_inr: number | null;
}

export interface Curves {
  asOf: string;
  credit: { bucket: string; source: string; effectiveN: number; points: { tenor: number; spreadBps: number }[] }[];
  riskFree: { tenor: number; par_yield: number }[];
  history: { valuation_date: string; bucket: string; spread_3y: number }[];
}

export interface Evidence {
  kind: string;
  isin: string;
  date: string;
  weight: number;
  spreadBps: number;
  faceValueLakh: number | null;
}

export interface Mark {
  isin: string;
  issuer_name: string | null;
  method: string;
  clean_price: number;
  yield_pct: number;
  z_spread_bps: number;
  g_spread_bps: number | null;
  modified_duration: number;
  tenor_years: number;
  rating_bucket: string;
  sector_group: string | null;
  denomination_class: string | null;
  confidence: number;
  model_spread_bps: number;
  evidence: string | null;
}

export interface Backtest {
  monthly: {
    month: string;
    method: string;
    predictions: number;
    mae_bps: number;
    median_abs_error_bps: number;
    bias_bps: number;
    last_yield_mae_bps: number | null;
    carried_spread_mae_bps: number | null;
  }[];
  byBucket: { rating_bucket: string; n: number; median_abs: number; mae: number }[];
  calibration: { band: string; n: number; median_abs: number; mae: number }[];
  errorHistogram: { bin: number; n: number }[];
}

export interface Recon {
  summary: ReconRow[];
  daily: { business_date: string; result: string; n: number }[];
  breaks: {
    recon_type: string;
    isin: string;
    issuer_name: string | null;
    business_date: string;
    break_type: string;
    severity: string;
    amount_lakh: number | null;
    details: string;
  }[];
  yieldBreaks: {
    isin: string;
    issuer_name: string | null;
    trade_date: string;
    result: string;
    break_type: string;
    face_lakh: number;
    details: string;
  }[];
}

export interface Distribution {
  monthly: {
    month: string;
    denomination_class: string;
    counterparty_class: string;
    face_crore: number;
    legs: number;
    yield: number | null;
  }[];
  retailByRating: { rating_bucket: string; face_crore: number; legs: number; yield: number | null }[];
  watchlist: {
    issuer_key: string;
    issuer_name: string;
    sector_group: string;
    rating_bucket: string;
    retail_face_crore_90d: number;
    retail_legs_90d: number;
    retail_yield: number | null;
    fails_180d: number;
    downgrades_365d: number;
    negative_rating_signals: number;
  }[];
  ratingActions: { month: string; action_type: string; actions: number; isins: number }[];
}

const cache = new Map<string, Promise<unknown>>();

export function load<T>(name: string): Promise<T> {
  if (!cache.has(name)) {
    const url = `${import.meta.env.BASE_URL}data/${name}.json`;
    cache.set(
      name,
      fetch(url).then((r) => {
        if (!r.ok) {
          throw new Error(`${url}: ${r.status}`);
        }
        return r.json();
      }),
    );
  }
  return cache.get(name) as Promise<T>;
}
