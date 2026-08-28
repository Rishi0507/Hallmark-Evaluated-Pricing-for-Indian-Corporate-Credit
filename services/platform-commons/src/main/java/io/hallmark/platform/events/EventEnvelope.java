package io.hallmark.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String eventId,
        String eventType,
        int schemaVersion,
        String source,
        String key,
        LocalDate businessDate,
        Instant producedAt,
        JsonNode payload) {
    public static EventEnvelope of(
            JsonMapper mapper, String eventType, String source, String key, LocalDate businessDate, Object payload) {
        JsonNode node = mapper.valueToTree(payload);
        Object canonical = canonical(mapper.treeToValue(node, Object.class));
        String content = eventType + '|' + key + '|' + businessDate + '|' + mapper.writeValueAsString(canonical);
        return new EventEnvelope(sha256(content), eventType, 1, source, key, businessDate, Instant.now(), node);
    }

    public <T> T payloadAs(JsonMapper mapper, Class<T> type) {
        return mapper.treeToValue(payload, type);
    }

    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EventEnvelope::canonical).toList();
        }
        return value;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
