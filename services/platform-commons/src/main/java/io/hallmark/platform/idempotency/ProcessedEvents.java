package io.hallmark.platform.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ProcessedEvents {
    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markIfNew(String eventId) {
        try {
            jdbc.sql("insert into processed_event (event_id, processed_at) values (:id, :at)")
                    .param("id", eventId)
                    .param("at", Timestamp.from(Instant.now()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public long count() {
        return jdbc.sql("select count(*) from processed_event").query(Long.class).single();
    }
}
