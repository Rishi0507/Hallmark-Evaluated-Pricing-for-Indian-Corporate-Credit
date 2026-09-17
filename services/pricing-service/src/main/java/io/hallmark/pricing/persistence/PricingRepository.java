package io.hallmark.pricing.persistence;

import io.hallmark.pricing.domain.CreditCurveModel;
import io.hallmark.pricing.domain.MarkingEngine.Mark;
import io.hallmark.pricing.domain.MarkingEngine.Prediction;
import io.hallmark.pricing.domain.MarkingEngine.Residual;
import io.hallmark.pricing.domain.Observation;
import io.hallmark.platform.jdbc.Upsert;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PricingRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper mapper;

    public PricingRepository(JdbcClient jdbc, JdbcTemplate template, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.template = template;
        this.mapper = mapper;
    }

    public void saveSecurity(
            String isin, String issuerKey, String issuerName, String sector, boolean eligible, String golden) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", isin);
        row.put("issuer_key", issuerKey);
        row.put("issuer_name", issuerName);
        row.put("sector_group", sector);
        row.put("pricing_eligible", eligible);
        row.put("golden", golden);
        row.put("updated_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "security", List.of("isin"), row);
    }

    public List<String> eligibleSecurityJson() {
        return jdbc.sql("select golden from security where pricing_eligible").query(String.class).list();
    }

    public Optional<String> securityJson(String isin) {
        return jdbc.sql("select golden from security where isin = :isin").param("isin", isin).query(String.class).optional();
    }

    public void saveCurve(LocalDate asOf, String pointsJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("as_of", asOf);
        row.put("points", pointsJson);
        row.put("updated_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "risk_free_curve", List.of("as_of"), row);
    }

    public record StoredCurve(LocalDate asOf, String pointsJson) {}

    public Optional<StoredCurve> latestCurveOnOrBefore(LocalDate date) {
        return jdbc.sql("select as_of, points from risk_free_curve where as_of <= :d order by as_of desc limit 1")
                .param("d", date)
                .query((rs, n) -> new StoredCurve(rs.getObject(1, LocalDate.class), rs.getString(2)))
                .optional();
    }

    public boolean curveExists(LocalDate date) {
        return jdbc.sql("select count(*) from risk_free_curve where as_of = :d").param("d", date)
                .query(Integer.class).single() > 0;
    }

    public void saveRawObservation(String isin, LocalDate tradeDate, double clean, double yield, double faceLakh,
            boolean institutional, List<String> flags) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", isin);
        row.put("trade_date", tradeDate);
        row.put("clean_price", clean);
        row.put("yield_pct", yield);
        row.put("face_lakh", faceLakh);
        row.put("institutional", institutional);
        row.put("clean", flags.isEmpty());
        row.put("flags", flags.isEmpty() ? null : String.join(",", flags));
        Upsert.row(jdbc, "observation", List.of("isin", "trade_date"), row);
    }

    public int observationCount(LocalDate date) {
        return jdbc.sql("select count(*) from observation where trade_date = :d").param("d", date)
                .query(Integer.class).single();
    }

    public record RawObservation(String isin, LocalDate tradeDate, double cleanPrice, double yieldPct,
            double faceLakh, boolean institutional) {}

    public List<RawObservation> cleanObservationsOn(LocalDate date) {
        return jdbc.sql("""
                select isin, trade_date, clean_price, yield_pct, face_lakh, institutional
                from observation where trade_date = :d and clean""")
                .param("d", date)
                .query((rs, n) -> new RawObservation(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getBoolean(6)))
                .list();
    }

    public void saveObservationSpreads(List<Observation> observations) {
        template.batchUpdate("""
                update observation set z_spread_bps = ?, tenor_years = ?, rating_bucket = ?, sector_group = ?,
                    issuer_key = ?, yield_pct = ?, risk_free_yield_pct = ? where isin = ? and trade_date = ?""",
                observations,
                500,
                (ps, o) -> {
                    ps.setDouble(1, o.zSpreadBps());
                    ps.setDouble(2, o.tenorYears());
                    ps.setString(3, o.bucket());
                    ps.setString(4, o.sectorGroup());
                    ps.setString(5, o.issuerKey());
                    ps.setDouble(6, o.yieldPct());
                    ps.setDouble(7, o.riskFreeYieldPct());
                    ps.setString(8, o.isin());
                    ps.setDate(9, Date.valueOf(o.tradeDate()));
                });
    }

    public void saveModelSpreads(LocalDate date, Map<String, Double> modelSpreadByIsin) {
        template.batchUpdate("update observation set model_spread_bps = ? where isin = ? and trade_date = ?",
                List.copyOf(modelSpreadByIsin.entrySet()),
                500,
                (ps, e) -> {
                    ps.setDouble(1, e.getValue());
                    ps.setString(2, e.getKey());
                    ps.setDate(3, Date.valueOf(date));
                });
    }

    public List<Observation> spreadObservations(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select isin, trade_date, clean_price, yield_pct, face_lakh, institutional, z_spread_bps, tenor_years,
                       rating_bucket, sector_group, issuer_key, coalesce(risk_free_yield_pct, 0)
                from observation
                where trade_date between :from and :to and clean and z_spread_bps is not null""")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Observation(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getBoolean(6), rs.getDouble(7),
                        rs.getDouble(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getDouble(12)))
                .list();
    }

    public Map<String, Residual> lastInstitutionalResiduals(LocalDate fromInclusive, LocalDate beforeExclusive) {
        Map<String, Residual> out = new HashMap<>();
        jdbc.sql("""
                select isin, trade_date, z_spread_bps, model_spread_bps, yield_pct, face_lakh,
                       coalesce(risk_free_yield_pct, 0)
                from observation
                where trade_date >= :from and trade_date < :before and institutional and clean
                  and z_spread_bps is not null and model_spread_bps is not null
                order by trade_date""")
                .param("from", fromInclusive)
                .param("before", beforeExclusive)
                .query((rs, n) -> Map.entry(rs.getString(1), new Residual(rs.getObject(2, LocalDate.class),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7))))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public List<String> recentlyObservedIsins(LocalDate from, LocalDate to) {
        return jdbc.sql("select distinct isin from observation where trade_date between :from and :to and clean")
                .param("from", from)
                .param("to", to)
                .query(String.class)
                .list();
    }

    public void replaceMarks(LocalDate date, List<Mark> marks) {
        jdbc.sql("delete from mark where valuation_date = :d").param("d", date).update();
        template.batchUpdate("""
                insert into mark (valuation_date, isin, method, clean_price, dirty_price, accrued, yield_pct,
                    z_spread_bps, g_spread_bps, modified_duration, tenor_years, rating_bucket, sector_group,
                    model_spread_bps, confidence, evidence)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                marks,
                1000,
                (ps, m) -> {
                    ps.setDate(1, Date.valueOf(m.valuationDate()));
                    ps.setString(2, m.isin());
                    ps.setString(3, m.method().name());
                    ps.setDouble(4, m.cleanPrice());
                    ps.setDouble(5, m.dirtyPrice());
                    ps.setDouble(6, m.accrued());
                    ps.setDouble(7, m.yieldPct());
                    ps.setDouble(8, m.zSpreadBps());
                    ps.setDouble(9, m.gSpreadBps());
                    ps.setDouble(10, m.modifiedDuration());
                    ps.setDouble(11, m.tenorYears());
                    ps.setString(12, m.ratingBucket());
                    ps.setString(13, m.sectorGroup());
                    ps.setDouble(14, m.modelSpreadBps());
                    ps.setDouble(15, m.confidence());
                    ps.setString(16, mapper.writeValueAsString(m.evidence()));
                });
    }

    public void replaceCurveFits(LocalDate date, CreditCurveModel model) {
        jdbc.sql("delete from curve_fit where valuation_date = :d").param("d", date).update();
        jdbc.sql("delete from sector_offset where valuation_date = :d").param("d", date).update();
        model.curves().values().forEach(c -> jdbc.sql("""
                        insert into curve_fit (valuation_date, bucket, level_bps, slope_bps, scale_bps, effective_n, source)
                        values (:d, :b, :l, :s, :sc, :n, :src)""")
                .param("d", date)
                .param("b", c.bucket())
                .param("l", c.level())
                .param("s", c.slope())
                .param("sc", c.scaleBps())
                .param("n", c.effectiveN())
                .param("src", c.source())
                .update());
        model.sectorOffsets().forEach((sector, o) -> jdbc.sql("""
                        insert into sector_offset (valuation_date, sector_group, offset_bps, weight, observations)
                        values (:d, :g, :o, :w, :n)""")
                .param("d", date)
                .param("g", sector)
                .param("o", o.bps())
                .param("w", o.weight())
                .param("n", o.observations())
                .update());
    }

    public void replacePredictions(LocalDate date, List<Prediction> predictions) {
        jdbc.sql("delete from backtest where trade_date = :d").param("d", date).update();
        template.batchUpdate("""
                insert into backtest (trade_date, isin, method, rating_bucket, sector_group, predicted_yield_pct,
                    observed_yield_pct, error_bps, last_yield_error_bps, carried_spread_error_bps, confidence)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                predictions,
                500,
                (ps, p) -> {
                    ps.setDate(1, Date.valueOf(p.tradeDate()));
                    ps.setString(2, p.isin());
                    ps.setString(3, p.method().name());
                    ps.setString(4, p.ratingBucket());
                    ps.setString(5, p.sectorGroup());
                    ps.setDouble(6, p.predictedYieldPct());
                    ps.setDouble(7, p.observedYieldPct());
                    ps.setDouble(8, p.errorBps());
                    ps.setObject(9, p.lastYieldErrorBps());
                    ps.setObject(10, p.carriedSpreadErrorBps());
                    ps.setDouble(11, p.confidence());
                });
    }

    public void saveEod(LocalDate date, int expectedObservations) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("business_date", date);
        row.put("expected_observations", expectedObservations);
        row.put("status", "PENDING");
        row.put("received_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "eod_control", List.of("business_date"), row);
    }

    public record PendingEod(LocalDate businessDate, int expectedObservations, Instant receivedAt) {}

    public Optional<PendingEod> earliestPendingEod() {
        return jdbc.sql("""
                select business_date, expected_observations, received_at from eod_control
                where status = 'PENDING' order by business_date limit 1""")
                .query((rs, n) -> new PendingEod(rs.getObject(1, LocalDate.class), rs.getInt(2),
                        rs.getTimestamp(3).toInstant()))
                .optional();
    }

    public void completeEod(LocalDate date, LocalDate curveDate, int marks, int predictions) {
        jdbc.sql("""
                update eod_control set status = 'DONE', completed_at = :at, curve_date = :cd, marks = :m,
                    predictions = :p where business_date = :d""")
                .param("at", Timestamp.from(Instant.now()))
                .param("cd", curveDate)
                .param("m", marks)
                .param("p", predictions)
                .param("d", date)
                .update();
    }

    public void requeueEod(LocalDate date) {
        jdbc.sql("update eod_control set status = 'PENDING' where business_date = :d").param("d", date).update();
    }

    public JdbcClient jdbc() {
        return jdbc;
    }
}
