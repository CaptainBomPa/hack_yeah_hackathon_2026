package pl.hackyeah.controllayer.audit;

import java.time.Instant;
import java.util.List;
import pl.hackyeah.controllayer.chat.ControlTrace;

/**
 * Dane jednego żądania do zapisania w audycie. Pola od klienta (`sessionId`, `model`) są
 * niezaufane — `AuditService` sanitizuje je przed zapisem. `trace` może być null tylko przy
 * odtwarzaniu rekordu z bazy (weryfikacja łańcucha czyta wtedy JSON wprost z kolumny).
 */
public record AuditEntry(
        String requestId,
        Instant occurredAt,
        String principal,
        String role,
        String sessionId,
        String model,
        String action,
        String blockedBy,
        int httpStatus,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        int messageCount,
        List<ControlTrace> trace) {}
