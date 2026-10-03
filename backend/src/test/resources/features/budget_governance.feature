Feature: Per-role token budget
  docs/deterministic/14-token-budget-quotas.md — BUDGET-002 (input size limit) and BUDGET-003
  (daily cap per role, atomic reservation in Postgres/H2).

  Background:
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"
    And the model responds with "ok"

  Scenario: A request within the daily budget goes through
    Given role "chat" has a daily budget limit of 20000 tokens
    When the user sends the prompt "Hi"
    Then the response action is "allow"

  Scenario: An exhausted daily budget blocks the next request (429, not 403 — like a rate limit)
    Given role "chat" has a daily budget limit of 1 tokens
    When the user sends the prompt "Hi"
    Then the response action is "block"
    And it is blocked by "budget.daily_cap"
    And the HTTP status is 429

  Scenario: An oversized prompt is rejected before it ever reaches the daily budget reservation
    Given the maximum input size is 10 tokens
    When the user sends a very long prompt
    Then the response action is "block"
    And it is blocked by "budget.input_limit"
    And the HTTP status is 413
