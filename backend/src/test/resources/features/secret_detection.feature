Feature: Detecting leaked secrets in content (SEC-GITLEAKS)
  A Java port of the Gitleaks rule pack (backend/src/main/resources/rules/gitleaks/gitleaks.toml):
  regex + entropy + keyword pre-filter, no NLP. Mirrors PII-RECOGNIZERS — same guard shape
  (redact by default, block/monitor/disable overrides), different data source.

  Background:
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"

  Scenario Outline: A <secret> in the prompt gets redacted
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "<secret>"
    Then the response action is "redact"

    Examples:
      | secret           |
      | AWS access key   |
      | GitHub token     |
      | Slack bot token  |
      | JWT              |

  Scenario: A private key is blocked outright, not redacted (default blockRules)
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "private key"
    Then the response action is "block"
    And it is blocked by "SEC-GITLEAKS"

  Scenario: The model "tries to help" by pasting a token into its answer — it gets redacted too
    Given the model responds with text containing a fake "GitHub token"
    When the user sends the prompt "What token should I use for the deploy script?"
    Then the response action is "redact"

  Scenario: Plain benign English text about secrets (no actual secret) passes through
    Given the model responds with "ok"
    When the user sends a benign prompt about secrets
    Then the response action is "allow"

  Scenario: Mentioning secret variable names without an actual value is not a false positive
    Given the model responds with "ok"
    When the user sends a benign prompt that merely mentions secret variable names
    Then the response action is "allow"

  Scenario: Disabling and re-enabling SEC-GITLEAKS flips the same prompt between allow and redact
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "AWS access key"
    Then the response action is "redact"
    When the admin disables guard "SEC-GITLEAKS"
    And the user sends the same prompt again
    Then the response action is "allow"
    When the admin enables guard "SEC-GITLEAKS"
    And the user sends the same prompt again
    Then the response action is "redact"

  Scenario: Adding a rule to the block list turns its redact into a block
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "GitHub token"
    Then the response action is "redact"
    When the admin adds rule "github-pat" to guard "SEC-GITLEAKS"'s "blockRules" list
    And the user sends the same prompt again
    Then the response action is "block"
    And it is blocked by "SEC-GITLEAKS"

  Scenario: Clearing the default block list turns a private-key block into a redact
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "private key"
    Then the response action is "block"
    When the admin clears guard "SEC-GITLEAKS"'s "blockRules" list
    And the user sends the same prompt again
    Then the response action is "redact"

  Scenario: Disabling a single rule turns its redact into an allow
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "AWS access key"
    Then the response action is "redact"
    When the admin adds rule "aws-access-token" to guard "SEC-GITLEAKS"'s "disabledRules" list
    And the user sends the same prompt again
    Then the response action is "allow"

  Scenario: Two different secret types in the same answer both get redacted
    Given the model responds with text containing a fake "AWS access key" and a fake "GitHub token"
    When the user sends the prompt "What do you have on file for me?"
    Then the response action is "redact"
    And the response does not contain a fake "AWS access key"
    And the response does not contain a fake "GitHub token"

  Scenario: Toggling a rule between the default redact and an explicit block and back
    Given the model responds with "ok"
    When the user sends a prompt containing a fake "GitHub token"
    Then the response action is "redact"
    When the admin adds rule "github-pat" to guard "SEC-GITLEAKS"'s "blockRules" list
    And the user sends the same prompt again
    Then the response action is "block"
    When the admin clears guard "SEC-GITLEAKS"'s "blockRules" list
    And the user sends the same prompt again
    Then the response action is "redact"
