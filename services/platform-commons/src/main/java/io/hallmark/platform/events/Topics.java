package io.hallmark.platform.events;

public final class Topics {
    public static final String PREFIX = "hallmark.";

    public static final String REFDATA_SOURCE = PREFIX + "refdata.source.v1";
    public static final String MARKET_CURVE = PREFIX + "market.curve.v1";
    public static final String TRADES_REPORTED = PREFIX + "trades.reported.v1";
    public static final String TRADES_SETTLEMENT = PREFIX + "trades.settlement.v1";
    public static final String CONTROL_EOD = PREFIX + "control.eod.v1";

    public static final String REFDATA_SECURITY = PREFIX + "refdata.security.v1";
    public static final String TRADES_OBSERVATION = PREFIX + "trades.observation.v1";
    public static final String TRADES_LIFECYCLE = PREFIX + "trades.lifecycle.v1";
    public static final String PRICING_MARKS = PREFIX + "pricing.marks.v1";
    public static final String CONTROL_TRADEFLOW_EOD = PREFIX + "control.tradeflow-eod.v1";
    public static final String CONTROL_PRICING_EOD = PREFIX + "control.pricing-eod.v1";
    public static final String RECON_BREAKS = PREFIX + "recon.breaks.v1";

    public static final String DLQ_SUFFIX = ".dlq";

    private Topics() {}
}
