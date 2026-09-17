package io.hallmark.pricing;

import io.hallmark.platform.events.Topics;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PricingProperties.class)
public class PricingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PricingApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic marksTopic() {
        return TopicBuilder.name(Topics.PRICING_MARKS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic pricingEodTopic() {
        return TopicBuilder.name(Topics.CONTROL_PRICING_EOD).partitions(1).replicas(1).build();
    }
}
