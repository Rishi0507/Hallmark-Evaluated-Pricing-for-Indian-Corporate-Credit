package io.hallmark.refdata.persistence;

import io.hallmark.platform.jdbc.Upsert;
import io.hallmark.refdata.domain.GoldenSecurity;
import io.hallmark.refdata.domain.Rating;
import io.hallmark.refdata.domain.RatingView;
import io.hallmark.refdata.domain.SourceRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RefdataRepository {
    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public RefdataRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveSource(SourceRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", record.isin());
        row.put("source", record.source());
        row.put("snapshot_date", record.snapshotDate());
        row.put("payload", mapper.writeValueAsString(record));
        row.put("received_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "source_record", List.of("isin", "source"), row);
    }

    public Map<String, SourceRecord> sources(String isin) {
        Map<String, SourceRecord> out = new LinkedHashMap<>();
        jdbc.sql("select source, payload from source_record where isin = :isin")
                .param("isin", isin)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), mapper.readValue(e.getValue(), SourceRecord.class)));
        return out;
    }

    public Optional<String> contentHash(String isin) {
        return jdbc.sql("select content_hash from golden_security where isin = :isin")
                .param("isin", isin)
                .query(String.class)
                .optional();
    }

    public void saveGolden(GoldenSecurity g, String json, String hash) {
        Optional<Rating> composite = RatingView.compositeCurrent(g.ratings());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", g.isin());
        row.put("issuer_key", g.issuerKey());
        row.put("issuer_name", g.issuerName());
        row.put("sector_group", g.sectorGroup());
        row.put("denomination_class", g.denominationClass());
        row.put("maturity_date", g.maturityDate());
        row.put("coupon_rate", g.couponRate());
        row.put("composite_notch", composite.map(Rating::notch).orElse(null));
        row.put("composite_symbol", composite.map(Rating::symbol).orElse(null));
        row.put("rating_bucket", RatingView.bucket(composite.map(Rating::notch).orElse(null)));
        row.put("rating_dispersion", RatingView.dispersion(g.ratings()));
        row.put("pricing_eligible", g.pricingEligible());
        row.put("ineligible_reason", g.ineligibleReason());
        row.put("conflict_count", g.conflicts().size());
        row.put("content_hash", hash);
        row.put("golden", json);
        row.put("updated_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "golden_security", List.of("isin"), row);

        jdbc.sql("delete from rating_history where isin = :isin").param("isin", g.isin()).update();
        for (Rating r : g.ratings()) {
            jdbc.sql("""
                    insert into rating_history (isin, agency, raw_rating, symbol, notch, outlook, action,
                        effective_date, rating_date, watch, is_current, withdrawn)
                    values (:isin, :agency, :raw, :symbol, :notch, :outlook, :action, :effective, :ratingDate,
                        :watch, :current, :withdrawn)""")
                    .param("isin", g.isin())
                    .param("agency", r.agency())
                    .param("raw", r.rawRating())
                    .param("symbol", r.symbol())
                    .param("notch", r.notch())
                    .param("outlook", r.outlook())
                    .param("action", r.action())
                    .param("effective", r.effectiveDate())
                    .param("ratingDate", r.ratingDate())
                    .param("watch", r.watch())
                    .param("current", r.isCurrent())
                    .param("withdrawn", r.withdrawn())
                    .update();
        }
    }

    public Optional<GoldenSecurity> golden(String isin) {
        return jdbc.sql("select golden from golden_security where isin = :isin")
                .param("isin", isin)
                .query(String.class)
                .optional()
                .map(json -> mapper.readValue(json, GoldenSecurity.class));
    }

    public record SecuritySummary(
            String isin,
            String issuerName,
            String issuerKey,
            String sectorGroup,
            String denominationClass,
            LocalDate maturityDate,
            Double couponRate,
            String compositeSymbol,
            String ratingBucket,
            int ratingDispersion,
            boolean pricingEligible,
            String ineligibleReason,
            int conflictCount) {}

    public List<SecuritySummary> search(
            String q, String issuerKey, String sectorGroup, String bucket, Boolean eligible, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                select isin, issuer_name, issuer_key, sector_group, denomination_class, maturity_date, coupon_rate,
                       composite_symbol, rating_bucket, rating_dispersion, pricing_eligible, ineligible_reason,
                       conflict_count
                from golden_security where 1 = 1""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            sql.append(" and (upper(issuer_name) like :q or isin like :q)");
            params.put("q", "%" + q.trim().toUpperCase() + "%");
        }
        if (issuerKey != null) {
            sql.append(" and issuer_key = :issuerKey");
            params.put("issuerKey", issuerKey);
        }
        if (sectorGroup != null) {
            sql.append(" and sector_group = :sector");
            params.put("sector", sectorGroup);
        }
        if (bucket != null) {
            sql.append(" and rating_bucket = :bucket");
            params.put("bucket", bucket);
        }
        if (eligible != null) {
            sql.append(" and pricing_eligible = :eligible");
            params.put("eligible", eligible);
        }
        sql.append(" order by issuer_name, maturity_date, isin limit :size offset :offset");
        params.put("size", size);
        params.put("offset", page * size);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new SecuritySummary(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getObject(6, LocalDate.class),
                        (Double) rs.getObject(7),
                        rs.getString(8),
                        rs.getString(9),
                        rs.getInt(10),
                        rs.getBoolean(11),
                        rs.getString(12),
                        rs.getInt(13)))
                .list();
    }

    public record RatingAction(
            String isin, String issuerName, String agency, String symbol, String outlook, String action,
            LocalDate effectiveDate, String watch) {}

    public List<RatingAction> ratingActions(LocalDate from, LocalDate to, String action) {
        String sql = """
                select r.isin, g.issuer_name, r.agency, r.symbol, r.outlook, r.action, r.effective_date, r.watch
                from rating_history r join golden_security g on g.isin = r.isin
                where r.effective_date between :from and :to
                """ + (action == null ? "" : " and upper(r.action) like :action") + """
                 order by r.effective_date desc, g.issuer_name""";
        var spec = jdbc.sql(sql).param("from", from).param("to", to);
        if (action != null) {
            spec = spec.param("action", "%" + action.toUpperCase() + "%");
        }
        return spec.query((rs, n) -> new RatingAction(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getObject(7, LocalDate.class),
                        rs.getString(8)))
                .list();
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("securities", jdbc.sql("select count(*) from golden_security").query(Long.class).single());
        out.put("pricingEligible", jdbc.sql("select count(*) from golden_security where pricing_eligible")
                .query(Long.class)
                .single());
        out.put("withConflicts", jdbc.sql("select count(*) from golden_security where conflict_count > 0")
                .query(Long.class)
                .single());
        out.put("bySector", counts("sector_group"));
        out.put("byRatingBucket", counts("rating_bucket"));
        out.put("byDenomination", counts("denomination_class"));
        out.put("ineligibleReasons", counts("ineligible_reason"));
        return out;
    }

    private Map<String, Long> counts(String column) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select coalesce(" + column + ", 'NONE'), count(*) from golden_security group by " + column
                        + " order by count(*) desc")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public record ConflictRow(String isin, String issuerName, GoldenSecurity.Conflict conflict) {}

    public List<ConflictRow> conflicts(int limit) {
        List<ConflictRow> out = new ArrayList<>();
        jdbc.sql("select golden from golden_security where conflict_count > 0 order by isin limit :limit")
                .param("limit", limit)
                .query(String.class)
                .list()
                .forEach(json -> {
                    GoldenSecurity g = mapper.readValue(json, GoldenSecurity.class);
                    g.conflicts().forEach(c -> out.add(new ConflictRow(g.isin(), g.issuerName(), c)));
                });
        return out;
    }
}
