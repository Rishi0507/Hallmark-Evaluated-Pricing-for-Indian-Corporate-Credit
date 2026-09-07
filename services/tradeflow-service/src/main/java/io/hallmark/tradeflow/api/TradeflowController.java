package io.hallmark.tradeflow.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.hallmark.tradeflow.persistence.TradeflowRepository;
import io.hallmark.tradeflow.persistence.TradeflowRepository.ChannelSummary;
import io.hallmark.tradeflow.persistence.TradeflowRepository.LifecycleRow;
import io.hallmark.tradeflow.persistence.TradeflowRepository.StateSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Trade flow")
public class TradeflowController {
    private final TradeflowRepository repository;
    private final JsonMapper mapper;

    public TradeflowController(TradeflowRepository repository, JsonMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @GetMapping("/trades")
    @Operation(summary = "Lifecycle rows for a trade date range, filtered by ISIN or state")
    public List<LifecycleRow> trades(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String isin,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        requireRange(from, to);
        return repository.lifecycle(from, to, isin == null ? null : isin.toUpperCase(), state, limit);
    }

    @GetMapping("/trades/{isin}")
    @Operation(summary = "Lifecycle history of one ISIN")
    public List<LifecycleRow> history(
            @PathVariable String isin,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return repository.lifecycle(from, to, isin.toUpperCase(), null, 5000);
    }

    @GetMapping("/lifecycle/summary")
    @Operation(summary = "Count and face value by lifecycle state per trade date")
    public List<StateSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return repository.stateSummary(from, to);
    }

    @GetMapping("/settlement/channels")
    @Operation(summary = "Settled bond face value by month, counterparty class and denomination class")
    public List<ChannelSummary> channels(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return repository.channels(from, to);
    }

    @GetMapping("/observations")
    @Operation(summary = "Price observations emitted for a trade date")
    public List<JsonNode> observations(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return repository.observationsOn(date).stream().map(mapper::readTree).toList();
    }

    @GetMapping("/eod")
    @Operation(summary = "End-of-day barrier status")
    public List<Map<String, Object>> eod(@RequestParam(defaultValue = "30") @Min(1) @Max(1000) int limit) {
        return repository.eodStatus(limit);
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        if (from.plusDays(400).isBefore(to)) {
            throw new IllegalArgumentException("date range is limited to 400 days");
        }
    }
}
