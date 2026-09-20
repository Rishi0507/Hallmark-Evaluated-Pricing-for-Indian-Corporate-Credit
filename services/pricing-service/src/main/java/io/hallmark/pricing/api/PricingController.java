package io.hallmark.pricing.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.hallmark.platform.web.NotFoundException;
import io.hallmark.pricing.api.PricingQueries.MarkRow;
import io.hallmark.pricing.api.PricingQueries.PredictionRow;
import io.hallmark.pricing.persistence.PricingRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Pricing")
public class PricingController {
    private final PricingQueries queries;
    private final PricingRepository repository;

    public PricingController(PricingQueries queries, PricingRepository repository) {
        this.queries = queries;
        this.repository = repository;
    }

    @GetMapping("/marks")
    @Operation(summary = "Marks for a valuation date, widest spread first")
    public List<MarkRow> marks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String ratingBucket,
            @RequestParam(required = false) String sectorGroup,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") @Min(1) @Max(5000) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return queries.marks(date, ratingBucket, sectorGroup, method, q, limit, offset);
    }

    @GetMapping("/marks/{isin}")
    @Operation(summary = "Mark history of one ISIN")
    public List<MarkRow> history(
            @PathVariable String isin,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return queries.history(isin.toUpperCase(), from, to);
    }

    @GetMapping("/marks/{isin}/{date}")
    @Operation(summary = "One mark with its method, confidence and evidence")
    public MarkRow mark(
            @PathVariable String isin, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.history(isin.toUpperCase(), date, date).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("no mark for " + isin + " on " + date));
    }

    @GetMapping("/marks/summary")
    @Operation(summary = "Count and average confidence by marking method")
    public Map<String, Object> summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.methodMix(date);
    }

    @GetMapping("/curves/{date}")
    @Operation(summary = "Fitted credit curves by rating bucket and sector offsets")
    public Map<String, Object> curves(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.creditCurves(date);
    }

    @GetMapping("/backtest/summary")
    @Operation(summary = "Out-of-sample error of T-1 marks against next institutional trades, with baselines")
    public Map<String, Object> backtest(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return queries.backtestSummary(from, to);
    }

    @GetMapping("/backtest/predictions")
    @Operation(summary = "Individual out-of-sample predictions")
    public List<PredictionRow> predictions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return queries.predictions(from, to);
    }

    @GetMapping("/runs")
    @Operation(summary = "End-of-day pricing runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "30") @Min(1) @Max(1000) int limit) {
        return queries.runs(limit);
    }

    @PostMapping("/runs/{date}/requeue")
    @Operation(summary = "Queue a business date for repricing")
    public ResponseEntity<Void> requeue(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        repository.requeueEod(date);
        return ResponseEntity.accepted().build();
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
    }
}
