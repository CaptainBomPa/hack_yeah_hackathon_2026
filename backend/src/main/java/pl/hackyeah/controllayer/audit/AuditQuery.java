package pl.hackyeah.controllayer.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtry listy i eksportu audytu (`/api/audit/events`, `/api/audit/export`). Pola wielowartościowe
 * to wybór z listy (`GET /api/audit/facets`) — rekord pasuje, gdy ma którąkolwiek z wartości.
 * `sessionId` to wyszukiwanie „zawiera” bez wielkości liter (ID sesji są losowe, nikt ich nie pamięta).
 * Parametry trafiają do zapytania wyłącznie przez Criteria API (bez konkatenacji SQL — por. CVE-2024-5225).
 */
public record AuditQuery(
        List<String> actions,
        List<String> principals,
        List<String> models,
        List<String> blockedBy,
        String sessionId,
        Instant from,
        Instant to,
        Long before) {

    Specification<AuditEvent> toSpecification() {
        List<Specification<AuditEvent>> parts = new ArrayList<>();
        addAnyOf(parts, "action", actions);
        addAnyOf(parts, "principal", principals);
        addAnyOf(parts, "model", models);
        addAnyOf(parts, "blockedBy", blockedBy);
        if (sessionId != null && !sessionId.isBlank()) {
            String pattern = "%" + escapeLike(sessionId.trim().toLowerCase(Locale.ROOT)) + "%";
            parts.add((root, q, cb) -> cb.like(cb.lower(root.get("sessionId")), pattern, '\\'));
        }
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

    private static void addAnyOf(List<Specification<AuditEvent>> parts, String field, List<String> values) {
        List<String> wanted = values == null ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
        if (!wanted.isEmpty()) {
            parts.add((root, q, cb) -> root.get(field).in(wanted));
        }
    }

    /** `%` i `_` z wejścia to zwykłe znaki, nie wildcardy LIKE. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
