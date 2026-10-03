package pl.hackyeah.controllayer.audit;

import java.time.Instant;
import java.util.List;
import pl.hackyeah.controllayer.chat.ControlTrace;
import pl.hackyeah.controllayer.chat.Usage;

/** Rekord audytu w API dashboardu — `AuditEvent` z frontend/src/api/types.ts. */
public record AuditEventView(
        long seq,
        String requestId,
        Instant timestamp,
        String principal,
        String role,
        String sessionId,
        String model,
        String action,
        String blockedBy,
        int httpStatus,
        long latencyMs,
        Usage usage,
        int messageCount,
        List<ControlTrace> trace,
        Long policyVersion,
        String recordHash) {

    static AuditEventView of(AuditEvent e, List<ControlTrace> trace) {
        Usage usage = e.getPromptTokens() == null ? null
                : new Usage(e.getPromptTokens(), e.getCompletionTokens() == null ? 0 : e.getCompletionTokens());
        return new AuditEventView(e.getSeq(), e.getRequestId(), e.getOccurredAt(), e.getPrincipal(), e.getRole(),
                e.getSessionId(), e.getModel(), e.getAction(), e.getBlockedBy(), e.getHttpStatus(), e.getLatencyMs(),
                usage, e.getMessageCount(), trace, e.getPolicyVersion(), e.getRecordHash());
    }

    /** Strona listy z kursorem keyset (`before=nextCursor` daje kolejną stronę). */
    public record Page(List<AuditEventView> items, Long nextCursor) {}
}
