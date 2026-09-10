package io.hallmark.refdata.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.hallmark.platform.web.NotFoundException;
import io.hallmark.refdata.domain.GoldenSecurity;
import io.hallmark.refdata.persistence.RefdataRepository;
import io.hallmark.refdata.persistence.RefdataRepository.ConflictRow;
import io.hallmark.refdata.persistence.RefdataRepository.RatingAction;
import io.hallmark.refdata.persistence.RefdataRepository.SecuritySummary;
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

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Reference data")
public class SecurityController {
    private final RefdataRepository repository;

    public SecurityController(RefdataRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/securities/{isin}")
    @Operation(summary = "Golden record for an ISIN, with field lineage and source conflicts")
    public GoldenSecurity security(@PathVariable String isin) {
        return repository.golden(isin.toUpperCase())
                .orElseThrow(() -> new NotFoundException("unknown ISIN " + isin));
    }

    @GetMapping("/securities")
    @Operation(summary = "Search securities by issuer name or ISIN, sector group and rating bucket")
    public List<SecuritySummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String issuerKey,
            @RequestParam(required = false) String sectorGroup,
            @RequestParam(required = false) String ratingBucket,
            @RequestParam(required = false) Boolean pricingEligible,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return repository.search(q, issuerKey, sectorGroup, ratingBucket, pricingEligible, page, size);
    }

    @GetMapping("/ratings/actions")
    @Operation(summary = "Rating actions effective in a date range, optionally filtered by action type")
    public List<RatingAction> ratingActions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String action) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        return repository.ratingActions(from, to, action);
    }

    @GetMapping("/refdata/stats")
    @Operation(summary = "Coverage of the golden store by sector, rating bucket and eligibility")
    public Map<String, Object> stats() {
        return repository.stats();
    }

    @GetMapping("/refdata/conflicts")
    @Operation(summary = "Fields where NSDL and the NSE reporting master disagree")
    public List<ConflictRow> conflicts(@RequestParam(defaultValue = "200") @Min(1) @Max(5000) int limit) {
        return repository.conflicts(limit);
    }
}
