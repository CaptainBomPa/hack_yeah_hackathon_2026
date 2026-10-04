Feature: Guard chain ordering and multi-guard interaction
  Guards run in ascending `order` (GuardChain#build); a Block stops the chain immediately, a Redact
  rewrites the text and the NEXT guard sees the rewritten version. These scenarios combine two
  different guards (PII-RECOGNIZERS, SEC-GITLEAKS) on the same message to prove composition and
  ordering are real, observable behaviour — not just two independent unit tests.

  Background:
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"

  Scenario: A PESEL and a leaked secret in the same answer are both redacted by different guards in one pass
    Given the model responds with a PESEL and a fake "AWS access key"
    When the user sends the prompt "What do you have on file for me?"
    Then the response action is "redact"
    And the response does not contain "44051401359"
    And the response does not contain a fake "AWS access key"

  Scenario: Guard order decides which guard blocks first, and the order is configurable at runtime
    Given the model responds with "ok"
    When the user sends the prompt "Hi"
    Then the response action is "allow"
    When the admin adds recognizer "PII-007" to guard "PII-RECOGNIZERS"'s "blockRecognizers" list
    And the user sends a prompt containing a card number and a fake "private key"
    Then the response action is "block"
    And it is blocked by "PII-RECOGNIZERS"
    When the admin sets guard "SEC-GITLEAKS"'s order to 50
    And the user sends the same prompt again
    Then the response action is "block"
    And it is blocked by "SEC-GITLEAKS"
