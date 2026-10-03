Feature: Detecting sensitive data in content (PII-RECOGNIZERS)
  docs/deterministic/pii-recognizers.md — a Presidio-style recognizer engine: pattern +
  validator (checksum) + optional context word. PII-001 = Polish PESEL, PII-007 = payment card
  (Luhn validator).

  Background:
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"

  Scenario: A valid PESEL in the prompt gets redacted
    Given the model responds with "ok"
    When the user sends the prompt "My PESEL is 44051401359"
    Then the response action is "redact"

  Scenario: A number that looks like a PESEL but has a wrong checksum passes through (no false positive)
    Given the model responds with "ok"
    When the user sends the prompt "Order number: 44051401358"
    Then the response action is "allow"

  Scenario: A valid PESEL in the model's answer also gets redacted (the control runs on output too)
    Given the model responds with "Your PESEL on file is 44051401359"
    When the user sends the prompt "Check my PESEL on file"
    Then the response action is "redact"
    And the response does not contain "44051401359"

  Scenario: The model "tries to help" by pasting a payment card number into its answer — it gets redacted
    Given the model responds with "Your card is 4111 1111 1111 1111, valid through 12/27"
    When the user sends the prompt "What card number do you have on file for me?"
    Then the response action is "redact"
    And the response does not contain "4111 1111 1111 1111"

  Scenario: A plain, non-sensitive answer passes through unchanged
    Given the model responds with "ok, I can help you sort a list in Python"
    When the user sends the prompt "How do I sort a list?"
    Then the response action is "allow"
