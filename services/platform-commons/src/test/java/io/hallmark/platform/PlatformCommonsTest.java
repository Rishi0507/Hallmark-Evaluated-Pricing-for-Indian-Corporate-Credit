package io.hallmark.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.idempotency.ProcessedEvents;
import io.hallmark.platform.jdbc.Upsert;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class PlatformCommonsTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private JdbcClient jdbc;

    record Payload(String isin, LocalDate tradeDate, double wap) {}

    @BeforeEach
    void db() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = JdbcClient.create(ds);
        jdbc.sql("create table processed_event (event_id varchar(64) primary key, processed_at timestamp)")
                .update();
        jdbc.sql("create table t (k1 varchar(12), k2 date, v double precision, primary key (k1, k2))")
                .update();
    }

    @Test
    void eventIdIsDeterministicAndIndependentOfFieldOrder() {
        var p = new Payload("INE423A07484", LocalDate.of(2026, 10, 14), 100.14);
        var a = EventEnvelope.of(mapper, "ReportedTrade", "test", p.isin(), p.tradeDate(), p);
        var b = EventEnvelope.of(mapper, "ReportedTrade", "other-source", p.isin(), p.tradeDate(), p);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("wap", 100.14);
        reordered.put("tradeDate", "2026-10-14");
        reordered.put("isin", "INE423A07484");
        var c = EventEnvelope.of(mapper, "ReportedTrade", "test", p.isin(), p.tradeDate(), reordered);
        assertThat(a.eventId()).hasSize(64).isEqualTo(b.eventId()).isEqualTo(c.eventId());
        var d = EventEnvelope.of(mapper, "ReportedTrade", "test", p.isin(), p.tradeDate(), new Payload(p.isin(), p.tradeDate(), 100.15));
        assertThat(d.eventId()).isNotEqualTo(a.eventId());
    }

    @Test
    void readerRoundTripsAndRejectsMalformedInput() {
        var reader = new EventReader(mapper);
        var p = new Payload("INE423A07484", LocalDate.of(2026, 10, 14), 100.14);
        String json = mapper.writeValueAsString(EventEnvelope.of(mapper, "ReportedTrade", "t", p.isin(), p.tradeDate(), p));
        var env = reader.envelope(json);
        assertThat(reader.payload(env, Payload.class)).isEqualTo(p);
        assertThatThrownBy(() -> reader.envelope("{not json"))
                .isInstanceOf(EventReader.MalformedEventException.class);
        assertThatThrownBy(() -> reader.envelope("{\"eventType\":\"X\"}"))
                .isInstanceOf(EventReader.MalformedEventException.class);
    }

    @Test
    void processedEventsSkipsDuplicates() {
        var guard = new ProcessedEvents(jdbc);
        assertThat(guard.markIfNew("abc")).isTrue();
        assertThat(guard.markIfNew("abc")).isFalse();
        assertThat(guard.markIfNew("def")).isTrue();
        assertThat(guard.count()).isEqualTo(2);
    }

    @Test
    void upsertInsertsThenUpdates() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("k1", "INE423A07484");
        row.put("k2", LocalDate.of(2026, 10, 14));
        row.put("v", 1.0);
        Upsert.row(jdbc, "t", List.of("k1", "k2"), row);
        row.put("v", 2.0);
        Upsert.row(jdbc, "t", List.of("k1", "k2"), row);
        assertThat(jdbc.sql("select count(*) from t").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select v from t").query(Double.class).single()).isEqualTo(2.0);
    }
}
