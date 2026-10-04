Feature: Model allowlist and per-role access policy
  Covers VISION.md §4.A.12 (model allowlist) and policy.model-access (docs/auth).

  Scenario: A role can use a model it is granted in the policy
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"
    And the model responds with "hi there"
    When the user sends the prompt "How are you?"
    Then the response action is "allow"
    And the HTTP status is 200

  Scenario: A model outside the catalog is rejected
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"
    When the user sends the prompt "test" to model "unknown-model"
    Then the response action is "block"
    And it is blocked by "model.allowlist"
    And the HTTP status is 403

  Scenario: A role without access to a given model is rejected
    Given policy "chat" allows model "qwen-test"
    And the catalog also has model "other-model"
    And user "chat1" with role "chat"
    When the user sends the prompt "test" to model "other-model"
    Then the response action is "block"
    And it is blocked by "policy.model-access"
    And the HTTP status is 403

  Scenario: An unauthenticated user is rejected before any control runs
    Given policy "chat" allows model "qwen-test"
    When an unauthenticated user sends the prompt "test"
    Then the HTTP status is 401
    And it is blocked by "auth.required"

  Scenario: A role that was never configured in the policy at all is denied (fail-closed)
    Given policy "chat" allows model "qwen-test"
    And user "ghost" with role "nobody"
    When the user sends the prompt "test" to model "qwen-test"
    Then the response action is "block"
    And it is blocked by "policy.model-access"

  Scenario: A role with several explicit models can use any one of them
    Given policy "chat" allows model "model-a"
    And policy "chat" allows model "model-b"
    And user "chat1" with role "chat"
    And the model responds with "ok"
    When the user sends the prompt "test" to model "model-a"
    Then the response action is "allow"
    When the user sends the prompt "test" to model "model-b"
    Then the response action is "allow"

  Scenario: A wildcard role ("*") can use a model it was never explicitly granted
    Given the catalog also has model "any-model"
    And policy "super" allows any model
    And user "root" with role "super"
    And the model responds with "ok"
    When the user sends the prompt "test" to model "any-model"
    Then the response action is "allow"
