-- Która wersja polityki podjęła decyzję (docs/policy-management-plan.md §3). Starsze rekordy mają NULL;
-- kolumna wchodzi do hasha łańcucha tylko gdy jest ustawiona, więc ich weryfikacja się nie zmienia.
-- Trigger append-only z V3 blokuje UPDATE/DELETE wierszy, nie zmianę schematu.
ALTER TABLE audit_event ADD COLUMN policy_version BIGINT;
