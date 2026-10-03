-- Serialized admission, persistent token buckets and expiring concurrency leases.
CREATE TABLE IF NOT EXISTS rate_limit_lock (
    id INTEGER PRIMARY KEY
);
INSERT INTO rate_limit_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM rate_limit_lock WHERE id = 1);

CREATE TABLE IF NOT EXISTS rate_limit_bucket (
    user_id UUID PRIMARY KEY,
    tokens DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    full_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_limit_lease (
    request_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS rate_limit_lease_expiry_idx ON rate_limit_lease (expires_at);
CREATE INDEX IF NOT EXISTS rate_limit_lease_user_idx ON rate_limit_lease (user_id, expires_at);
CREATE INDEX IF NOT EXISTS rate_limit_bucket_idle_idx ON rate_limit_bucket (full_at);
