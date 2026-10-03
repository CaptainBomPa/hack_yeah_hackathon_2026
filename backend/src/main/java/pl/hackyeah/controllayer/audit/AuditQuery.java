package pl.hackyeah.controllayer.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtry listy i eksportu audytu (`/api/audit/events`, `/api/audit/export`). Parametry trafiają
 * do zapytania wyłącznie przez Criteria API (bez konkatenacji SQL — por. CVE-2024-5225).
 */
public record AuditQuery(
        String action,
        String principal,
        String model,
        String blockedBy,
        String sessionId,
        Instant from,
        Instant to,
        Long before) {

    Specification<AuditEvent> toSpecification() {
        List<Specification<AuditEvent>> parts = new ArrayList<>();
        addEquals(parts, "action", action);
        addEquals(parts, "principal", principal);
        addEquals(parts, "model", model);
        addEquals(parts, "blockedBy", blockedBy);
        addEquals(parts, "sessionId", sessionId);
        if (from != null) {
            parts.add((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            parts.add((root, q, cb) -> cb.lessThan(root.get("occurredAt"), to));
        }
        if (before != null) {
            parts.add((root, q, cb) -> cb.lessThan(root.get("seq"), before));
        }
        return Specification.allOf(parts);
    }

    private static void addEquals(List<Specification<AuditEvent>> parts, String field, String value) {
        if (value != null && !value.isBlank()) {
            parts.add((root, q, cb) -> cb.equal(root.get(field), value));
        }
    }
}
