package io.hallmark.refdata;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.platform.idempotency.ProcessedEvents;
import io.hallmark.refdata.domain.GoldenRecordBuilder;
import io.hallmark.refdata.domain.GoldenSecurity;
import io.hallmark.refdata.domain.SourceRecord;
import io.hallmark.refdata.persistence.RefdataRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RefdataService {
    static final String SECURITY_MASTER_RECORD = "SecurityMasterRecord";
    static final String GOLDEN_SECURITY = "GoldenSecurity";
    private static final Pattern ISIN = Pattern.compile("^IN[A-Z0-9]{9}[0-9]$");
    private static final Logger log = LoggerFactory.getLogger(RefdataService.class);

    private final RefdataRepository repository;
    private final ProcessedEvents processed;
    private final EventReader reader;
    private final Optional<EventPublisher> publisher;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Counter applied;
    private final Counter goldenChanged;
    private final Counter duplicates;

    public RefdataService(
            RefdataRepository repository,
            ProcessedEvents processed,
            EventReader reader,
            Optional<EventPublisher> publisher,
            JsonMapper mapper,
            Clock clock,
            MeterRegistry meters) {
        this.repository = repository;
        this.processed = processed;
        this.reader = reader;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
        this.applied = meters.counter("hallmark.refdata.source.applied");
        this.goldenChanged = meters.counter("hallmark.refdata.golden.changed");
        this.duplicates = meters.counter("hallmark.refdata.source.duplicates");
    }

    @Transactional
    public Optional<GoldenSecurity> apply(EventEnvelope envelope) {
        if (!SECURITY_MASTER_RECORD.equals(envelope.eventType())) {
            throw new IllegalArgumentException("unexpected event type " + envelope.eventType());
        }
        if (!processed.markIfNew(envelope.eventId())) {
            duplicates.increment();
            return Optional.empty();
        }
        SourceRecord record = reader.payload(envelope, SourceRecord.class);
        if (record.isin() == null || !ISIN.matcher(record.isin()).matches()) {
            throw new IllegalArgumentException("invalid ISIN " + record.isin());
        }
        repository.saveSource(record);
        applied.increment();

        GoldenSecurity golden = GoldenRecordBuilder.merge(
                record.isin(), repository.sources(record.isin()), LocalDate.now(clock));
        String json = mapper.writeValueAsString(golden);
        String hash = sha256(json);
        if (repository.contentHash(record.isin()).filter(hash::equals).isPresent()) {
            return Optional.empty();
        }
        repository.saveGolden(golden, json, hash);
        goldenChanged.increment();
        publishAfterCommit(golden);
        return Optional.of(golden);
    }

    private void publishAfterCommit(GoldenSecurity golden) {
        if (publisher.isEmpty()) {
            return;
        }
        Runnable send = () -> publisher.get()
                .publish(Topics.REFDATA_SECURITY, GOLDEN_SECURITY, golden.isin(), null, golden)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("failed to publish golden record {}", golden.isin(), ex);
                    }
                });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
