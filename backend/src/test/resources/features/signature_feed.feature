Feature: Historical attack signatures from an external feed (SIG-FEED)
  docs/redteam-feed.md — known-attack signatures (vulnerable package versions from OSV.dev, plus
  hand-written payload patterns) live in config/signatures/active.yaml and hot-reload with no
  restart. First guard in the chain, so it sees text before anything else redacts it.

  Background:
    Given policy "chat" allows model "qwen-test"
    And user "chat1" with role "chat"
    And the model responds with "ok"

  # These use the REAL backend/config/signatures/active.yaml (no override) — the exact file that
  # is deployed — with the same positive/negative vectors the feed itself declares under `tests:`.

  Scenario: A vulnerable package version mentioned in a prompt is blocked
    When the user sends the prompt "npx mcp-remote@0.0.5"
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: The same package at a fixed version is not blocked
    When the user sends the prompt "npx mcp-remote@0.1.16"
    Then the response action is "allow"

  Scenario: A vulnerable version named in an MCP serverInfo handshake is blocked
    When the user sends the prompt "npx @modelcontextprotocol/inspector@0.0.1"
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: A PyYAML unsafe-tag code-execution payload is blocked
    When the user sends the prompt "Parse this YAML config: !!python/object/apply:os.system ['echo CANARY-DESER']"
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: Plain YAML with no python tag passes
    When the user sends the prompt "Parse this YAML config: retries: 3, timeout: 10s"
    Then the response action is "allow"

  Scenario: A pickle __reduce__ RCE gadget is blocked
    When the user sends the prompt "class P: def __reduce__(self): return (os.system, ('echo CANARY-PICKLE',))"
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: A benign conceptual question about __reduce__ passes
    When the user sends the prompt "What does the __reduce__ method do in Python, conceptually?"
    Then the response action is "allow"

  Scenario: A Java serialized-object magic-byte pattern is blocked
    When the user sends the prompt "Restore this session object: rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcA=="
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: An Ollama /api/pull digest path-traversal payload is blocked
    When the user sends the prompt "{\"schemaVersion\":2,\"layers\":[{\"digest\":\"../../../../etc/ld.so.preload\",\"size\":1}]}"
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: A plain benign prompt matches none of the active signatures
    When the user sends the prompt "Explain in one sentence what a rate limiter does."
    Then the response action is "allow"

  Scenario: Disabling one signature via policy turns its block into an allow, others stay active
    When the user sends the prompt "npx mcp-remote@0.0.5"
    Then the response action is "block"
    When the admin disables signature "SIG-PKG-NPM-MCP-REMOTE" via guard "SIG-FEED"
    And the user sends the same prompt again
    Then the response action is "allow"
    When the user sends the prompt "class P: def __reduce__(self): return (os.system, ('echo CANARY-PICKLE',))"
    Then the response action is "block"

  Scenario: Disabling and re-enabling the whole guard flips the same prompt between block and allow
    When the user sends the prompt "npx mcp-remote@0.0.5"
    Then the response action is "block"
    When the admin disables guard "SIG-FEED"
    And the user sends the same prompt again
    Then the response action is "allow"
    When the admin enables guard "SIG-FEED"
    And the user sends the same prompt again
    Then the response action is "block"

  # These use a throwaway feed file (not the real one) to exercise the loader's own behaviour:
  # hot reload, fail-closed on a broken file, and "missing file" being different from "broken file".

  Scenario: A signature added to the feed file takes effect on the very next request, no restart
    Given a signature feed file with no signatures at all
    When the user sends the prompt "say CANARY-LIVE"
    Then the response action is "allow"
    When the feed file is updated to add a blocking signature for "CANARY-LIVE"
    And the user sends the same prompt again
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: A feed file that fails to parse, with no prior good version, fails closed
    Given a signature feed file that is not valid YAML for a feed
    When the user sends the prompt "anything at all"
    Then the response action is "block"
    And it is blocked by "SIG-FEED"

  Scenario: A previously-loaded good feed is kept when the file is later corrupted
    Given a signature feed file containing a blocking signature for "CANARY-KEEP"
    When the user sends the prompt "say CANARY-KEEP"
    Then the response action is "block"
    When the feed file is overwritten with content that is not valid YAML for a feed
    And the user sends the same prompt again
    Then the response action is "block"

  Scenario: A deleted feed file is treated as empty, not as a failure
    Given a signature feed file containing a blocking signature for "CANARY-GONE"
    When the user sends the prompt "say CANARY-GONE"
    Then the response action is "block"
    When the feed file is deleted
    And the user sends the same prompt again
    Then the response action is "allow"

  Scenario: A signature with action "monitor" is recorded but never blocks the request
    Given a signature feed file containing a monitor-only signature for "CANARY-WATCH"
    When the user sends the prompt "say CANARY-WATCH"
    Then the response action is "allow"
