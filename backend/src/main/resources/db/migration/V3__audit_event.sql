-- Audit log (docs/deterministic/27-audit-logging.md, VISION.md §6). Jeden rekord na żądanie
-- /v1/chat/completions z pełną ścieżką kontroli w `controls` (JSON, także decyzje ALLOW).
-- Bez treści promptów i odpowiedzi. `record_hash` = HMAC-SHA256 po (prev_hash + rekord), więc
-- modyfikację albo usunięcie rekordu wykrywa GET /api/audit/verify.
CREATE TABLE audit_event (
    seq               BIGINT PRIMARY KEY,
    request_id        VARCHAR(36)  NOT NULL UNIQUE,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    principal         VARCHAR(100),
    role              VARCHAR(50),
    session_id        VARCHAR(100),
    model             VARCHAR(200),
    action            VARCHAR(20)  NOT NULL,
    blocked_by        VARCHAR(100),
    http_status       INTEGER      NOT NULL,
    latency_ms        BIGINT       NOT NULL,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    message_count     INTEGER      NOT NULL,
    controls          TEXT         NOT NULL,
    prev_hash         VARCHAR(64)  NOT NULL,
    record_hash       VARCHAR(64)  NOT NULL
);

CREATE INDEX audit_event_occurred_at_idx ON audit_event (occurred_at);
CREATE INDEX audit_event_action_idx ON audit_event (action);

-- Append-only (AUDIT-004): aplikacja tylko dopisuje; UPDATE/DELETE kończą się błędem.
CREATE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();
