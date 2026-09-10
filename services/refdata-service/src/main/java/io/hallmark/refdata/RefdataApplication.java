package io.hallmark.refdata;

import io.hallmark.platform.events.Topics;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class RefdataApplication {
    public static void main(String[] args) {
        SpringApplication.run(RefdataApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic goldenSecurityTopic() {
        return TopicBuilder.name(Topics.REFDATA_SECURITY).partitions(3).replicas(1).compact().build();
    }
}
