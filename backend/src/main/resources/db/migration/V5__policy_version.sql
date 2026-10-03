-- Polityka gatewaya w bazie (docs/policy-management-plan.md). Append-only: każda zmiana z UI to nowa
-- wersja, aktywna = najnowsza; przywrócenie starej = nowa wersja z jej treścią. `document` to cała
-- polityka jako kanoniczny JSON (PolicyDocument), `hash` = SHA-256 tego JSON-a.
CREATE TABLE policy_version (
    version     BIGINT PRIMARY KEY,
    document    TEXT         NOT NULL,
    hash        VARCHAR(64)  NOT NULL,
    author      VARCHAR(100) NOT NULL,
    source      VARCHAR(20)  NOT NULL,
    comment     VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE FUNCTION policy_version_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'policy_version is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER policy_version_no_update_delete
    BEFORE UPDATE OR DELETE ON policy_version
    FOR EACH ROW EXECUTE FUNCTION policy_version_append_only();
