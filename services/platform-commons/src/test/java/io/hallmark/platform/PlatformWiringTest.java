package io.hallmark.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.platform.kafka.DeadLetterConfig;
import io.hallmark.platform.web.ApiExceptionHandler;
import io.hallmark.platform.web.NotFoundException;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import tools.jackson.databind.json.JsonMapper;

class PlatformWiringTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void publisherSendsKeyedEnvelopeJson() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        var publisher = new EventPublisher(template, mapper, "pricing-service");
        publisher.publish(Topics.PRICING_MARKS, "Mark", "INE423A07484", LocalDate.of(2026, 10, 14), Map.of("x", 1));
        publisher.flush();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq(Topics.PRICING_MARKS), eq("INE423A07484"), json.capture());
        verify(template).flush();
        EventEnvelope envelope = new EventReader(mapper).envelope(json.getValue());
        assertThat(envelope.eventType()).isEqualTo("Mark");
        assertThat(envelope.source()).isEqualTo("pricing-service");
        assertThat(envelope.businessDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(envelope.payload().get("x").asInt()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterHandlerIsConfigured() {
        DefaultErrorHandler handler = DeadLetterConfig.errorHandler(mock(KafkaOperations.class));
        assertThat(handler).isNotNull();
        assertThat(Topics.TRADES_REPORTED + Topics.DLQ_SUFFIX).isEqualTo("hallmark.trades.reported.v1.dlq");
    }

    @Test
    void exceptionsMapToProblemDetails() {
        var handler = new ApiExceptionHandler();
        assertThat(handler.notFound(new NotFoundException("no ISIN")).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        var bad = handler.badRequest(new IllegalArgumentException("bad date"));
        assertThat(bad.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(bad.getDetail()).isEqualTo("bad date");
    }

    @Test
    void autoConfigurationCreatesBeans() {
        var config = new PlatformAutoConfiguration();
        assertThat(config.eventReader(mapper)).isNotNull();
        assertThat(config.processedEvents(org.springframework.jdbc.core.simple.JdbcClient.create(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:x"))))
                .isNotNull();
    }
}
