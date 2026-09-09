package io.hallmark.tradeflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hallmark.platform.events.Topics;
import io.hallmark.platform.testing.Contracts;
import io.hallmark.platform.testing.Replay;
import io.hallmark.tradeflow.persistence.TradeflowRepository;
import java.time.Duration;
import java.time.LocalDate;
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
            "spring.datasource.url=jdbc:h2:mem:tradeflow-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "hallmark.eod.poll-ms=500"
        })
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        brokerProperties = {
            "offsets.topic.num.partitions=1",
            "group.initial.rebalance.delay.ms=0",
            "log.index.size.max.bytes=1048576"
        },
        topics = {
            Topics.TRADES_REPORTED, Topics.TRADES_SETTLEMENT, Topics.CONTROL_EOD, Topics.REFDATA_SECURITY,
            Topics.TRADES_OBSERVATION, Topics.TRADES_LIFECYCLE, Topics.CONTROL_TRADEFLOW_EOD
        })
class TradeflowReplayIntegrationTest {
    private static final LocalDate DAY = LocalDate.parse(Replay.DAY);

    @Autowired
    KafkaTemplate<String, String> template;

    @Autowired
    TradeflowRepository repository;

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    MockMvc mvc;

    @Test
    void replaysARealDayEndToEnd() throws Exception {
        RealDay.goldenEvents().forEach(e -> template.send(Topics.REFDATA_SECURITY, "k", e));
        template.flush();
        String anySecurity = RealDay.securityPayloads().keySet().iterator().next();
        await().atMost(Duration.ofSeconds(60)).until(() -> repository.security(anySecurity).isPresent());

        Replay.events("control.eod.v1").forEach(e -> template.send(Topics.CONTROL_EOD, Replay.DAY, e));
        template.flush();
        List<String> reported = Replay.events("trades.reported.v1");
        List<String> settlements = Replay.events("trades.settlement.v1");
        reported.forEach(e -> template.send(Topics.TRADES_REPORTED, "k", e));
        settlements.forEach(e -> template.send(Topics.TRADES_SETTLEMENT, "k", e));

        reported.forEach(e -> template.send(Topics.TRADES_REPORTED, "k", e));
        template.flush();

        await().atMost(Duration.ofSeconds(120))
                .until(() -> repository.reportedCount(DAY) >= reported.size()
                        && repository.legCount(DAY) >= settlements.size());
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> assertThat(repository.eodStatus(1))
                .as("reported=%d legs=%d", repository.reportedCount(DAY), repository.legCount(DAY))
                .singleElement()
                .satisfies(m -> assertThat(m.get("status")).isEqualTo("DONE")));

        assertThat(repository.reportedCount(DAY)).isEqualTo(reported.size());
        assertThat(repository.legCount(DAY)).isEqualTo(settlements.size());
        var lifecycle = repository.lifecycle(DAY.minusDays(10), DAY, null, null, 5000);
        assertThat(lifecycle).isNotEmpty();
        assertThat(lifecycle).extracting(TradeflowRepository.LifecycleRow::state).contains("SETTLED");

        mvc.perform(get("/api/v1/lifecycle/summary").param("from", "2026-10-01").param("to", Replay.DAY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/settlement/channels").param("from", "2026-10-01").param("to", Replay.DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("2026-10"));
        mvc.perform(get("/api/v1/trades").param("from", Replay.DAY).param("to", Replay.DAY).param("limit", "5"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/trades").param("from", Replay.DAY).param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/observations").param("date", Replay.DAY)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/eod")).andExpect(status().isOk());

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "check", true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                        props, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            consumer.subscribe(List.of(
                    Topics.TRADES_OBSERVATION, Topics.TRADES_LIFECYCLE, Topics.CONTROL_TRADEFLOW_EOD));
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1)).forEach(out::add);

                assertThat(out).anyMatch(r -> r.topic().equals(Topics.CONTROL_TRADEFLOW_EOD));
                assertThat(out.stream().filter(r -> r.topic().equals(Topics.TRADES_OBSERVATION)).count())
                        .isGreaterThan(300);
            });
            for (ConsumerRecord<String, String> r : out) {
                String type = switch (r.topic()) {
                    case Topics.TRADES_OBSERVATION -> "TradeObservation";
                    case Topics.TRADES_LIFECYCLE -> "TradeLifecycle";
                    default -> "EndOfDay";
                };
                Contracts.assertConforms(type, r.value());
            }
            long observations = out.stream().filter(r -> r.topic().equals(Topics.TRADES_OBSERVATION)).count();
            assertThat(observations).isGreaterThan(300);
        }
    }
}
