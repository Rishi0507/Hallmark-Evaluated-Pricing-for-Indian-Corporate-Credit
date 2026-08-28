package io.hallmark.platform;

import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.idempotency.ProcessedEvents;
import io.hallmark.platform.kafka.DeadLetterConfig;
import io.hallmark.platform.web.ApiExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration"
        })
@Import(ApiExceptionHandler.class)
public class PlatformAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public EventReader eventReader(JsonMapper mapper) {
        return new EventReader(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public EventPublisher eventPublisher(
            KafkaTemplate<String, String> template,
            JsonMapper mapper,
            @Value("${spring.application.name:hallmark}") String source) {
        return new EventPublisher(template, mapper, source);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(KafkaOperations.class)
    @SuppressWarnings("unchecked")
    public CommonErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> template) {
        return DeadLetterConfig.errorHandler((KafkaOperations<Object, Object>) template);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcClient.class)
    public ProcessedEvents processedEvents(JdbcClient jdbc) {
        return new ProcessedEvents(jdbc);
    }
}
