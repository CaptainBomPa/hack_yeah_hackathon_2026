Feature: Invalid policy edits are rejected, the old policy keeps governing
  PolicyValidator checks every edit before it becomes active (docs/policy-management-plan.md) —
  a broken version must never go live. Each case below breaks exactly one validation rule; the
  edit is rejected and the next request is still evaluated against the policy from before the
  attempted edit.

  Background:
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"
    And the model responds with "ok"

  Scenario Outline: An edit that <violation> is rejected
    When the user sends the prompt "Hi"
    Then the response action is "allow"
    When the admin attempts a policy edit that "<violation>"
    Then the policy edit is rejected
    And the user sends the same prompt again
    Then the response action is "allow"

    Examples:
      | violation                                             |
      | uses an invalid role name                              |
      | lists a model twice in the catalog                     |
      | references an unknown model in a role                  |
      | references an unknown guard                            |
      | sets a guard order below the minimum                   |
      | sets a guard order above the maximum                   |
      | sets a threshold above 1                                |
      | sets a negative daily token budget                      |
      | sets max input tokens to zero                           |
      | sets max output tokens above the maximum                |
      | references an unknown recognizer in a block list        |
      | puts the same recognizer in two different lists         |
      | sets contextPrefixWords above the maximum                |
      | sets contextSuffixWords to a negative value              |
      | references an unknown recognizer in the monitor list     |
      | references an unknown recognizer in the disabled list    |
