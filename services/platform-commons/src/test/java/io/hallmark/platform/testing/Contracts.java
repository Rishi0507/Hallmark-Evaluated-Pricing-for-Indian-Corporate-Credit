package io.hallmark.platform.testing;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Contracts {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private Contracts() {}

    public static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contracts");
            if (Files.isDirectory(candidate.resolve("schemas"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("contracts directory not found above " + Path.of("").toAbsolutePath());
    }

    public static List<String> examples(String eventType) {
        Path file = root().resolve("examples").resolve(eventType + ".json");
        try {
            JsonNode array = MAPPER.readTree(Files.readString(file));
            List<String> out = new ArrayList<>();
            array.forEach(node -> out.add(MAPPER.writeValueAsString(node)));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<String> violations(String eventType, String json) {
        Schema schema = SCHEMAS.computeIfAbsent(eventType, Contracts::load);
        List<Error> errors = schema.validate(MAPPER.readTree(json));
        return errors.stream().map(e -> e.getInstanceLocation() + ": " + e.getMessage()).collect(Collectors.toList());
    }

    public static void assertConforms(String eventType, String json) {
        List<String> errors = violations(eventType, json);
        if (!errors.isEmpty()) {
            throw new AssertionError(eventType + " violates its contract: " + errors);
        }
    }

    private static Schema load(String eventType) {
        try (InputStream in = Files.newInputStream(root().resolve("schemas").resolve(eventType + ".json"))) {
            return REGISTRY.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
