package io.hallmark.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hallmark.platform.events.Topics;
import io.hallmark.platform.testing.Contracts;
import io.hallmark.refdata.persistence.RefdataRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.datasource.url=jdbc:h2:mem:refdata-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        })
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        brokerProperties = {
            "offsets.topic.num.partitions=1",
            "group.initial.rebalance.delay.ms=0",
            "log.index.size.max.bytes=1048576"
        },
        topics = {Topics.REFDATA_SOURCE, Topics.REFDATA_SECURITY})
class RefdataIntegrationTest {
    @Autowired
    KafkaTemplate<String, String> template;

    @Autowired
    RefdataRepository repository;

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    MockMvc mvc;

    @Test
    void sourceRecordsBecomeGoldenRecords() throws Exception {
        List<String> examples = Contracts.examples("SecurityMasterRecord");
        for (String json : examples) {
            template.send(Topics.REFDATA_SOURCE, "k", json);
        }

        for (String json : examples) {
            template.send(Topics.REFDATA_SOURCE, "k", json);
        }
        template.flush();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(repository.golden("INE053F08403")).isPresent());
        long isins = examples.stream().map(e -> e.split("\"isin\":\"")[1].substring(0, 12)).distinct().count();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(repository.stats().get("securities")).isEqualTo(isins));

        mvc.perform(get("/api/v1/securities/INE053F08403"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectorGroup").value("PSU_FINANCIAL"))
                .andExpect(jsonPath("$.fieldSources.couponRate").value("NSDL"));
        mvc.perform(get("/api/v1/securities/INE000000000")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/securities").param("q", "bajaj"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isin").value("INE296A07UB9"));
        mvc.perform(get("/api/v1/ratings/actions").param("from", "2026-01-01").param("to", "2026-10-15"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ratings/actions").param("from", "2026-10-15").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/refdata/stats")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/refdata/conflicts")).andExpect(status().isOk());

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "golden-check", true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                        props, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            consumer.subscribe(List.of(Topics.REFDATA_SECURITY));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1)).forEach(received::add);
                assertThat(received).extracting(ConsumerRecord::key).contains("INE053F08403", "INE296A07UB9");
            });
            received.forEach(r -> Contracts.assertConforms("GoldenSecurity", r.value()));

            assertThat(received.stream().filter(r -> r.key().equals("INE296A07UB9")).count()).isEqualTo(1);
        }
    }
}
