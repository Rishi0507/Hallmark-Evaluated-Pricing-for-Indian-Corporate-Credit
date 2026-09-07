package io.hallmark.tradeflow;

import io.hallmark.platform.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradeflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeflowApplication.class, args);
    }

    @Bean
    NewTopic observationTopic() {
        return TopicBuilder.name(Topics.TRADES_OBSERVATION).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic lifecycleTopic() {
        return TopicBuilder.name(Topics.TRADES_LIFECYCLE).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic tradeflowEodTopic() {
        return TopicBuilder.name(Topics.CONTROL_TRADEFLOW_EOD).partitions(1).replicas(1).build();
    }
}
