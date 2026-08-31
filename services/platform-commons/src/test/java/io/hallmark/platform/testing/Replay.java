package io.hallmark.platform.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Replay {
    public static final String DAY = "2026-10-14";

    private Replay() {}

    public static List<String> events(String topicWithoutPrefix) {
        return events(DAY, topicWithoutPrefix);
    }

    public static List<String> events(String day, String topicWithoutPrefix) {
        Path file = Contracts.root().resolve("replay").resolve(day).resolve(topicWithoutPrefix + ".jsonl");
        try (var lines = Files.lines(file)) {
            return lines.filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
