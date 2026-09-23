package io.hallmark.recon;

import io.hallmark.platform.events.Topics;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReconApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic breaksTopic() {
        return TopicBuilder.name(Topics.RECON_BREAKS).partitions(3).replicas(1).build();
    }
}
