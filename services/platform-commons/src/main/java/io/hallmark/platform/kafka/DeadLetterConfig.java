package io.hallmark.platform.kafka;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

public final class DeadLetterConfig {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterConfig.class);

    private DeadLetterConfig() {}

    public static DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(record.topic() + Topics.DLQ_SUFFIX, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                EventReader.MalformedEventException.class,
                IllegalArgumentException.class,
                BadSqlGrammarException.class,
                DataIntegrityViolationException.class);
        handler.setRetryListeners((record, ex, attempt) -> log.warn(
                "delivery attempt {} failed for {}-{}@{}: {}",
                attempt, record.topic(), record.partition(), record.offset(), rootMessage(ex)));
        return handler;
    }

    static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
