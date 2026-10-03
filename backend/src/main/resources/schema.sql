-- Tabela spoza JPA (BudgetService używa czystego JdbcTemplate, nie Hibernate — VISION.md:
-- atomowe liczniki budżetu nie mogą iść przez warstwę ORM). Dla profilu `local` (H2) Spring Boot
-- odpala ten plik automatycznie przy starcie (spring.sql.init.mode=embedded, wartość domyślna);
-- dla `prod` (Postgres) to samo robi Flyway (db/migration/V3__budget_counter.sql) — ten plik się
-- wtedy NIE uruchamia, bo inicjalizacja schema.sql dotyczy tylko baz embedded.
CREATE TABLE IF NOT EXISTS budget_counter (
    subject      VARCHAR(200) NOT NULL,
    period_kind  VARCHAR(10)  NOT NULL,
    period_start DATE         NOT NULL,
    used_tokens  BIGINT       NOT NULL DEFAULT 0,
    reserved     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (subject, period_kind, period_start)
);
