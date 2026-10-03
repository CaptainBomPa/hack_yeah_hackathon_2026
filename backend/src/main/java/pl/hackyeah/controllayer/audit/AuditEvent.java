package pl.hackyeah.controllayer.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Rekord audytu jednego żądania (V3__audit_event.sql). Pola tekstowe pochodzące od klienta są
 * już zsanitizowane (`AuditSanitizer`); treści promptów i odpowiedzi celowo nie ma (VISION.md §6).
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent implements Persistable<Long> {

    @Id
    private long seq;

    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(length = 100)
    private String principal;

    @Column(length = 50)
    private String role;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(length = 200)
    private String model;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "blocked_by", length = 100)
    private String blockedBy;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /** Ścieżka kontroli (lista `ControlTrace`) jako JSON. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String controls;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;

    protected AuditEvent() {
    }

    AuditEvent(long seq, AuditEntry entry, String controls, String prevHash, String recordHash) {
        this.seq = seq;
        this.requestId = entry.requestId();
        this.occurredAt = entry.occurredAt();
        this.principal = entry.principal();
        this.role = entry.role();
        this.sessionId = entry.sessionId();
        this.model = entry.model();
        this.action = entry.action();
        this.blockedBy = entry.blockedBy();
        this.httpStatus = entry.httpStatus();
        this.latencyMs = entry.latencyMs();
        this.promptTokens = entry.promptTokens();
        this.completionTokens = entry.completionTokens();
        this.messageCount = entry.messageCount();
        this.controls = controls;
        this.prevHash = prevHash;
        this.recordHash = recordHash;
    }

    /** Pola wchodzące do hasha, w stałej kolejności (bez samego `recordHash`). */
    AuditEntry toEntry() {
        return new AuditEntry(requestId, occurredAt, principal, role, sessionId, model, action, blockedBy,
                httpStatus, latencyMs, promptTokens, completionTokens, messageCount, null);
    }

    public long getSeq() {
        return seq;
    }

    @Override
    public Long getId() {
        return seq;
    }

    /**
     * Rekordy są tylko dopisywane: `save` zawsze robi INSERT, nigdy merge. Bez tego JPA przy
     * istniejącym `seq` po cichu nadpisałoby rekord audytu.
     */
    @Override
    public boolean isNew() {
        return true;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getRole() {
        return role;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getModel() {
        return model;
    }

    public String getAction() {
        return action;
    }

    public String getBlockedBy() {
        return blockedBy;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public String getControls() {
        return controls;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getRecordHash() {
        return recordHash;
    }
}
