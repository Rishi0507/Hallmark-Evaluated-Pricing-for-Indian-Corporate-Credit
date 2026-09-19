package io.hallmark.pricing;

import io.hallmark.bondmath.ZeroCurve;
import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.testing.Replay;
import io.hallmark.pricing.domain.MarkingEngine;
import io.hallmark.pricing.domain.Observation;
import io.hallmark.pricing.domain.SecurityView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class RealMarket {
    static final LocalDate DAY1 = LocalDate.of(2026, 10, 13);
    static final LocalDate DAY2 = LocalDate.of(2026, 10, 14);
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final EventReader READER = new EventReader(MAPPER);

    private RealMarket() {}

    static Map<String, ObjectNode> goldenPayloads() {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        for (String json : Replay.events(DAY2.toString(), "refdata.source.v1")) {
            JsonNode p = READER.envelope(json).payload();
            if (!"NSDL".equals(p.get("source").asString())) {
                continue;
            }
            ObjectNode g = ((ObjectNode) p).deepCopy();
            String cin = p.path("cin").asString(null);
            g.put("issuerKey", cin != null ? "CIN:" + cin : "NAME:" + p.path("issuerName").asString(""));
            g.put("sectorGroup", p.path("basicIndustry").asString("UNKNOWN"));
            String basis = p.path("couponBasis").asString("").toLowerCase(Locale.ROOT);
            String desc = " " + p.path("description").asString("") + " ";
            boolean defective = false;
            for (JsonNode w : p.path("scheduleWarnings")) {
                defective |= java.util.Set.of("MISSING_AMOUNT", "ZERO_INTEREST_AMOUNTS", "COUPON_AMOUNT_INCONSISTENT")
                        .contains(w.asString());
            }
            boolean eligible = (basis.contains("fixed") || basis.contains("zero")) && !desc.contains(" BR ")
                    && !defective && !p.get("cashflows").isEmpty();
            g.put("pricingEligible", eligible);
            out.put(p.get("isin").asString(), g);
        }
        return out;
    }

    static Map<String, SecurityView> securities() {
        Map<String, SecurityView> out = new LinkedHashMap<>();
        goldenPayloads().forEach((isin, g) -> {
            SecurityView v = MAPPER.treeToValue(g, SecurityView.class);
            if (v.pricingEligible()) {
                out.put(isin, v);
            }
        });
        return out;
    }

    static List<String> goldenEvents() {
        List<String> out = new ArrayList<>();
        goldenPayloads().forEach((isin, g) ->
                out.add(MAPPER.writeValueAsString(EventEnvelope.of(MAPPER, "GoldenSecurity", "test", isin, null, g))));
        return out;
    }

    static ZeroCurve curve(LocalDate day) {
        JsonNode p = READER.envelope(Replay.events(day.toString(), "market.curve.v1").get(0)).payload();
        JsonNode points = p.get("points");
        double[] t = new double[points.size()];
        double[] y = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            t[i] = points.get(i).get("tenorYears").asDouble();
            y[i] = points.get(i).get("parYield").asDouble();
        }
        return ZeroCurve.fromSemiAnnualParYields(day, t, y);
    }

    static List<Map<String, Object>> observationPayloads(LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String json : Replay.events(day.toString(), "trades.reported.v1")) {
            JsonNode p = READER.envelope(json).payload();
            if (!"BOND".equals(p.path("instrumentClass").asString()) || p.get("wap").isNull() || p.get("way").isNull()) {
                continue;
            }
            double face = p.get("totalTradeValueLakh").asDouble();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("isin", p.get("isin").asString());
            o.put("tradeDate", day.toString());
            o.put("cleanPrice", p.get("wap").asDouble());
            o.put("yieldPct", p.get("way").asDouble());
            o.put("faceValueLakh", face);
            o.put("sizeClass", face >= 25 ? "INSTITUTIONAL" : "RETAIL");
            o.put("flags", List.of());
            out.add(o);
        }
        return out;
    }

    static List<Observation> observations(LocalDate day, Map<String, SecurityView> securities) {
        ZeroCurve curve = curve(day);
        List<Observation> out = new ArrayList<>();
        for (Map<String, Object> o : observationPayloads(day)) {
            SecurityView s = securities.get((String) o.get("isin"));
            if (s == null) {
                continue;
            }
            double yield = (double) o.get("yieldPct");
            double price = (double) o.get("cleanPrice");

            var recomputed = s.bond().at(day).map(sch -> {
                try {
                    return io.hallmark.bondmath.YieldMath.yieldFromClean(sch, price);
                } catch (RuntimeException e) {
                    return Double.NaN;
                }
            });
            if (recomputed.isEmpty() || !(Math.abs(recomputed.get() - yield) * 100 <= 25)) {
                continue;
            }
            MarkingEngine.observe(s, day, price, yield, (double) o.get("faceValueLakh"),
                    "INSTITUTIONAL".equals(o.get("sizeClass")), curve).ifPresent(out::add);
        }
        return out;
    }
}
