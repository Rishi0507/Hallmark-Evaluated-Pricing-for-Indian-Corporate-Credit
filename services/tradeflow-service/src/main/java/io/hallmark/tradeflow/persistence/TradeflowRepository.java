package io.hallmark.tradeflow.persistence;

import io.hallmark.platform.jdbc.Upsert;
import io.hallmark.tradeflow.domain.Inputs.EndOfDay;
import io.hallmark.tradeflow.domain.Inputs.ReportedTrade;
import io.hallmark.tradeflow.domain.Inputs.SecurityRef;
import io.hallmark.tradeflow.domain.Inputs.SettlementRecord;
import io.hallmark.tradeflow.domain.LifecycleState;
import io.hallmark.tradeflow.domain.ObservationBuilder.TradeObservation;
import io.hallmark.tradeflow.domain.TradeFlow;
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
public class TradeflowRepository {
    private static final double LAKH = 100_000.0;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public TradeflowRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveSecurity(SecurityRef ref, String goldenJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", ref.isin());
        row.put("issuer_key", ref.issuerKey());
        row.put("sector_group", ref.sectorGroup());
        row.put("denomination_class", ref.denominationClass());
        row.put("pricing_eligible", ref.pricingEligible());
        row.put("golden", goldenJson);
        row.put("updated_at", Timestamp.from(Instant.now()));
        Upsert.row(jdbc, "security_ref", List.of("isin"), row);
    }

    public Optional<SecurityRef> security(String isin) {
        return jdbc.sql("select golden from security_ref where isin = :isin")
                .param("isin", isin)
                .query(String.class)
                .optional()
                .map(json -> mapper.readValue(json, SecurityRef.class));
    }

    public void saveReported(ReportedTrade t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", t.isin());
        row.put("trade_date", t.tradeDate());
        row.put("instrument_class", t.instrumentClass() == null ? "OTHER" : t.instrumentClass());
        row.put("last_price", t.lastPrice());
        row.put("last_yield", t.lastYield());
        row.put("last_trade_value_lakh", t.lastTradeValueLakh());
        row.put("total_face_lakh", t.totalTradeValueLakh());
        row.put("wap", t.wap());
        row.put("way", t.way());
        Upsert.row(jdbc, "reported_trade", List.of("isin", "trade_date"), row);
    }

    public void saveLeg(String eventId, SettlementRecord s) {
        jdbc.sql("""
                insert into settlement_leg (event_id, isin, trade_date, settlement_date, instrument_class, listing,
                    counterparty_class, status, quantity, nominal_inr, wap, way, denomination_class)
                values (:id, :isin, :td, :sd, :ic, :listing, :cp, :status, :qty, :nominal, :wap, :way, :denom)""")
                .param("id", eventId)
                .param("isin", s.isin())
                .param("td", s.tradeDate())
                .param("sd", s.settlementDate())
                .param("ic", s.instrumentClass() == null ? "OTHER" : s.instrumentClass())
                .param("listing", s.listing())
                .param("cp", s.counterpartyClass())
                .param("status", s.status())
                .param("qty", s.quantity())
                .param("nominal", s.nominalValueInr())
                .param("wap", s.wap())
                .param("way", s.way())
                .param("denom", s.denominationClass())
                .update();
    }

    public List<ReportedTrade> reportedOn(LocalDate date) {
        return jdbc.sql("""
                select isin, trade_date, instrument_class, last_price, last_yield, last_trade_value_lakh,
                       total_face_lakh, wap, way
                from reported_trade where trade_date = :d order by isin""")
                .param("d", date)
                .query((rs, n) -> new ReportedTrade(
                        rs.getString(1),
                        rs.getObject(2, LocalDate.class),
                        "NSE",
                        rs.getString(3),
                        (Double) rs.getObject(4),
                        (Double) rs.getObject(5),
                        (Double) rs.getObject(6),
                        rs.getDouble(7),
                        (Double) rs.getObject(8),
                        (Double) rs.getObject(9)))
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

    public int reportedCount(LocalDate date) {
        return jdbc.sql("select count(*) from reported_trade where trade_date = :d")
                .param("d", date)
                .query(Integer.class)
                .single();
    }

    public int legCount(LocalDate settlementDate) {
        return jdbc.sql("select count(*) from settlement_leg where settlement_date = :d")
                .param("d", settlementDate)
                .query(Integer.class)
                .single();
    }

    public void completeEod(LocalDate date) {
        jdbc.sql("update eod_control set status = 'DONE', completed_at = :at where business_date = :d")
                .param("at", Timestamp.from(Instant.now()))
                .param("d", date)
                .update();
    }

    public List<Map<String, Object>> eodStatus(int limit) {
        return jdbc.sql("""
                select business_date, expected_reported, expected_settlements, status, completed_at
                from eod_control order by business_date desc limit :limit""")
                .param("limit", limit)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("businessDate", rs.getObject(1, LocalDate.class));
                    m.put("expectedReported", rs.getInt(2));
                    m.put("expectedSettlements", rs.getInt(3));
                    m.put("status", rs.getString(4));
                    Timestamp completed = rs.getTimestamp(5);
                    m.put("completedAt", completed == null ? null : completed.toInstant());
                    return m;
                })
                .list();
    }

    private record Key(String isin, LocalDate tradeDate) {}

    private record Reported(String instrumentClass, double face, Double wap, Double way) {}

    private record LegTotals(
            String instrumentClass, double settled, double settledMandatory, double failed, Double settledWap,
            LocalDate lastSettlement) {}

    public List<TradeFlow> flows(LocalDate fromTradeDate, LocalDate toTradeDate) {
        Map<Key, Reported> reported = new LinkedHashMap<>();
        jdbc.sql("""
                select isin, trade_date, instrument_class, total_face_lakh, wap, way
                from reported_trade where trade_date between :from and :to""")
                .param("from", fromTradeDate)
                .param("to", toTradeDate)
                .query((rs, n) -> Map.entry(
                        new Key(rs.getString(1), rs.getObject(2, LocalDate.class)),
                        new Reported(rs.getString(3), rs.getDouble(4), (Double) rs.getObject(5),
                                (Double) rs.getObject(6))))
                .list()
                .forEach(e -> reported.put(e.getKey(), e.getValue()));

        Map<Key, LegTotals> legs = new LinkedHashMap<>();
        jdbc.sql("""
                select isin, trade_date, max(instrument_class),
                       sum(case when status = 'SETTLED' then nominal_inr else 0 end),
                       sum(case when status = 'SETTLED' and counterparty_class = 'MANDATORY'
                                then nominal_inr else 0 end),
                       sum(case when status = 'FAILED' then nominal_inr else 0 end),
                       sum(case when status = 'SETTLED' and wap is not null then nominal_inr * wap else 0 end),
                       sum(case when status = 'SETTLED' and wap is not null then nominal_inr else 0 end),
                       max(settlement_date)
                from settlement_leg
                where trade_date between :from and :to
                group by isin, trade_date""")
                .param("from", fromTradeDate)
                .param("to", toTradeDate)
                .query((rs, n) -> {
                    double weight = rs.getDouble(8);
                    return Map.entry(
                            new Key(rs.getString(1), rs.getObject(2, LocalDate.class)),
                            new LegTotals(rs.getString(3), rs.getDouble(4) / LAKH, rs.getDouble(5) / LAKH,
                                    rs.getDouble(6) / LAKH, weight > 0 ? rs.getDouble(7) / weight : null,
                                    rs.getObject(9, LocalDate.class)));
                })
                .list()
                .forEach(e -> legs.put(e.getKey(), e.getValue()));

        Set<Key> keys = new LinkedHashSet<>(reported.keySet());
        keys.addAll(legs.keySet());
        List<TradeFlow> out = new ArrayList<>(keys.size());
        for (Key k : keys) {
            Reported r = reported.get(k);
            LegTotals l = legs.get(k);
            out.add(new TradeFlow(
                    k.isin(),
                    k.tradeDate(),
                    r != null ? r.instrumentClass() : l.instrumentClass(),
                    r != null ? r.face() : null,
                    r != null ? r.wap() : null,
                    r != null ? r.way() : null,
                    l != null ? l.settled() : 0,
                    l != null ? l.settledMandatory() : 0,
                    l != null ? l.failed() : 0,
                    l != null ? l.settledWap() : null,
                    l != null ? l.lastSettlement() : null));
        }
        return out;
    }

    public Map<String, String> lifecycleStates(LocalDate fromTradeDate, LocalDate toTradeDate) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("select isin, trade_date, state from trade_lifecycle where trade_date between :from and :to")
                .param("from", fromTradeDate)
                .param("to", toTradeDate)
                .query((rs, n) -> Map.entry(rs.getString(1) + "|" + rs.getObject(2, LocalDate.class), rs.getString(3)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public void saveLifecycle(TradeFlow f, LifecycleState state, LocalDate evaluatedOn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", f.isin());
        row.put("trade_date", f.tradeDate());
        row.put("instrument_class", f.instrumentClass());
        row.put("state", state.name());
        row.put("reported_face_lakh", f.reportedFaceLakh());
        row.put("settled_face_lakh", f.settledFaceLakh());
        row.put("failed_face_lakh", f.failedFaceLakh());
        row.put("settled_share", f.reportedFaceLakh() == null ? null : f.settledShare());
        row.put("last_settlement_date", f.lastSettlementDate());
        row.put("evaluated_on", evaluatedOn);
        Upsert.row(jdbc, "trade_lifecycle", List.of("isin", "trade_date"), row);
    }

    public void saveObservation(TradeObservation o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("isin", o.isin());
        row.put("trade_date", o.tradeDate());
        row.put("size_class", o.sizeClass());
        row.put("clean", o.clean());
        row.put("payload", mapper.writeValueAsString(o));
        Upsert.row(jdbc, "observation", List.of("isin", "trade_date"), row);
    }

    public record LifecycleRow(
            String isin,
            LocalDate tradeDate,
            String instrumentClass,
            String state,
            Double reportedFaceLakh,
            double settledFaceLakh,
            double failedFaceLakh,
            Double settledShare,
            LocalDate lastSettlementDate) {}

    public List<LifecycleRow> lifecycle(LocalDate from, LocalDate to, String isin, String state, int limit) {
        StringBuilder sql = new StringBuilder("""
                select isin, trade_date, instrument_class, state, reported_face_lakh, settled_face_lakh,
                       failed_face_lakh, settled_share, last_settlement_date
                from trade_lifecycle where trade_date between :from and :to""");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from);
        p.put("to", to);
        if (isin != null) {
            sql.append(" and isin = :isin");
            p.put("isin", isin);
        }
        if (state != null) {
            sql.append(" and state = :state");
            p.put("state", state);
        }
        sql.append(" order by trade_date desc, coalesce(reported_face_lakh, settled_face_lakh) desc limit :limit");
        p.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> new LifecycleRow(
                        rs.getString(1),
                        rs.getObject(2, LocalDate.class),
                        rs.getString(3),
                        rs.getString(4),
                        (Double) rs.getObject(5),
                        rs.getDouble(6),
                        rs.getDouble(7),
                        (Double) rs.getObject(8),
                        rs.getObject(9, LocalDate.class)))
                .list();
    }

    public record StateSummary(LocalDate tradeDate, String state, long count, double faceLakh) {}

    public List<StateSummary> stateSummary(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select trade_date, state, count(*), sum(coalesce(reported_face_lakh, settled_face_lakh))
                from trade_lifecycle where trade_date between :from and :to
                group by trade_date, state order by trade_date, state""")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new StateSummary(
                        rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3), rs.getDouble(4)))
                .list();
    }

    public record ChannelSummary(
            String month, String counterpartyClass, String denominationClass, long legs, double faceCrore) {}

    public List<ChannelSummary> channels(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select substring(cast(settlement_date as varchar), 1, 7) as settle_month,
                       coalesce(counterparty_class, 'UNKNOWN'), coalesce(denomination_class, 'UNKNOWN'),
                       count(*), sum(nominal_inr) / 1e7
                from settlement_leg
                where status = 'SETTLED' and instrument_class = 'BOND' and settlement_date between :from and :to
                group by substring(cast(settlement_date as varchar), 1, 7), counterparty_class, denomination_class
                order by 1, 2, 3""")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new ChannelSummary(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getDouble(5)))
                .list();
    }

    public List<String> observationsOn(LocalDate date) {
        return jdbc.sql("select payload from observation where trade_date = :d order by isin")
                .param("d", date)
                .query(String.class)
                .list();
    }
}
