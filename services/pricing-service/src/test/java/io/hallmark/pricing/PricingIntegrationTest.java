package io.hallmark.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.Topics;
import io.hallmark.platform.testing.Contracts;
import io.hallmark.platform.testing.Replay;
import io.hallmark.pricing.api.PricingQueries;
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
            "spring.datasource.url=jdbc:h2:mem:pricing-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "hallmark.pricing.poll-ms=500"
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
            Topics.REFDATA_SECURITY, Topics.MARKET_CURVE, Topics.TRADES_OBSERVATION, Topics.CONTROL_TRADEFLOW_EOD,
            Topics.PRICING_MARKS, Topics.CONTROL_PRICING_EOD
        })
class PricingIntegrationTest {
    @Autowired
    KafkaTemplate<String, String> template;

    @Autowired
    PricingQueries queries;

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    MockMvc mvc;

    @Autowired
    SecurityCache cache;

    @Test
    void pricesTwoRealDays() throws Exception {
        List<String> golden = RealMarket.goldenEvents();
        golden.forEach(e -> template.send(Topics.REFDATA_SECURITY, "k", e));
        for (LocalDate day : List.of(RealMarket.DAY1, RealMarket.DAY2)) {
            Replay.events(day.toString(), "market.curve.v1").forEach(e -> template.send(Topics.MARKET_CURVE, "k", e));
        }
        template.flush();
        await().atMost(Duration.ofSeconds(60)).until(() -> cache.size() > 300);

        for (LocalDate day : List.of(RealMarket.DAY1, RealMarket.DAY2)) {
            List<Map<String, Object>> observations = RealMarket.observationPayloads(day);
            observations.forEach(o -> template.send(Topics.TRADES_OBSERVATION, (String) o.get("isin"), envelope(
                    "TradeObservation", (String) o.get("isin"), day, o)));
            template.send(Topics.CONTROL_TRADEFLOW_EOD, day.toString(), envelope("EndOfDay", day.toString(), day,
                    Map.of("businessDate", day.toString(), "counts", Map.of("observations", observations.size()))));
        }
        template.flush();

        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> assertThat(queries.runs(5))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("DONE")));

        mvc.perform(get("/api/v1/marks").param("date", "2026-10-14").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
        mvc.perform(get("/api/v1/marks/summary").param("date", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.TRADED.count").isNumber())
                .andExpect(jsonPath("$.CARRIED.count").isNumber());
        mvc.perform(get("/api/v1/curves/2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curves.AAA.spreadsBps.length()").value(8));
        mvc.perform(get("/api/v1/backtest/summary").param("from", "2026-10-14").param("to", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.all.n").isNumber())
                .andExpect(jsonPath("$.byMethod.CARRIED.maeBps").isNumber());
        mvc.perform(get("/api/v1/marks/INE000000000/2026-10-14")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runs")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/runs/2026-10-14/requeue")).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(queries.runs(1).get(0).get("status"))
                .isEqualTo("DONE"));

        String isin = queries.marks(RealMarket.DAY2, null, null, "TRADED", null, 1, 0).get(0).isin();
        mvc.perform(get("/api/v1/marks/" + isin).param("from", "2026-10-13").param("to", "2026-10-14"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/marks/" + isin + "/2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("TRADED"));

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "check", true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                        props, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            consumer.subscribe(List.of(Topics.PRICING_MARKS, Topics.CONTROL_PRICING_EOD));
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1)).forEach(out::add);
                assertThat(out.stream().filter(r -> r.topic().equals(Topics.CONTROL_PRICING_EOD)).count())
                        .isGreaterThanOrEqualTo(2);
                assertThat(out.stream().filter(r -> r.topic().equals(Topics.PRICING_MARKS)).count())
                        .isGreaterThan(200);
            });
            out.stream().filter(r -> r.topic().equals(Topics.PRICING_MARKS)).limit(500)
                    .forEach(r -> Contracts.assertConforms("Mark", r.value()));
            out.stream().filter(r -> r.topic().equals(Topics.CONTROL_PRICING_EOD))
                    .forEach(r -> Contracts.assertConforms("EndOfDay", r.value()));
        }
    }

    private static String envelope(String type, String key, LocalDate day, Object payload) {
        return RealMarket.MAPPER.writeValueAsString(EventEnvelope.of(RealMarket.MAPPER, type, "test", key, day, payload));
    }
}
