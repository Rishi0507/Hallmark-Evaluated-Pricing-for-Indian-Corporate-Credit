package io.hallmark.recon;

import io.hallmark.recon.domain.Outcome;
import io.hallmark.recon.domain.Outcome.Result;
import io.hallmark.recon.persistence.ReconRepository;
import io.hallmark.recon.persistence.ReconRepository.BreakRow;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BreakBook {
    public static final String OPEN = "OPEN";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String RESOLVED = "RESOLVED";
    static final String SYSTEM = "system";

    private final ReconRepository repository;

    public BreakBook(ReconRepository repository) {
        this.repository = repository;
    }

    public Optional<String> apply(Outcome o, LocalDate runOn) {
        Optional<BreakRow> existing = repository.findBreak(o.breakId());
        if (o.result() == Result.MATCHED || o.result() == Result.PENDING) {
            if (o.result() == Result.MATCHED && existing.isPresent() && !RESOLVED.equals(existing.get().status())) {
                repository.refreshBreak(o, RESOLVED, runOn, "Matched on re-evaluation on " + runOn, SYSTEM);
                return Optional.of(RESOLVED);
            }
            return Optional.empty();
        }
        if (o.result() == Result.EXPLAINED) {
            String note = "Explained automatically: " + o.breakType();
            if (existing.isEmpty()) {
                repository.insertBreak(o, ACKNOWLEDGED, runOn, note);
                return Optional.of(ACKNOWLEDGED);
            }
            String status = OPEN.equals(existing.get().status()) ? ACKNOWLEDGED : existing.get().status();
            repository.refreshBreak(o, status, runOn, note, SYSTEM);
            return status.equals(existing.get().status()) ? Optional.empty() : Optional.of(status);
        }
        if (existing.isEmpty()) {
            repository.insertBreak(o, OPEN, runOn, null);
            return Optional.of(OPEN);
        }
        BreakRow row = existing.get();
        if (RESOLVED.equals(row.status())) {
            repository.refreshBreak(o, OPEN, runOn, "Reopened on " + runOn, SYSTEM);
            return Optional.of(OPEN);
        }
        repository.refreshBreak(o, row.status(), runOn, row.resolution(), row.updatedBy());
        return o.breakType().equals(row.breakType()) ? Optional.empty() : Optional.of(row.status());
    }

    public BreakRow transition(String breakId, String target, String comment, String user, LocalDate on) {
        BreakRow row = repository.findBreak(breakId)
                .orElseThrow(() -> new io.hallmark.platform.web.NotFoundException("unknown break " + breakId));
        Set<String> allowed = switch (row.status()) {
            case OPEN -> Set.of(ACKNOWLEDGED, RESOLVED);
            case ACKNOWLEDGED -> Set.of(RESOLVED, OPEN);
            default -> Set.of();
        };
        if (!allowed.contains(target)) {
            throw new IllegalStateException("cannot move a " + row.status() + " break to " + target);
        }
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("a comment is required");
        }
        repository.updateStatus(breakId, target, comment.trim(), user == null ? "analyst" : user, on);
        return repository.findBreak(breakId).orElseThrow();
    }
}
