-- Lokalne konta gatewaya (docs/auth). Hasło jako BCrypt, rola wiąże konto z policy.roles w policy.yaml.
-- Konta tworzy skrypt scripts/add-user.sh; nie ma rejestracji przez API.
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    login         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(50)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
