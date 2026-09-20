package io.hallmark.pricing.api;

import io.hallmark.pricing.domain.RatingBuckets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PricingQueries {
    static final double[] TENORS = {0.5, 1, 2, 3, 5, 7, 10, 15};

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public PricingQueries(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record MarkRow(
            String isin,
            String issuerName,
            LocalDate valuationDate,
            String method,
            double cleanPrice,
            double yieldPct,
            double zSpreadBps,
            Double gSpreadBps,
            double modifiedDuration,
            double tenorYears,
            String ratingBucket,
            String sectorGroup,
            double modelSpreadBps,
            double confidence,
            JsonNode evidence) {}

    private static final String MARK_COLUMNS = """
            select m.isin, s.issuer_name, m.valuation_date, m.method, m.clean_price, m.yield_pct, m.z_spread_bps,
                   m.g_spread_bps, m.modified_duration, m.tenor_years, m.rating_bucket, m.sector_group,
                   m.model_spread_bps, m.confidence, m.evidence
            from mark m left join security s on s.isin = m.isin""";

    public List<MarkRow> marks(LocalDate date, String bucket, String sector, String method, String q, int limit, int offset) {
        StringBuilder sql = new StringBuilder(MARK_COLUMNS).append(" where m.valuation_date = :d");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("d", date);
        if (bucket != null) {
            sql.append(" and m.rating_bucket = :bucket");
            p.put("bucket", bucket);
        }
        if (sector != null) {
            sql.append(" and m.sector_group = :sector");
            p.put("sector", sector);
        }
        if (method != null) {
            sql.append(" and m.method = :method");
            p.put("method", method);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (upper(s.issuer_name) like :q or m.isin like :q)");
            p.put("q", "%" + q.trim().toUpperCase() + "%");
        }
        sql.append(" order by m.z_spread_bps desc limit :limit offset :offset");
        p.put("limit", limit);
        p.put("offset", offset);
        return jdbc.sql(sql.toString()).params(p).query((rs, n) -> markRow(rs)).list();
    }

    public List<MarkRow> history(String isin, LocalDate from, LocalDate to) {
        return jdbc.sql(MARK_COLUMNS + " where m.isin = :isin and m.valuation_date between :from and :to"
                        + " order by m.valuation_date")
                .param("isin", isin)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> markRow(rs))
                .list();
    }

    private MarkRow markRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String evidence = rs.getString(15);
        return new MarkRow(
                rs.getString(1),
                rs.getString(2),
                rs.getObject(3, LocalDate.class),
                rs.getString(4),
                rs.getDouble(5),
                rs.getDouble(6),
                rs.getDouble(7),
                (Double) rs.getObject(8),
                rs.getDouble(9),
                rs.getDouble(10),
                rs.getString(11),
                rs.getString(12),
                rs.getDouble(13),
                rs.getDouble(14),
                evidence == null ? null : mapper.readTree(evidence));
    }

    public Map<String, Object> methodMix(LocalDate date) {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.sql("""
                select method, count(*), avg(confidence) from mark where valuation_date = :d
                group by method order by count(*) desc""")
                .param("d", date)
                .query((rs, n) -> Map.entry(rs.getString(1), Map.of("count", rs.getLong(2), "avgConfidence",
                        Math.round(rs.getDouble(3) * 1000) / 1000.0)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public Map<String, Object> creditCurves(LocalDate date) {
        Map<String, double[]> params = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        jdbc.sql("select bucket, level_bps, slope_bps, source from curve_fit where valuation_date = :d")
                .param("d", date)
                .query((rs, n) -> {
                    params.put(rs.getString(1), new double[] {rs.getDouble(2), rs.getDouble(3)});
                    sources.put(rs.getString(1), rs.getString(4));
                    return null;
                })
                .list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valuationDate", date);
        out.put("tenors", TENORS);
        Map<String, Object> curves = new LinkedHashMap<>();
        for (String bucket : RatingBuckets.ORDER) {
            if (!params.containsKey(bucket)) {
                continue;
            }
            double[] spreads = new double[TENORS.length];
            for (int i = 0; i < TENORS.length; i++) {
                double running = -Double.MAX_VALUE;
                for (String b : RatingBuckets.ORDER.subList(0, RatingBuckets.index(bucket) + 1)) {
                    double[] ab = params.get(b);
                    if (ab != null) {
                        running = Math.max(running, ab[0] + ab[1] * Math.log1p(TENORS[i]));
                    }
                }
                spreads[i] = Math.round(running * 10) / 10.0;
            }
            curves.put(bucket, Map.of("spreadsBps", spreads, "source", sources.get(bucket)));
        }
        out.put("curves", curves);
        Map<String, Object> sectors = new LinkedHashMap<>();
        jdbc.sql("select sector_group, offset_bps, observations from sector_offset where valuation_date = :d"
                        + " order by offset_bps")
                .param("d", date)
                .query((rs, n) -> sectors.put(rs.getString(1),
                        Map.of("offsetBps", Math.round(rs.getDouble(2) * 10) / 10.0, "observations", rs.getInt(3))))
                .list();
        out.put("sectorOffsets", sectors);
        return out;
    }

    public record PredictionRow(
            LocalDate tradeDate, String isin, String method, String ratingBucket, String sectorGroup,
            double predictedYieldPct, double observedYieldPct, double errorBps, Double lastYieldErrorBps,
            Double carriedSpreadErrorBps, double confidence) {}

    public List<PredictionRow> predictions(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select trade_date, isin, method, rating_bucket, sector_group, predicted_yield_pct, observed_yield_pct,
                       error_bps, last_yield_error_bps, carried_spread_error_bps, confidence
                from backtest where trade_date between :from and :to order by trade_date, isin""")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new PredictionRow(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                        (Double) rs.getObject(9), (Double) rs.getObject(10), rs.getDouble(11)))
                .list();
    }

    public record Stats(long n, double maeBps, double medianAbsBps, double p90AbsBps, double biasBps,
            double within10Bps, double within25Bps) {
        static Stats of(double[] errors) {
            if (errors.length == 0) {
                return new Stats(0, 0, 0, 0, 0, 0, 0);
            }
            double[] abs = Arrays.stream(errors).map(Math::abs).sorted().toArray();
            return new Stats(
                    errors.length,
                    round(Arrays.stream(abs).average().orElse(0)),
                    round(quantile(abs, 0.5)),
                    round(quantile(abs, 0.9)),
                    round(Arrays.stream(errors).average().orElse(0)),
                    round(Arrays.stream(abs).filter(a -> a <= 10).count() / (double) abs.length),
                    round(Arrays.stream(abs).filter(a -> a <= 25).count() / (double) abs.length));
        }

        private static double quantile(double[] sorted, double q) {
            double pos = q * (sorted.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
        }

        private static double round(double v) {
            return Math.round(v * 100) / 100.0;
        }
    }

    public Map<String, Object> backtestSummary(LocalDate from, LocalDate to) {
        List<PredictionRow> rows = predictions(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("all", Stats.of(rows.stream().mapToDouble(PredictionRow::errorBps).toArray()));
        out.put("byMethod", group(rows, PredictionRow::method));
        out.put("byRatingBucket", group(rows, PredictionRow::ratingBucket));
        out.put("bySector", group(rows, r -> r.sectorGroup() == null ? "UNKNOWN" : r.sectorGroup()));

        List<PredictionRow> withHistory = rows.stream().filter(r -> r.lastYieldErrorBps() != null).toList();
        Map<String, Object> baselines = new LinkedHashMap<>();
        baselines.put("model", Stats.of(withHistory.stream().mapToDouble(PredictionRow::errorBps).toArray()));
        baselines.put("lastTradedYield", Stats.of(withHistory.stream().mapToDouble(PredictionRow::lastYieldErrorBps).toArray()));
        baselines.put("lastSpreadCarried", Stats.of(withHistory.stream()
                .filter(r -> r.carriedSpreadErrorBps() != null)
                .mapToDouble(PredictionRow::carriedSpreadErrorBps)
                .toArray()));
        out.put("baselinesOnInstrumentsWithPriorTrade", baselines);

        Map<String, Object> calibration = new LinkedHashMap<>();
        double[][] bands = {{0, 0.4}, {0.4, 0.6}, {0.6, 0.8}, {0.8, 1.01}};
        for (double[] band : bands) {
            double[] errs = rows.stream()
                    .filter(r -> r.confidence() >= band[0] && r.confidence() < band[1])
                    .mapToDouble(PredictionRow::errorBps)
                    .toArray();
            calibration.put(String.format("%.1f-%.1f", band[0], Math.min(1.0, band[1])), Stats.of(errs));
        }
        out.put("byConfidence", calibration);
        return out;
    }

    private static Map<String, Stats> group(List<PredictionRow> rows, Function<PredictionRow, String> key) {
        Map<String, List<PredictionRow>> grouped = rows.stream().collect(Collectors.groupingBy(key));
        Map<String, Stats> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(grouped.keySet());
        keys.sort(null);
        keys.forEach(k -> out.put(k, Stats.of(grouped.get(k).stream().mapToDouble(PredictionRow::errorBps).toArray())));
        return out;
    }

    public List<Map<String, Object>> runs(int limit) {
        return jdbc.sql("""
                select business_date, status, expected_observations, curve_date, marks, predictions, completed_at
                from eod_control order by business_date desc limit :limit""")
                .param("limit", limit)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("businessDate", rs.getObject(1, LocalDate.class));
                    m.put("status", rs.getString(2));
                    m.put("expectedObservations", rs.getInt(3));
                    m.put("curveDate", rs.getObject(4, LocalDate.class));
                    m.put("marks", rs.getObject(5));
                    m.put("predictions", rs.getObject(6));
                    var completed = rs.getTimestamp(7);
                    m.put("completedAt", completed == null ? null : completed.toInstant());
                    return m;
                })
                .list();
    }
}
