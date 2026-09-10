package io.hallmark.tradeflow;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.testing.Replay;
import io.hallmark.tradeflow.domain.Inputs.SecurityRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class RealDay {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final EventReader READER = new EventReader(MAPPER);
    static final java.util.Set<String> CRITICAL_WARNINGS =
            java.util.Set.of("MISSING_AMOUNT", "ZERO_INTEREST_AMOUNTS", "COUPON_AMOUNT_INCONSISTENT");

    private RealDay() {}

    static Map<String, ObjectNode> securityPayloads() {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        for (String json : Replay.events("refdata.source.v1")) {
            JsonNode p = READER.envelope(json).payload();
            if (!"NSDL".equals(p.get("source").asString())) {
                continue;
            }
            ObjectNode g = MAPPER.createObjectNode();
            g.put("isin", p.get("isin").asString());
            g.set("faceValue", p.get("faceValue"));
            g.set("firstCallDate", p.get("firstCallDate"));
            String basis = p.path("couponBasis").asString("");
            boolean fixed = basis.toLowerCase().contains("fixed") || basis.toLowerCase().contains("zero");
            boolean defective = false;
            for (JsonNode w : p.path("scheduleWarnings")) {
                defective |= CRITICAL_WARNINGS.contains(w.asString());
            }
            g.put("pricingEligible", fixed && !defective && !p.get("cashflows").isEmpty());
            g.set("cashflows", p.get("cashflows"));
            out.put(g.get("isin").asString(), g);
        }
        return out;
    }

    static Map<String, SecurityRef> securities() {
        Map<String, SecurityRef> out = new LinkedHashMap<>();
        securityPayloads().forEach((isin, node) -> out.put(isin, MAPPER.treeToValue(node, SecurityRef.class)));
        return out;
    }

    static List<String> goldenEvents() {
        List<String> out = new ArrayList<>();
        securityPayloads().forEach((isin, node) -> out.add(MAPPER.writeValueAsString(
                EventEnvelope.of(MAPPER, "GoldenSecurity", "test", isin, null, node))));
        return out;
    }
}
