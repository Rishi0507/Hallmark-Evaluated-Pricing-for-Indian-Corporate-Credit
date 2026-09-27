package io.hallmark.recon.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.hallmark.platform.web.NotFoundException;
import io.hallmark.recon.BreakBook;
import io.hallmark.recon.persistence.ReconRepository;
import io.hallmark.recon.persistence.ReconRepository.BreakRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Reconciliation")
public class ReconController {
    private final ReconRepository repository;
    private final BreakBook book;
    private final Clock clock;

    public ReconController(ReconRepository repository, BreakBook book, Clock clock) {
        this.repository = repository;
        this.book = book;
        this.clock = clock;
    }

    @GetMapping("/breaks")
    @Operation(summary = "Breaks by business date range, status, control and type")
    public List<BreakRow> breaks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reconType,
            @RequestParam(required = false) String breakType,
            @RequestParam(defaultValue = "200") @Min(1) @Max(5000) int limit) {
        requireRange(from, to);
        return repository.breaks(status, reconType, breakType, from, to, limit);
    }

    @GetMapping("/breaks/{breakId}")
    @Operation(summary = "One break with its evidence")
    public BreakRow breakById(@PathVariable String breakId) {
        return repository.findBreak(breakId).orElseThrow(() -> new NotFoundException("unknown break " + breakId));
    }

    public record Transition(
            @NotBlank @Pattern(regexp = "OPEN|ACKNOWLEDGED|RESOLVED") String status,
            @NotBlank String comment,
            String user) {}

    @PatchMapping("/breaks/{breakId}")
    @Operation(summary = "Analyst workflow: acknowledge, resolve or reopen a break, with a comment")
    public BreakRow transition(@PathVariable String breakId, @Valid @RequestBody Transition t) {
        return book.transition(breakId, t.status(), t.comment(), t.user(), LocalDate.now(clock));
    }

    @GetMapping("/breaks/summary")
    @Operation(summary = "Break counts and face value by control, type, status and severity")
    public List<Map<String, Object>> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return repository.breakSummary(from, to);
    }

    @GetMapping("/breaks/aging")
    @Operation(summary = "Open breaks by age bucket in calendar days")
    public Map<String, Map<String, Long>> aging() {
        LocalDate today = LocalDate.now(clock);
        Map<String, Map<String, Long>> out = new TreeMap<>();
        for (Map<String, Object> row : repository.openBreakAges(today)) {
            long age = ChronoUnit.DAYS.between((LocalDate) row.get("businessDate"), today);
            String bucket = age <= 1 ? "0-1" : age <= 5 ? "2-5" : age <= 10 ? "6-10" : age <= 30 ? "11-30" : ">30";
            out.computeIfAbsent((String) row.get("reconType"), k -> new LinkedHashMap<>())
                    .merge(bucket, (Long) row.get("count"), Long::sum);
        }
        return out;
    }

    @GetMapping("/recons/runs")
    @Operation(summary = "Evaluated, matched, explained, pending and break counts per control and date")
    public List<Map<String, Object>> runs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireRange(from, to);
        return repository.runs(from, to);
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
    }
}
