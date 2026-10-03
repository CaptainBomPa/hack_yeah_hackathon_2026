-- Liczniki budżetu tokenów (docs/deterministic/14-token-budget-quotas.md §9, BUDGET-003/004).
-- `subject` jest dziś zawsze "role:<rola>"; schemat zostawia miejsce na inne wymiary
-- (user/agent/model/global) bez zmiany struktury, gdyby były kiedyś potrzebne.
CREATE TABLE budget_counter (
    subject      VARCHAR(200) NOT NULL,
    period_kind  VARCHAR(10)  NOT NULL,
    period_start DATE         NOT NULL,
    used_tokens  BIGINT       NOT NULL DEFAULT 0,
    reserved     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (subject, period_kind, period_start)
);
