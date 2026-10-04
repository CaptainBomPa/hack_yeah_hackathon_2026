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

  # Every recognizer besides PESEL/card (PII-002..PII-006, PII-008) — docs/deterministic/pii-recognizers.md.
  # Values verified against the recognizer engine's own unit tests (PiiRecognizerGuardTest).
  Scenario Outline: <recognizer> — <case>
    Given the model responds with "ok"
    When the user sends the prompt "<prompt>"
    Then the response action is "<action>"

    Examples:
      | recognizer | case                                  | prompt                                        | action |
      | NIP        | valid, no separators                  | NIP 1234563218                                | redact |
      | NIP        | valid, dashed                         | NIP: 123-456-32-18                            | redact |
      | NIP        | valid, PL prefix                      | Invoice PL1234563218                          | redact |
      | NIP        | wrong checksum, even with context     | NIP is 1234563219                             | allow  |
      | REGON      | valid, with context word              | Company REGON: 123456785                      | redact |
      | REGON      | valid digits, no context word         | Product code 123456785                        | allow  |
      | ID card    | valid, with context word              | Identity document number: ABA300000           | redact |
      | ID card    | valid format, no context word         | SKU ABA300000                                 | allow  |
      | ID card    | wrong checksum, even with context     | identity ABA300001                            | allow  |
      | Email      | valid address                         | Email jan.kowalski@example.pl.                | redact |
      | Phone      | international, no context needed      | Call: +48 601 234 567                         | redact |
      | Phone      | grouped, with context word            | tel. 601-234-567                              | redact |
      | Phone      | bare 9 digits, with context word      | my phone 601234567                            | redact |
      | Phone      | bare 9 digits, no context word        | Order 601234567 sent                          | allow  |
      | IBAN       | full IBAN with PL prefix              | Transfer to PL61 1090 1014 0000 0712 1981 2874| redact |
      | IBAN       | bare NRB (no PL prefix, no spaces)    | Account 61109010140000071219812874            | redact |
      | IBAN       | NRB with one digit changed (mod-97)   | Account 61109010140000071219812875            | allow  |
      | IBAN       | NRB grouped (2 then 4s), no PL prefix | Account 61 1090 1014 0000 0712 1981 2874      | redact |
      | NIP        | valid, PL prefix with a space         | Invoice PL 1234563218                         | redact |
      | ID card    | valid, context word AFTER the value   | Number ABA300000 in the identity document.    | redact |
      | Card       | valid, dash-separated                 | Card 4111-1111-1111-1111 on file              | redact |
      | Email      | invalid TLD (digits, not letters)     | Email jan@example.123, thanks.                | allow  |
      | Phone      | grouped, no context word              | Order number: 601-234-567                     | allow  |
      | PESEL      | valid, no space before punctuation    | PESEL:44051401359.                            | redact |
