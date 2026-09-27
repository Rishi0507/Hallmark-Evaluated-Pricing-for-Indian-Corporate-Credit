package io.hallmark.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hallmark.platform.events.Topics;
import io.hallmark.platform.testing.Contracts;
import io.hallmark.platform.testing.Replay;
import io.hallmark.recon.persistence.ReconRepository;
import io.hallmark.recon.persistence.ReconRepository.BreakRow;
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
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.datasource.url=jdbc:h2:mem:recon-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
            Topics.TRADES_REPORTED, Topics.TRADES_SETTLEMENT, Topics.CONTROL_EOD, Topics.REFDATA_SOURCE,
            Topics.RECON_BREAKS
        })
class ReconIntegrationTest {
    @Autowired
    KafkaTemplate<String, String> template;

    @Autowired
    ReconRepository repository;

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    MockMvc mvc;

    @Test
    void reconcilesTwoRealDays() throws Exception {
        Replay.events("2026-10-14", "refdata.source.v1").forEach(e -> template.send(Topics.REFDATA_SOURCE, "k", e));
        for (String day : List.of("2026-10-13", "2026-10-14")) {
            Replay.events(day, "trades.reported.v1").forEach(e -> template.send(Topics.TRADES_REPORTED, "k", e));
            Replay.events(day, "trades.settlement.v1").forEach(e -> template.send(Topics.TRADES_SETTLEMENT, "k", e));
            Replay.events(day, "control.eod.v1").forEach(e -> template.send(Topics.CONTROL_EOD, day, e));
        }
        template.flush();

        LocalDate from = LocalDate.parse("2026-10-01");
        LocalDate to = LocalDate.parse("2026-10-14");
        await().atMost(Duration.ofSeconds(180)).untilAsserted(() -> assertThat(repository.runs(to, to))
                .anyMatch(r -> "YIELD_RECOMPUTE".equals(r.get("reconType"))));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(repository.runs(from, to))
                .anyMatch(r -> "TRADE_VS_SETTLEMENT".equals(r.get("reconType"))
                        && LocalDate.parse("2026-10-13").equals(r.get("businessDate"))));

        List<BreakRow> breaks = repository.breaks(null, null, null, from, to, 5000);
        assertThat(breaks).isNotEmpty();
        assertThat(breaks).extracting(BreakRow::reconType).contains("TRADE_VS_SETTLEMENT", "YIELD_RECOMPUTE");

        BreakRow open = breaks.stream().filter(b -> "OPEN".equals(b.status())).findFirst().orElseThrow();
        String path = "/api/v1/breaks/" + open.breakId();
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\",\"comment\":\"checked with operations\",\"user\":\"analyst1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.updatedBy").value("analyst1"));
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"comment\":\"settled bilaterally\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\",\"comment\":\"again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WRONG\",\"comment\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/breaks/none")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/breaks").param("from", "2026-10-01").param("to", "2026-10-14").param("status", "OPEN"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/breaks/summary").param("from", "2026-10-01").param("to", "2026-10-14"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/breaks/aging")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/recons/runs").param("from", "2026-10-01").param("to", "2026-10-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evaluated").isNumber());

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "check", true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                        props, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            consumer.subscribe(List.of(Topics.RECON_BREAKS));
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1)).forEach(out::add);
                assertThat(out).hasSizeGreaterThan(10);
            });
            out.forEach(r -> Contracts.assertConforms("ReconBreak", r.value()));
        }
    }
}
