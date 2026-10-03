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
