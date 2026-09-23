package io.hallmark.recon.persistence;

import io.hallmark.platform.jdbc.Upsert;
import io.hallmark.recon.domain.Inputs.EndOfDay;
import io.hallmark.recon.domain.Inputs.ReportedTrade;
import io.hallmark.recon.domain.Inputs.SettlementRecord;
import io.hallmark.recon.domain.Inputs.SourceRecord;
import io.hallmark.recon.domain.Outcome;
import io.hallmark.recon.domain.TradeSettlementRecon.Activity;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReconRepository {
    private static final double LAKH = 100_000.0;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public ReconRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveReported(ReportedTrade t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", t.isin());
        row.put("trade_date", t.tradeDate());
        row.put("instrument_class", t.instrumentClass() == null ? "OTHER" : t.instrumentClass());
        row.put("total_face_lakh", t.totalTradeValueLakh());
        row.put("wap", t.wap());
        row.put("way", t.way());
        Upsert.row(jdbc, "reported_trade", List.of("isin", "trade_date"), row);
    }

    public void saveLeg(String eventId, SettlementRecord s) {
        jdbc.sql("""
                insert into settlement_leg (event_id, isin, trade_date, settlement_date, instrument_class,
                    counterparty_class, status, nominal_inr, wap)
                values (:id, :isin, :td, :sd, :ic, :cp, :status, :nominal, :wap)""")
                .param("id", eventId)
                .param("isin", s.isin())
                .param("td", s.tradeDate())
                .param("sd", s.settlementDate())
                .param("ic", s.instrumentClass() == null ? "OTHER" : s.instrumentClass())
                .param("cp", s.counterpartyClass())
                .param("status", s.status())
                .param("nominal", s.nominalValueInr())
                .param("wap", s.wap())
                .update();
    }

    public void saveSource(SourceRecord r, String json) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", r.isin());
        row.put("source", r.source());
        row.put("payload", json);
        row.put("updated_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "source_record", List.of("isin", "source"), row);
    }

    public Map<String, SourceRecord> sources(String isin) {
        Map<String, SourceRecord> out = new HashMap<>();
        jdbc.sql("select source, payload from source_record where isin = :isin")
                .param("isin", isin)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .forEach(e -> out.put(e.getKey(), mapper.readValue(e.getValue(), SourceRecord.class)));
        return out;
    }

    public Map<String, SourceRecord> nsdlRecords(Set<String> isins) {
        Map<String, SourceRecord> out = new HashMap<>();
        if (isins.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(isins);
        for (int i = 0; i < list.size(); i += 500) {
            jdbc.sql("select isin, payload from source_record where source = 'NSDL' and isin in (:isins)")
                    .param("isins", list.subList(i, Math.min(list.size(), i + 500)))
                    .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                    .list()
                    .forEach(e -> out.put(e.getKey(), mapper.readValue(e.getValue(), SourceRecord.class)));
        }
        return out;
    }

    public List<ReportedTrade> reportedOn(LocalDate date) {
        return jdbc.sql("""
                select isin, trade_date, instrument_class, total_face_lakh, wap, way
                from reported_trade where trade_date = :d""")
                .param("d", date)
                .query((rs, n) -> new ReportedTrade(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getDouble(4), (Double) rs.getObject(5), (Double) rs.getObject(6)))
                .list();
    }

    public int reportedCount(LocalDate date) {
        return jdbc.sql("select count(*) from reported_trade where trade_date = :d").param("d", date)
                .query(Integer.class).single();
    }

    public int legCount(LocalDate settlementDate) {
        return jdbc.sql("select count(*) from settlement_leg where settlement_date = :d").param("d", settlementDate)
                .query(Integer.class).single();
    }

    private record Key(String isin, LocalDate tradeDate) {}

    public List<Activity> activity(LocalDate fromTradeDate, LocalDate toTradeDate) {
        Map<Key, Object[]> reported = new LinkedHashMap<>();
        jdbc.sql("""
                select isin, trade_date, instrument_class, total_face_lakh, wap
                from reported_trade where trade_date between :from and :to""")
                .param("from", fromTradeDate)
                .param("to", toTradeDate)
                .query((rs, n) -> reported.put(new Key(rs.getString(1), rs.getObject(2, LocalDate.class)),
                        new Object[] {rs.getString(3), rs.getDouble(4), rs.getObject(5)}))
                .list();
        Map<Key, Object[]> legs = new LinkedHashMap<>();
        jdbc.sql("""
                select isin, trade_date, max(instrument_class),
                       sum(case when status = 'SETTLED' then nominal_inr else 0 end),
                       sum(case when status = 'SETTLED' and counterparty_class = 'MANDATORY' then nominal_inr else 0 end),
                       sum(case when status = 'FAILED' then nominal_inr else 0 end),
                       sum(case when status = 'SETTLED' and wap is not null then nominal_inr * wap else 0 end),
                       sum(case when status = 'SETTLED' and wap is not null then nominal_inr else 0 end),
                       max(settlement_date)
                from settlement_leg where trade_date between :from and :to
                group by isin, trade_date""")
                .param("from", fromTradeDate)
                .param("to", toTradeDate)
                .query((rs, n) -> legs.put(new Key(rs.getString(1), rs.getObject(2, LocalDate.class)),
                        new Object[] {rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                            rs.getDouble(7), rs.getDouble(8), rs.getObject(9, LocalDate.class)}))
                .list();
        Set<Key> keys = new LinkedHashSet<>(reported.keySet());
        keys.addAll(legs.keySet());
        List<Activity> out = new ArrayList<>(keys.size());
        for (Key k : keys) {
            Object[] r = reported.get(k);
            Object[] l = legs.get(k);
            double weight = l == null ? 0 : (double) l[5];
            out.add(new Activity(
                    k.isin(),
                    k.tradeDate(),
                    r != null ? (String) r[0] : (String) l[0],
                    r != null ? (Double) r[1] : null,
                    r != null ? (Double) r[2] : null,
                    l != null ? (double) l[1] / LAKH : 0,
                    l != null ? (double) l[2] / LAKH : 0,
                    l != null ? (double) l[3] / LAKH : 0,
                    weight > 0 ? (double) l[4] / weight : null,
                    l != null ? (LocalDate) l[6] : null));
        }
        return out;
    }

    public record BreakRow(
            String breakId,
            String reconType,
            String breakType,
            String isin,
            LocalDate businessDate,
            String status,
            String severity,
            Double amountLakh,
            Map<String, Object> details,
            LocalDate firstSeen,
            LocalDate lastSeen,
            LocalDate resolvedOn,
            String resolution,
            String updatedBy) {}

    public Optional<BreakRow> findBreak(String breakId) {
        return jdbc.sql(BREAK_COLUMNS + " where break_id = :id")
                .param("id", breakId)
                .query((rs, n) -> breakRow(rs))
                .optional();
    }

    private static final String BREAK_COLUMNS = """
            select break_id, recon_type, break_type, isin, business_date, status, severity, amount_lakh, details,
                   first_seen, last_seen, resolved_on, resolution, updated_by
            from recon_break""";

    @SuppressWarnings("unchecked")
    private BreakRow breakRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BreakRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, LocalDate.class),
                rs.getString(6),
                rs.getString(7),
                (Double) rs.getObject(8),
                mapper.readValue(rs.getString(9), Map.class),
                rs.getObject(10, LocalDate.class),
                rs.getObject(11, LocalDate.class),
                rs.getObject(12, LocalDate.class),
                rs.getString(13),
                rs.getString(14));
    }

    public void insertBreak(Outcome o, String status, LocalDate seenOn, String resolution) {
        jdbc.sql("""
                insert into recon_break (break_id, recon_type, break_type, isin, business_date, status, severity,
                    amount_lakh, details, first_seen, last_seen, resolved_on, resolution, updated_by, updated_at)
                values (:id, :rt, :bt, :isin, :bd, :status, :sev, :amt, :details, :seen, :seen, :resolved, :res,
                    'system', :at)""")
                .param("id", o.breakId())
                .param("rt", o.reconType().name())
                .param("bt", o.breakType())
                .param("isin", o.isin())
                .param("bd", o.businessDate())
                .param("status", status)
                .param("sev", o.severity())
                .param("amt", o.amountLakh())
                .param("details", mapper.writeValueAsString(o.details()))
                .param("seen", seenOn)
                .param("resolved", "RESOLVED".equals(status) ? seenOn : null)
                .param("res", resolution)
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    public void refreshBreak(Outcome o, String status, LocalDate seenOn, String resolution, String by) {
        jdbc.sql("""
                update recon_break set break_type = :bt, status = :status, severity = :sev, amount_lakh = :amt,
                    details = :details, last_seen = :seen, resolved_on = :resolved, resolution = :res,
                    updated_by = :by, updated_at = :at
                where break_id = :id""")
                .param("id", o.breakId())
                .param("bt", o.breakType() == null ? "MATCHED" : o.breakType())
                .param("status", status)
                .param("sev", o.severity() == null ? "LOW" : o.severity())
                .param("amt", o.amountLakh())
                .param("details", mapper.writeValueAsString(o.details()))
                .param("seen", seenOn)
                .param("resolved", "RESOLVED".equals(status) ? seenOn : null)
                .param("res", resolution)
                .param("by", by)
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    public void updateStatus(String breakId, String status, String comment, String user, LocalDate on) {
        jdbc.sql("""
                update recon_break set status = :status, resolution = :comment, updated_by = :user,
                    resolved_on = :resolved, updated_at = :at
                where break_id = :id""")
                .param("id", breakId)
                .param("status", status)
                .param("comment", comment)
                .param("user", user)
                .param("resolved", "RESOLVED".equals(status) ? on : null)
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    public List<BreakRow> breaks(
            String status, String reconType, String breakType, LocalDate from, LocalDate to, int limit) {
        StringBuilder sql = new StringBuilder(BREAK_COLUMNS).append(" where business_date between :from and :to");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from);
        p.put("to", to);
        if (status != null) {
            sql.append(" and status = :status");
            p.put("status", status);
        }
        if (reconType != null) {
            sql.append(" and recon_type = :rt");
            p.put("rt", reconType);
        }
        if (breakType != null) {
            sql.append(" and break_type = :bt");
            p.put("bt", breakType);
        }
        sql.append(" order by business_date desc, amount_lakh desc limit :limit");
        p.put("limit", limit);
        return jdbc.sql(sql.toString()).params(p).query((rs, n) -> breakRow(rs)).list();
    }

    public List<Map<String, Object>> breakSummary(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select recon_type, break_type, status, severity, count(*), sum(coalesce(amount_lakh, 0))
                from recon_break where business_date between :from and :to
                group by recon_type, break_type, status, severity
                order by recon_type, break_type, status, severity""")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reconType", rs.getString(1));
                    m.put("breakType", rs.getString(2));
                    m.put("status", rs.getString(3));
                    m.put("severity", rs.getString(4));
                    m.put("count", rs.getLong(5));
                    m.put("amountLakh", Math.round(rs.getDouble(6) * 100) / 100.0);
                    return m;
                })
                .list();
    }

    public List<Map<String, Object>> openBreakAges(LocalDate asOf) {
        return jdbc.sql("""
                select recon_type, business_date, count(*) from recon_break
                where status = 'OPEN' group by recon_type, business_date""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reconType", rs.getString(1));
                    m.put("businessDate", rs.getObject(2, LocalDate.class));
                    m.put("count", rs.getLong(3));
                    return m;
                })
                .list();
    }

    public void saveRun(String reconType, LocalDate businessDate, int[] counts, LocalDate runOn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recon_type", reconType);
        row.put("business_date", businessDate);
        row.put("evaluated", counts[0]);
        row.put("matched", counts[1]);
        row.put("explained", counts[2]);
        row.put("pending", counts[3]);
        row.put("breaks", counts[4]);
        row.put("run_on", runOn);
        Upsert.row(jdbc, "recon_run", List.of("recon_type", "business_date"), row);
    }

    public List<Map<String, Object>> runs(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select recon_type, business_date, evaluated, matched, explained, pending, breaks, run_on
                from recon_run where business_date between :from and :to order by business_date, recon_type""")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reconType", rs.getString(1));
                    m.put("businessDate", rs.getObject(2, LocalDate.class));
                    m.put("evaluated", rs.getInt(3));
                    m.put("matched", rs.getInt(4));
                    m.put("explained", rs.getInt(5));
                    m.put("pending", rs.getInt(6));
                    m.put("breaks", rs.getInt(7));
                    m.put("runOn", rs.getObject(8, LocalDate.class));
                    return m;
                })
                .list();
    }

    public void saveEod(EndOfDay eod) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("business_date", eod.businessDate());
        row.put("expected_reported", eod.expected("reported"));
        row.put("expected_settlements", eod.expected("settlements"));
        row.put("status", "PENDING");
        row.put("received_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "eod_control", List.of("business_date"), row);
    }

    public record PendingEod(LocalDate businessDate, int expectedReported, int expectedSettlements) {}

    public Optional<PendingEod> earliestPendingEod() {
        return jdbc.sql("""
                select business_date, expected_reported, expected_settlements from eod_control
                where status = 'PENDING' order by business_date limit 1""")
                .query((rs, n) -> new PendingEod(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3)))
                .optional();
    }

    public void completeEod(LocalDate date) {
        jdbc.sql("update eod_control set status = 'DONE', completed_at = :at where business_date = :d")
                .param("at", Timestamp.from(Instant.now()))
                .param("d", date)
                .update();
    }
}
