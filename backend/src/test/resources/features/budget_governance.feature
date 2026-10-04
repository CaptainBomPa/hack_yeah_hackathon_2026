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

  Scenario: A prompt estimated at exactly the maximum input size still goes through (boundary is inclusive)
    Given the maximum input size is 3 tokens
    When the user sends the prompt "123456789"
    Then the response action is "allow"

  Scenario: One token more than the maximum input size is rejected
    Given the maximum input size is 2 tokens
    When the user sends the prompt "123456789"
    Then the response action is "block"
    And it is blocked by "budget.input_limit"

  Scenario: A reservation that exactly exhausts the daily cap still goes through (boundary is inclusive)
    Given the maximum output size is 10 tokens
    And role "chat" has a daily budget limit of 13 tokens
    When the user sends the prompt "123456789"
    Then the response action is "allow"

  Scenario: One token less than the exact reservation blocks the request
    Given the maximum output size is 10 tokens
    And role "chat" has a daily budget limit of 12 tokens
    When the user sends the prompt "123456789"
    Then the response action is "block"
    And it is blocked by "budget.daily_cap"

  Scenario: A role without a configured daily budget is never blocked by it (unlimited)
    When the user sends the prompt "Hi"
    Then the response action is "allow"

  Scenario: Two roles have independent daily budgets — one role's exhaustion does not affect the other
    Given policy "agent" allows model "qwen-test"
    And role "chat" has a daily budget limit of 1 tokens
    And role "agent" has a daily budget limit of 20000 tokens
    When the user sends the prompt "Hi"
    Then the response action is "block"
    And it is blocked by "budget.daily_cap"
    When user "agent1" with role "agent"
    And the user sends the prompt "Hi"
    Then the response action is "allow"
