# Zbiorczy rejestr źródeł (docs/deterministic)

Zdeduplikowany rejestr źródeł z sekcji „15. Sources" plików case `01`–`27`. Nie dodano żadnych URL-i spoza tych plików.

Konwencje:

- **Case'y** — numery plików `NN-*.md`, które powołują się na dane źródło.
- **Data** — wyłącznie data podana w pliku case; `n/d` = brak daty (`b.d.`, „bieżąca", „dostęp 2026" traktowane jako brak daty publikacji, chyba że wpisano jako adnotację).
- **Typ dowodu** — tagi z case'ów: `RESEARCH`, `MITIGATION`, `CONFIRMED-VULN`, `REAL-ATTACK`, `POC`, `VENDOR-CLAIM`, `THEORETICAL`; „dok." = dokumentacja/standard bez tagu w case'ie.
- Adnotacje „niezweryfikowane" / „wtórne" przeniesione z case'ów (pełna lista w sekcji „Znane luki weryfikacji").
- Źródło występujące w kilku kategoriach wpisano w jednej (najbardziej właściwej), z odnośnikiem tam, gdzie to potrzebne.

## 1. OWASP

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| OWASP LLM01:2025 Prompt Injection | https://genai.owasp.org/llmrisk/llm01-prompt-injection/ | 2025 | Prompt injection (niepobrane, **niezweryfikowane**) | 21, 26 | `RESEARCH` |
| OWASP LLM02:2025 Sensitive Information Disclosure | https://genai.owasp.org/llmrisk/llm022025-sensitive-information-disclosure/ | 2025 | Ujawnianie danych wrażliwych / PII | 01, 19 | `MITIGATION` |
| OWASP LLM05:2025 Improper Output Handling | https://genai.owasp.org/llmrisk/llm052025-improper-output-handling/ | 2025 (odczytano) | Obsługa wyjścia modelu, kanały exfil | 20 | `MITIGATION` |
| OWASP LLM06:2025 Excessive Agency | https://genai.owasp.org/llmrisk/llm062025-excessive-agency/ | 2025 | Nadmierna sprawczość agenta, autoryzacja, limity wywołań | 04, 15 | `RESEARCH` / `MITIGATION` |
| OWASP LLM07:2025 System Prompt Leakage | https://genai.owasp.org/llmrisk/llm072025-system-prompt-leakage/ | 2025 | Wyciek system promptu | 19 | `MITIGATION` |
| OWASP LLM10:2025 Unbounded Consumption | https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/ (warianty URL: https://genai.owasp.org/llmrisk/llm10/ ; https://genai.owasp.org/llmrisk/LLM10/) | 2025 (pobrane 2026-10-03) | DoS, rate limiting, budżety tokenów, pętle agentów, limity wejścia | 13, 14, 15, 16, 18 | `RESEARCH` / `MITIGATION` |
| OWASP Top 10 for LLM Applications 2025 (strona główna) | https://genai.owasp.org/llm-top-10/ | 2025 | Lista 10 pozycji (LLM02/05/06/08); w 09 zweryfikowana tylko przez wyniki wyszukiwania, w 06 **niezweryfikowane** | 06, 09, 11 | `MITIGATION` / `RESEARCH` |
| OWASP Top 10 for Agentic Applications 2026 | https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026 | n/d (edycja 2026; data **niezweryfikowana**) | Ryzyka agentowe ASI01–ASI10 | 15 | `RESEARCH` |
| Giskard — streszczenie OWASP Agentic Top 10 2026 (ASI03) | https://www.giskard.ai/knowledge/owasp-top-10-for-agentic-application-2026 | 2025-12 | Uwierzytelnianie/autoryzacja agentów (źródło wtórne) | 03, 04 | `RESEARCH` (wtórne) |
| Modulos — mapowanie OWASP Agentic Top 10 | https://docs.modulos.ai/frameworks/owasp-top-10-agentic | n/d | Nazwy ASI01–ASI10, opis ASI07/ASI08 (wtórne) | 15 | `RESEARCH` (wtórne) |
| Galileo — OWASP ASI02 Tool Misuse | https://galileo.ai/blog/owasp-agentic-ai-asi02-tool-misuse | n/d | Mapowanie ASI02 -> T2/T4/T16 (wtórne, nie sprawdzone w oryginale taksonomii) | 15 | `RESEARCH` (wtórne) |
| Giskard glossary — OWASP ASI02 | https://www.giskard.ai/glossary/owasp-asi02-tool-misuse-and-exploitation-nu0lr | n/d | j.w. | 15 | `RESEARCH` (wtórne) |
| OWASP MCP Top 10 | https://owasp.org/www-project-mcp-top-10/ | 2025 (beta) | Ryzyka MCP: allowlista, walidacja argumentów, integralność narzędzi | 07, 08, 12 | `RESEARCH` |
| OWASP SSRF Prevention Cheat Sheet | https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html | n/d | Obrona przed SSRF | 10 | `MITIGATION` |
| OWASP Path Traversal | https://owasp.org/www-community/attacks/Path_Traversal | n/d | Ograniczenia ścieżek plików | 09 | `MITIGATION` |
| OWASP Logging Cheat Sheet | brak URL w case'ie | n/d | Logowanie/audyt — powołane z wiedzy, **niepobrane, niezweryfikowane** | 27 | n/d |

## 2. MITRE

**Brak.** Żaden z plików case nie zawiera w sekcji 15 źródeł MITRE (ATT&CK, ATLAS, CWE jako strona MITRE, CAPEC). Jedyne odniesienie pokrewne: CWE-117 (log injection) jest cytowane wyłącznie pośrednio przez OpenSSF Secure Coding Guide i CodeQL (patrz sekcja 6, case 27), nie przez MITRE.

## 3. NIST

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| NIST SP 800-92 (Guide to Computer Security Log Management) | https://csrc.nist.gov/publications/detail/sp/800-92/final | 2006 | Zarządzanie logami / audyt | 27 | `RESEARCH` |
| NIST SP 800-53 AU — omówienie wtórne (Coralogix) | https://coralogix.com/guides/nist-sp-800-53-audit-logging-au-controls/ | n/d | Kontrole audytowe AU (źródło wtórne, nie NIST) | 27 | `RESEARCH` (wtórne) |
| NVD CVE-2015-9235 | https://nvd.nist.gov/vuln/detail/CVE-2015-9235 | n/d | jsonwebtoken — obejście weryfikacji JWT | 03 | `CONFIRMED-VULN` |
| NVD CVE-2025-55284 | https://nvd.nist.gov/vuln/detail/cve-2025-55284 | VIII 2025 | Claude Code — exfiltracja przez DNS | 20 | `CONFIRMED-VULN` |
| NVD CVE-2024-39720 | https://nvd.nist.gov/vuln/detail/CVE-2024-39720 | n/d | Ollama — sygnatury IOC | 22 | `CONFIRMED-VULN` |
| NVD CVE-2025-32434 | https://nvd.nist.gov/vuln/detail/CVE-2025-32434 | n/d | PyTorch `torch.load` — deserializacja | 23 | `CONFIRMED-VULN` |
| NVD CVE-2025-54886 | https://nvd.nist.gov/vuln/detail/CVE-2025-54886 | n/d | skops — deserializacja | 23 | `CONFIRMED-VULN` |
| NVD CVE-2023-36258 | https://nvd.nist.gov/vuln/detail/CVE-2023-36258 | n/d | LangChain — RCE (deserializacja) | 23 | `CONFIRMED-VULN` |

Uwaga: z NIST pochodzą tylko SP 800-92 i strony NVD (NIST NVD). Nie ma w case'ach AI RMF, NIST AI 600-1, SP 800-53 (oryginał), SP 800-218 ani SP 800-63 — brak, nie dopisywano.

## 4. CISA

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| CISA KEV feed (Known Exploited Vulnerabilities, JSON) | https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json | n/d | Źródło danych do sygnatur IOC/CVE | 22 | `MITIGATION` |

Jest to jedyne źródło CISA w całym zbiorze (brak biuletynów/advisories CISA).

## 5. CVE / advisories

Strony NVD są w sekcji 3 (NIST). Poniżej zgrupowano po identyfikatorze CVE; wszystkie z tagiem `CONFIRMED-VULN`, o ile nie zaznaczono inaczej.

| CVE / nazwa | Link(i) | Data | Czego dotyczy | Case'y |
|---|---|---|---|---|
| CVE-2026-35030 (LiteLLM) | https://research.averlon.ai/vulnerability-intelligence/cve/CVE-2026-35030 ; https://docs.litellm.ai/blog/security-hardening-april-2026 | 2026 | Obejście auth/izolacji w LiteLLM | 03, 04, 06 |
| CVE-2026-42208 (LiteLLM pre-auth SQLi) | https://labs.cloudsecurityalliance.org/research/csa-research-note-litellm-pre-auth-sqli-20260428/ | 2026-04-28 | Pre-auth SQL injection | 03, 05 |
| CVE-2025-0330 (LiteLLM) | https://osv.dev/vulnerability/CVE-2025-0330 | n/d | Wyciek w logach | 27 |
| CVE-2024-5225 / GHSA-h6m6-jj8v-94jj | https://vulnerability.cert.dk/vuln/ghsa-h6m6-jj8v-94jj | n/d | Audyt/logi — **zweryfikować w NVD** | 27 |
| CVE-2025-49596 (MCP Inspector) | https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html ; https://asec.ahnlab.com/en/88812/ ; https://thehackernews.com/2025/07/critical-vulnerability-in-anthropics.html ; https://www.sentinelone.com/vulnerability-database/cve-2025-49596/ ; https://www.oligo.security/blog/critical-rce-vulnerability-in-anthropic-mcp-inspector-cve-2025-49596 | 2025-06/07 | RCE w MCP Inspector (auth, command injection) | 03, 07, 08, 11 |
| CVE-2025-6514 (mcp-remote) | https://research.jfrog.com/vulnerabilities/mcp-remote-command-injection-rce-jfsa-2025-001290844/ ; https://www.wiz.io/vulnerability-database/cve/cve-2025-6514 ; https://www.zealynx.io/resources/ai-security-hacks-library/mcp-remote-oauth-shell-injection-cve-2025-6514 (wtórne, potwierdzić w NVD/JFrog) ; https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html ; https://sdtimes.com/mcp/jfrog-finds-mcp-related-vulnerability-highlighting-need-for-stronger-focus-on-security-in-mcp-ecosystem/ | 2025-07 | Command injection / RCE w mcp-remote | 03, 08, 11, 22, 23, 25 |
| CVE-2025-53109/53110 (EscapeRoute, mcp-server-filesystem) | https://cymulate.com/blog/cve-2025-53109-53110-escaperoute-anthropic/ | 2025 | Ucieczka z sandboxa ścieżek (naprawione: 2025.7.1 / 0.6.3) | 07, 08, 09 |
| CVE-2025-68143/68144/68145 (mcp-server-git) | https://thehackernews.com/2026/01/three-flaws-in-anthropic-mcp-git-server.html ; https://www.csoonline.com/article/4119571/three-vulnerabilities-found-in-anthropic-git-mcp-server-could-let-attackers-tamper-with-llms.html | 2026-01 | Trzy luki w serwerze Git MCP | 08, 09, 11 |
| CVE-2025-54794/54795 (InversePrompt, Claude Code) | https://www.cymulate.com/blog/cve-2025-547954-54795-claude-inverseprompt/ ; https://www.wiz.io/vulnerability-database/cve/cve-2025-54794 | 2025 | Ominięcie ograniczeń ścieżek / komend | 09, 11 |
| CVE-2025-53967 (Figma MCP) | https://thehackernews.com/2025/10/severe-figma-mcp-vulnerability-lets.html | 2025-10 | Wstrzyknięcie komend w argumentach | 08 |
| CVE-2025-54136 (MCPoison, Cursor) | https://research.checkpoint.com/2025/cursor-vulnerability-mcpoison/ ; https://thehackernews.com/2025/08/cursor-ai-code-editor-vulnerability.html | 2025-08 | Podmiana zaufanej konfiguracji MCP | 12 |
| CVE-2025-54135 (CurXecute, Cursor) | https://www.tenable.com/cve/CVE-2025-54135 ; https://www.securityweek.com/several-vulnerabilities-patched-in-ai-code-editor-cursor/ | 2025-08 | RCE przez wstrzyknięcie | 11 |
| CVE-2025-53773 (Copilot / Visual Studio) | https://www.wiz.io/vulnerability-database/cve/cve-2025-53773 ; https://security-tracker.debian.org/tracker/CVE-2025-53773 | 2025-08 | RCE przez prompt injection | 11 |
| CVE-2025-32711 (EchoLeak, M365 Copilot, CVSS 9.3) | https://thehackernews.com/2025/06/zero-click-ai-vulnerability-exposes.html ; https://socprime.com/blog/cve-2025-32711-zero-click-ai-vulnerability/ ; https://simonwillison.net/2025/Jun/11/echoleak/ | 2025-06 | Zero-click exfiltracja danych (źródła wtórne; strona Aim Security 403) | 17, 19, 20 |
| CVE-2025-55284 (Claude Code DNS exfil) | https://stack.watch/vuln/CVE-2025-55284 (+ NVD, sekcja 3) | VIII 2025 | Exfiltracja przez DNS | 02 (agregat Akto), 20 |
| CVE-2025-64110 (agregat Akto) | patrz Akto w sekcji 8 | 2026 | Asystenci kodowania — **niezweryfikowane u źródła pierwotnego** (NVD nie zwrócił treści) | 02 |
| CVE-2024-37032 (Probllama, Ollama) | https://wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032 ; https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032 | 2024-06 | RCE / path traversal w Ollama | 03, 05, 22, 24 |
| Oligo „More Models More ProbLLMs" | https://oligo.security/blog/more-models-more-probllms | n/d | Kolejne luki w Ollama | 22, 24 |
| CVE-2024-28224 (Ollama DNS rebinding, NCC Group) | https://www.nccgroup.com/research/technical-advisory-ollama-dns-rebinding-attack-cve-2024-28224/ | 2024-04 | DNS rebinding | 10 |
| CVE-2025-0315 (Ollama GGUF) | https://access.redhat.com/security/cve/CVE-2025-0315 | 2025 | Nieograniczona pamięć (zweryfikowane przez wyniki wyszukiwania) | 18 |
| CVE-2025-41235 (Spring Cloud Gateway) | https://advisories.gitlab.com/maven/org.springframework.cloud/spring-cloud-gateway-server/CVE-2025-41235/ | 2025-05/07 | Wyciek nagłówków (auth) | 03, 04 |
| CVE-2026-47825 | https://www.herodevs.com/vulnerability-directory/cve-2026-47825 | n/d | Źródło wtórne | 03 |
| CVE-2022-21449 (Psychic Signatures, Java) | https://neilmadden.blog/2022/04/19/cve-2022-21449-psychic-signatures-in-java/ | 2022-04-19 | Obejście weryfikacji ECDSA/JWT | 03 |
| CVE-2025-65958 (Open WebUI) | https://advisories.gitlab.com/pypi/open-webui/CVE-2025-65958/ | n/d | SSRF | 10 |
| CVE-2026-45400 (Open WebUI) | https://advisories.gitlab.com/pypi/open-webui/CVE-2026-45400/ ; https://mend.io/vulnerability-database/CVE-2026-45400/ | n/d | SSRF | 10 |
| CVE-2026-45347 (Open WebUI) | https://advisories.gitlab.com/pypi/open-webui/CVE-2026-45347/ | n/d | SSRF | 10 |
| CVE-2025-59527 (Flowise) | https://db.gcve.eu/vuln/cve-2025-59527 | n/d | SSRF | 10 |
| CVE-2023-46229 (LangChain) | https://advisories.gitlab.com/pkg/pypi/langchain/CVE-2023-46229/ | n/d | SSRF | 10 |
| CVE-2026-26013 (langchain-core) | https://advisories.gitlab.com/pypi/langchain-core/CVE-2026-26013/ | n/d | SSRF | 10 |
| CVE-2026-58196 (ToolHive) | https://mend.io/vulnerability-database/CVE-2026-58196/ | n/d | SSRF | 10 |
| CVE-2024-4032 (Python ipaddress) | https://mail.python.org/archives/list/security-announce@python.org/thread/NRUHDUS2IV2USIZM2CVMSFL6SCKU3RZA/ | n/d | Błędna klasyfikacja adresów prywatnych/publicznych | 10 |
| CVE-2023-36258 (LangChain) | https://advisories.gitlab.com/pypi/langchain/CVE-2023-36258/ (+ NVD, sekcja 3) | n/d | RCE | 11, 23 |
| CVE-2023-39660 / CVE-2024-12366 (PandasAI) | https://www.wiz.io/vulnerability-database/cve/cve-2023-39660 ; https://cvefeed.io/vuln/detail/CVE-2024-12366 | n/d | RCE przez LLM | 11 |
| CVE-2023-44467 (langchain-experimental) | https://advisories.gitlab.com/pkg/pypi/langchain-experimental/CVE-2023-44467/ | n/d | RCE | 23 |
| CVE-2025-47277 (vLLM) | https://www.wiz.io/vulnerability-database/cve/cve-2025-47277 | n/d | Deserializacja | 22, 23 |
| CVE-2025-32434 (PyTorch) | https://www.kaspersky.com/blog/vulnerability-in-pytorch-framework/53311/ (+ NVD, sekcja 3) | n/d | Deserializacja `torch.load` | 23 |
| Keras safe_mode bypass (JFrog) | https://jfrog.com/blog/keras-safe_mode-bypass-vulnerability/ | n/d | Deserializacja | 23 |
| CVE-2025-68664 (LangGrinch, langchain-core) | https://thehackernews.com/2025/12/critical-langchain-core-vulnerability.html | 2025-12 | Deserializacja | 23 |
| CVE-2022-1471 (SnakeYAML) | https://www.veracode.com/blog/resolving-cve-2022-1471-snakeyaml-20-release-0/ | n/d | Deserializacja YAML | 23 |
| picklescan zero-days / CVE-2025-1716 | https://www.infosecurity-magazine.com/news/picklescan-flaws-expose-ai-supply ; https://osv.dev/vulnerability/CVE-2025-1716 | n/d | Ominięcie skanera pickle | 23, 24 |
| GGUF/GGML (Databricks) | https://www.databricks.com/blog/ggml-gguf-file-format-vulnerabilities | n/d | Luki parserów GGUF | 24 |
| CVE-2025-59152 (Litestar) | https://corgea.com/advisories/vulnerabilities/CVE-2025-59152 | 2025 | Obejście rate limitu przez X-Forwarded-For | 13 |
| CVE-2026-55501 (9router) | https://securelayer7.net/lab/cve-2026-55501-9router-x-forwarded-for-rate-limit-bypass | 2026 | Obejście rate limitu przez XFF (źródło wtórne) | 13 |
| CVE-2025-52999 (jackson-core) | https://www.tenable.com/cve/CVE-2025-52999 | 2025 | Głębokość zagnieżdżenia JSON | 18 |
| HTTP/2 Rapid Reset | https://cloud.google.com/blog/products/identity-security/how-it-works-the-novel-http2-rapid-reset-ddos-attack | 2023-10-10 | DoS HTTP/2 | 18 |
| CVE-2026-47244 (Netty HTTP/2) | https://www.sentinelone.com/vulnerability-database/cve-2026-47244/ | 2026-06-19 | Wyczerpanie strumieni (przez agregator) | 18 |
| CVE-2026-53659 (http4k) | https://securelayer7.net/lab/cve-2026-53659-http4k-gzip-decompression-bomb-dos | 2026 | Gzip bomb (ze snippetów) | 18 |
| CVE-2026-28435 (cpp-httplib) | https://db.gcve.eu/vuln/CVE-2026-28435 | 2026 | DoS (ze snippetu) | 18 |
| CVE-2026-28975 (SwiftNIO) | https://feedly.com/cve/CVE-2026-28975 | 2026 | Obejście limitu dekompresji (częściowo; powiązanie ze Spring niepotwierdzone) | 18 |
| Claude Code issues (rekurencyjne sub-agenty) | https://claudeissues.com/issue/68110-general-purpose-sub-agents-recursively-spawn-unbounded-child-agents-causing-expo ; https://github.com/anthropics/claude-code/issues/68619 | 2026 (otwarte 2026-06-13) | Zgłoszenia użytkowników; wartości liczbowe niezweryfikowane (agregator) | 15 |
| Claude Code #35166, #59318 (mirror) | https://claudeissues.com/issue/35166-bug-claude-code-sends-repeated-requests-hundreds-of-times-without-stopping ; https://claudeissues.com/issue/59318-agent-repeatedly-calls-the-same-tool-in-an-infinite-loop-during-exploratory-rese | 2026-03/04 (#35166) | Pętle wywołań; kwota niezweryfikowana | 16 |
| Cursor forum — pętla czytania pliku | https://forum.cursor.com/t/agent-enters-infinite-loop-re-reading-the-exact-same-file-range-with-auto-mode/170975 | n/d | Zgłoszenie użytkownika (niepełna lektura) | 15 (`CONFIRMED-VULN`), 16 |

Typ dowodu dla wszystkich wierszy powyżej: `CONFIRMED-VULN` (w 18 część „ze snippetów"/„częściowo").

## 6. Dokumentacja vendorów i standardy

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| MCP Authorization spec | https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization | 2025-06-18 (pobrano 2026-10) | Autoryzacja MCP | 03, 04 | `RESEARCH` |
| MCP Security Best Practices (spec) | https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices | 2025-11-25 | Bezpieczeństwo MCP, sesje, scope | 03, 04, 06, 27 | `RESEARCH` |
| MCP Security Best Practices (tutorial) | https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices | bieżąca | j.w. | 07, 08, 12 | `MITIGATION` |
| MCP Tools spec | https://modelcontextprotocol.io/specification/2025-06-18/server/tools | 2025-06-18 | Narzędzia MCP | 07, 08, 12 | `MITIGATION` |
| MCP Resources spec | https://modelcontextprotocol.io/specification/2025-06-18/server/resources | 2025-06-18 | Zasoby MCP | 07 | `MITIGATION` |
| RFC 8725 (JWT BCP) | https://datatracker.ietf.org/doc/html/rfc8725 | 2020-02 | JWT best practices | 03 | `MITIGATION` |
| RFC 8693 / 8705 / 8707 | brak URL w case'ie | n/d | Token Exchange, mTLS-bound tokens, Resource Indicators — powołane z MCP spec/wiedzy, **niezweryfikowane** | 03 | n/d |
| RFC 6585 §4 | https://www.rfc-editor.org/rfc/rfc6585#section-4 | 2012 | 429 Too Many Requests | 13 | dok. (standard) |
| IETF draft-ietf-httpapi-ratelimit-headers-11 | https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/ | 2026-05-23 | Nagłówki RateLimit (draft, nie RFC) | 13 | dok. (draft) |
| RFC 8785 (JCS), W3C Trace Context | brak URL w case'ie | n/d | Kanonizacja JSON / trace — powołane z wiedzy, **niezweryfikowane** | 27 | n/d |
| LiteLLM Model Access | https://docs.litellm.ai/docs/proxy/model_access | n/d | Allowlista modeli | 05 | `RESEARCH` / dok. |
| LiteLLM Proxy — budgets and rate limits | https://docs.litellm.ai/docs/proxy/users | n/d | Budżety i limity | 13, 14 | `VENDOR-CLAIM` / `MITIGATION` |
| Kong AI Gateway (routing, consumers) | https://developer.konghq.com/cookbooks/basic-llm-routing/ ; https://developer.konghq.com/operator/get-started/ai-gateway/consumers/ | n/d | Allowlista modeli w gateway | 05 | `VENDOR-CLAIM` |
| Kong MCP ACL | https://developer.konghq.com/ai-gateway/v1/mcp/use-access-controls-for-mcp-tools/ | n/d | ACL narzędzi MCP | 04 | `VENDOR-CLAIM` / dok. |
| Cerbos — porównanie silników (vs OPA/Cedar/OpenFGA) | https://www.cerbos.dev/comparisons | n/d | Autoryzacja; autor jest dostawcą Cerbos | 04 | `VENDOR-CLAIM` |
| Portkey — budget and rate limit | https://portkey.ai/docs/product/administration/enforce-budget-and-rate-limit | n/d | Budżety (wynik wyszukiwania) | 14 | `VENDOR-CLAIM` |
| Ollama FAQ | https://docs.ollama.com/faq | dostęp 2026-10-03 | OLLAMA_NUM_PARALLEL, OLLAMA_MAX_QUEUE=512, 503, kontekst | 13, 18 | `VENDOR-CLAIM` / dok. |
| Ollama API usage metrics | https://docs.ollama.com/api/usage | n/d | Metryki zużycia tokenów | 14 | dok. |
| Ollama OpenAI-compat (`stream_options.include_usage`) | https://ollama.readthedocs.io/en/openai/ | n/d | Wtórny opis; **niezweryfikowane na naszej wersji** | 14 | n/d |
| SSD Nodes — Ollama `num_predict` | https://www.ssdnodes.com/learn/ollama-num-predict-explained | n/d | Limit generowania (wtórne) | 14 | `RESEARCH` |
| OpenAI — Rate limits | https://developers.openai.com/api/docs/guides/rate-limits | bieżąca | Wzorce limitów | 13 | `VENDOR-CLAIM` |
| Spring Cloud Gateway — RequestRateLimiter | https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html | bieżąca | Rate limiting w SCG | 13 | `VENDOR-CLAIM` |
| Spring Cloud Gateway — filtr `RequestSize` | brak działającego URL (pobranie dało 404) | n/d | Limit rozmiaru żądania — **niezweryfikowane** | 18 | n/d |
| Reactor Netty — HTTP server reference | https://projectreactor.io/docs/netty/release/reference/http-server.html | dostęp 2026-10-03 | Timeouty/limity serwera | 18 | `MITIGATION` |
| Baeldung — WebFlux DataBufferLimitException | https://www.baeldung.com/spring-webflux-databufferlimitexception | dostęp 2026-10-03 | max-in-memory-size (częściowo) | 18 | `MITIGATION` |
| Resilience4j — RateLimiter | https://resilience4j.readme.io/docs/ratelimiter | bieżąca | Rate limiting | 13 | `VENDOR-CLAIM` |
| Resilience4j — Bulkhead | https://resilience4j.readme.io/docs/bulkhead | dostęp 2026-10-03 | Limity współbieżności | 18 | `MITIGATION` |
| cPanel — mitygacja Slowloris (`mod_reqtimeout`) | https://docs.cpanel.net/knowledge-base/security/how-to-mitigate-slowloris-attacks/ | dostęp 2026-10-03 | Slowloris | 18 | `MITIGATION` |
| Jackson-core release notes (StreamReadConstraints) | https://github.com/FasterXML/jackson-core/blob/2.x/release-notes/VERSION-2.x | dostęp 2026-10-03 | Limity parsowania JSON | 18 | `MITIGATION` |
| PostgreSQL — Transaction Isolation | https://www.postgresql.org/docs/current/transaction-iso.html | n/d | Read Committed, check-then-act | 14 | dok. |
| AWS IAM — unique ID prefixes | https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_identifiers.html | 2026-10-03 | Prefiksy kluczy AWS | 02 | dok. |
| GitHub — supported secret scanning patterns | https://docs.github.com/en/code-security/secret-scanning/introduction/supported-secret-scanning-patterns | 2026-10-03 | Wzorce sekretów | 02 | dok. |
| Azure Storage account keys | https://learn.microsoft.com/en-us/azure/storage/common/storage-account-keys-manage | aktualizacja 2026-08 | Klucze 512-bit | 02 | dok. |
| Semgrep Secrets | https://docs.semgrep.dev/semgrep-secrets/conceptual-overview | 2026-10-03 | Wykrywanie sekretów | 02 | `VENDOR-CLAIM` |
| Microsoft Purview — Poland national ID (PESEL) SIT | https://learn.microsoft.com/en-gb/purview/sit-defn-poland-national-id | dostęp 2026 | PESEL | 01 | `VENDOR-CLAIM` |
| Google Cloud DLP — InfoType | https://docs.cloud.google.com/dlp/docs/reference/rest/v2/InfoType | n/d | Typy PII | 01 | `VENDOR-CLAIM` |
| Presidio — supported entities / analyzer | https://presidio.dataprivacystack.org/supported_entities/ ; https://presidio.dataprivacystack.org/analyzer/ | dostęp 2026 | Detekcja PII | 01 | `VENDOR-CLAIM` |
| PESEL — Wikipedia | https://en.wikipedia.org/wiki/PESEL | dostęp 2026 | Struktura PESEL, algorytm 1-3-7-9 (źródło ogólnoencyklopedyczne) | 01 | `RESEARCH` |
| mBank — dowód osobisty | https://www.mbank.pl/artykuly/dowod-osobisty/ | n/d | Format dowodu — **niezweryfikowane w źródle pierwotnym** | 01 | n/d |
| polishdata.eu — walidatory NIP / REGON | https://polishdata.eu/validators/nip ; https://polishdata.eu/validators/regon | n/d | Walidacja NIP/REGON | 01 | `RESEARCH` |
| python-stdnum (REGON) | https://arthurdejong.org/git/python-stdnum/plain/stdnum/pl/regon.py?h=2.0 | n/d | Implementacja referencyjna | 01 | `RESEARCH` |
| django-localflavor (PL) | https://django-localflavor.readthedocs.io/en/1.1/_modules/localflavor/pl/forms/ | n/d | Implementacja referencyjna | 01 | `RESEARCH` |
| OpenSSF Secure Coding Guide — CWE-117 | https://best.openssf.org/Secure-Coding-Guide-for-Python/CWE-707/CWE-117 | n/d | Log injection | 27 | `RESEARCH` |
| CodeQL — Java log injection | https://codeql.github.com/codeql-query-help/java/java-log-injection | n/d | Log injection | 27 | `RESEARCH` |
| OpenTelemetry GenAI semantic conventions | https://github.com/open-telemetry/semantic-conventions-genai | n/d | Logowanie GenAI | 27 | `RESEARCH` |
| LangGraph GRAPH_RECURSION_LIMIT | https://docs.langchain.com/oss/python/langgraph/GRAPH_RECURSION_LIMIT ; https://docs.langchain.com/oss/javascript/langgraph/errors/GRAPH_RECURSION_LIMIT | n/d | Limit rekurencji (domyślne 25 z wyników wyszukiwania) | 15, 16 | `MITIGATION` |
| LangChain AgentExecutor `max_iterations` | https://reference.langchain.com/python/langchain-classic/agents/agent/AgentExecutor/max_iterations | n/d | Domyślne 15 (z wyników wyszukiwania) | 15 | `MITIGATION` |
| CrewAI — Customizing Agents | https://docs.crewai.com/en/learn/customizing-agents | n/d | `max_iter`=25, `max_rpm`, `max_execution_time` | 15 | `MITIGATION` |
| OpenAI Agents SDK — Running agents | https://openai.github.io/openai-agents-python/running_agents/ | n/d | `max_turns`, timeouty | 15 | `MITIGATION` |
| AutoGen — Termination Conditions | https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/termination.html | n/d | Warunki zakończenia | 15 | `MITIGATION` |
| agentgateway — MCP rate limit | https://agentgateway.dev/docs/kubernetes/latest/mcp/rate-limit/ | n/d | Limity per narzędzie | 15 | `MITIGATION` |
| OpenHands Stuck Detector | https://docs.openhands.dev/sdk/guides/agent-stuck-detector | n/d | Wykrywanie pętli | 16 | `MITIGATION` |
| Pillar Security SAIL 5.11 | https://www.pillar.security/sail/runaway-agent-reasoning-loop-dos | n/d | Runaway agent / reasoning loop DoS | 15 | `RESEARCH` / `MITIGATION` |
| Unicode TR39 confusables (translit docs) | https://translit.readthedocs.io/en/latest/user-guide/confusables.html | n/d | Homoglify | 21 | `MITIGATION` |
| LLM Guard — Sensitive output scanner | https://protectai.github.io/llm-guard/output_scanners/sensitive/ | n/d | Skaner wyjścia | 19 | `VENDOR-CLAIM` |
| LLM Guard — JSON output scanner | https://protectai.github.io/llm-guard/output_scanners/json/ | n/d | Walidacja JSON | 20 | dok. |
| Sigstore model-signing v1.0 | https://blog.sigstore.dev/model-transparency-v1.0 | n/d | Podpisywanie modeli | 24 | `MITIGATION` |
| Google — Taming the Wild West of ML | https://blog.google/security/taming-wild-west-of-ml-practical-mode/ | n/d | Podpisywanie modeli | 24 | `MITIGATION` |
| Hugging Face + Protect AI (6 miesięcy) | https://huggingface.co/blog/pai-6-month | n/d | Skanowanie modeli | 24 | `VENDOR-CLAIM` |
| OpenSSF — OSV malicious packages (blog) | https://openssf.org/?p=11003 | n/d | Baza złośliwych paczek | 22, 25 | `MITIGATION` |
| Cisco — Unicode Tag Prompt Injection | https://blogs.cisco.com/ai/understanding-and-mitigating-unicode-tag-prompt-injection | n/d | Znaki Unicode Tag | 21, 26 | `REAL-ATTACK` / `MITIGATION` |

## 7. Research papers i prace badawcze

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| Carlini i in., Extracting Training Data from LLMs | https://arxiv.org/abs/2012.07805 ; https://www.usenix.org/conference/usenixsecurity21/presentation/carlini-extracting | 2020/2021 (USENIX Security 2021) | Ekstrakcja danych treningowych | 01, 19 | `RESEARCH` |
| Huang i in., „Your Code Secret Belongs to Me" | https://arxiv.org/abs/2309.07639 | 2023-09-14 (rev. 2024-05-20), FSE '24 | Wyciek sekretów z modeli kodu | 02 | `RESEARCH` |
| pii-codex (Zenodo) | https://zenodo.org/records/7460567 | 12/2022 | Kategoryzacja PII | 01 | `RESEARCH` |
| Shumailov i in., Sponge Examples | https://arxiv.org/abs/2006.03463 | 2020, rev. 2021 (EuroS&P) | Ataki energia-opóźnienie | 13, 18 | `RESEARCH` |
| Fill and Squeeze (DoS na schedulery LLM) | https://arxiv.org/pdf/2511.04686v1 | 2025 | DoS (częściowo zweryfikowane) | 18 | `RESEARCH` |
| Many-Shot Jailbreaking (Anthropic) | https://www.anthropic.com/research/many-shot-jailbreaking | 2024-04-02 | Długi kontekst, jailbreak | 18, 26 | `RESEARCH` |
| Zhang i in., Breaking Agents (malfunction amplification) | https://arxiv.org/abs/2407.20859 | 2024-07-30 (EMNLP 2025) | Wzmacnianie awarii agentów | 15 | `RESEARCH` `POC` |
| Cohen i in., Here Comes The AI Worm (Morris II) | https://arxiv.org/abs/2403.02817 | 2024 | Robak AI | 15 | `RESEARCH` `POC` |
| CaMeL: Defeating Prompt Injections by Design | https://arxiv.org/abs/2503.18813 | 2025-03-24 (rev. 2025-06-24) | Przepływ informacji | 17 | `RESEARCH` |
| Design Patterns for Securing LLM Agents against Prompt Injections | https://arxiv.org/abs/2506.08837 | 2025-06-10 | Wzorce projektowe (nazwy wzorców **niezweryfikowane**) | 17 | `RESEARCH` |
| FIDES — Information-Flow Control | https://arxiv.org/abs/2505.23643 | 2025-05-29 (rev. 2025-09-03) | IFC dla agentów (repozytorium kodu **niezweryfikowane**) | 17 | `RESEARCH` |
| Progent | https://arxiv.org/abs/2504.11703 | 2025-04-16 | Kontrola uprawnień agentów (repo **niezweryfikowane**) | 17 | `RESEARCH` |
| AgentSpec | https://arxiv.org/abs/2503.18666 | 2025-03-24 | Specyfikacje runtime (repo **niezweryfikowane**) | 17 | `RESEARCH` |
| EchoLeak (Reddy, Gujral), AAAI Fall Symposium 2025 | https://arxiv.org/abs/2509.10540 | 2025 | Zero-click injection w M365 Copilot | 19 | `RESEARCH` `CONFIRMED-VULN` |
| Imprompter | https://arxiv.org/abs/2410.14923 ; https://simonwillison.net/2024/Oct/22/imprompter/ | 2024-10-22 | Exfiltracja przez obfuskowane prompty | 20 | `RESEARCH` |
| Enhancing Security in LLM Applications: Early Detection Systems | https://arxiv.org/abs/2506.19109 | 2025-06 | Canary tokens / Vigil / Rebuff (teza ze streszczenia wyszukiwania, nie z pełnej lektury) | 19, 26 | `RESEARCH` |
| Bypassing LLM Guardrails | https://arxiv.org/abs/2504.11168 | 2025-04 | Obejścia guardrails znakami Unicode | 21, 26 | `RESEARCH` |
| Mindgard — podsumowanie Bypassing LLM Guardrails | https://mindgard.ai/resources/bypassing-llm-guardrails-character-and-aml-attacks-in-practice | n/d | j.w. | 21 | `RESEARCH` |
| PromptInject | https://arxiv.org/abs/2211.09527 | n/d | Goal hijacking / prompt leaking | 26 | `RESEARCH` |
| The Attacker Moves Second | https://arxiv.org/abs/2510.09023 ; https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/ | n/d (2025) | Adaptacyjne ataki na obrony | 26 | `RESEARCH` |
| Hide and Seek (arXiv 2508.19774) | https://arxiv.org/html/2508.19774v1 | n/d | Ukrywanie złośliwych modeli | 24 | `RESEARCH` |
| Slopsquatting (USENIX Security 2025) — CSA note | https://labs.cloudsecurityalliance.org/research/csa-research-note-slopsquatting-ai-supply-chain-20260419/ ; https://xygeni.io/blog/slopsquatting-attack-prevention/ | 2026-04-19 (URL CSA) | Halucynowane pakiety (źródła wtórne) | 25 | `RESEARCH` (wtórne) |
| Willison — The lethal trifecta | https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/ | 2025-06-16 | Trójka ryzyk agentów (w 20 z listy wpisów, treść **niezweryfikowana**) | 17, 20 | `RESEARCH` |
| Willison o CaMeL | https://simonwillison.net/2025/Apr/11/camel/ | 2025-04-11 | Komentarz do CaMeL | 17 | `RESEARCH` |
| Language Log — niewidzialny tekst (Unicode tags) | https://languagelog.ldc.upenn.edu/nll/?p=66513 | n/d | Znaki Unicode Tag | 21 | `RESEARCH` |
| Trail of Bits — kategoria MCP | https://blog.trailofbits.com/categories/mcp/ | 2025 | Badania MCP (wpisów nie pobierano; opis z wyników wyszukiwania) | 12 | `RESEARCH` |
| Palo Alto Unit 42 — MCP attack vectors | https://unit42.paloaltonetworks.com/model-context-protocol-attack-vectors/ | 2025 | Wektory ataku MCP / sampling | 12 | `RESEARCH` |
| CSA — MCP tool poisoning note | https://labs.cloudsecurityalliance.org/research/csa-research-note-mcp-tool-poisoning-ai-agent-exfiltration-2/ | 2026 | Tool poisoning | 12 | `RESEARCH` |
| Orange Tsai, A New Era of SSRF (Black Hat USA 2017) | https://infocondb.org/con/black-hat/black-hat-usa-2017/a-new-era-of-ssrf-exploiting-url-parser-in-trending-programming-languages | 2017-07 | Parsery URL a SSRF | 10 | `RESEARCH` |
| GitGuardian — State of Secrets Sprawl 2026 | https://gitguardian.com/state-of-secrets-sprawl-report-2026 ; https://thehackernews.com/2026/03/the-state-of-secrets-sprawl-2026-9.html | 2026-03 | Raport o sekretach | 02 | `RESEARCH` / `VENDOR-CLAIM` |
| GitGuardian — Copilot can leak secrets | https://blog.gitguardian.com/yes-github-copilot-can-leak-secrets/ | 2023 | Wyciek sekretów z Copilota | 02 | `RESEARCH` / `VENDOR-CLAIM` |
| Sonatype — Redis race condition | https://www.sonatype.com/blog/openai-data-leak-and-redis-race-condition-vulnerability-that-remains-unfixed | 2023 | Wyciek między sesjami ChatGPT | 06 | `RESEARCH` |
| Nudge Security — Asana MCP | https://www.nudgesecurity.com/post/asana-mcp-server-data-exposure-incident | 2025-06 | Analiza incydentu Asana | 06 | `RESEARCH` |
| HackerNoon — „Your Agent Is Not Stuck, It Is Looping" | https://hackernoon.com/your-agent-is-not-stuck-it-is-looping-there-is-a-difference-and-it-costs-you-either-way | wczesny 2026 | Pętle agentów (anegdotyczne; liczby 47 000 USD / 11 dni **niezweryfikowane**, nieużyte) | 15 | `RESEARCH` |
| Token Count Comparison (Qwen vs GPT, HF Space) | https://huggingface.co/spaces/xzuyn/Token-Count-Comparison/blob/main/app.py | n/d | Porównanie tokenizacji — **niezweryfikowane** | 14 | `RESEARCH` |
| Zbiory danych: deepset/prompt-injections | https://huggingface.co/datasets/deepset/prompt-injections | n/d | Dane testowe prompt injection | 26 | dane |
| Zbiory danych: Lakera/gandalf_ignore_instructions | https://huggingface.co/datasets/Lakera/gandalf_ignore_instructions | n/d | Dane testowe | 26 | dane |

## 8. Projekty open-source i narzędzia

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| Gitleaks (+ `config/gitleaks.toml`) | https://github.com/gitleaks/gitleaks | pobrane 2026-10-03 | Skaner sekretów (MIT; regexy do porównania z oryginałem) | 02 | `VENDOR-CLAIM` / dok. |
| TruffleHog | https://github.com/trufflesecurity/trufflehog | 2026-10-03 | Sekrety, weryfikacja (AGPL-3.0, 800+ detektorów) | 02 | dok. |
| detect-secrets (Yelp) | https://github.com/Yelp/detect-secrets | 2026-10-03 | Sekrety (Apache-2.0) | 02 | dok. |
| Microsoft Presidio (repo) | https://github.com/microsoft/presidio | dostęp 2026 | Detekcja PII | 01 | `VENDOR-CLAIM` |
| scrubadub | https://github.com/LeapBeyond/scrubadub | n/d | PII scrubbing | 01 | `VENDOR-CLAIM` |
| DataFog | https://github.com/datafog/datafog-python | n/d | PII | 01 | `VENDOR-CLAIM` |
| networknt/json-schema-validator | https://github.com/networknt/json-schema-validator | bieżąca | Walidacja JSON Schema (Apache-2.0, do 2020-12) | 08, 20 | dok. |
| Bucket4j | https://github.com/bucket4j/bucket4j ; https://bucket4j.com/8.10.1/toc.html | bieżące | Rate limiting | 13 | `VENDOR-CLAIM` |
| Invariant Guardrails | https://github.com/invariantlabs-ai/invariant | n/d | Guardrails dla agentów (Apache-2.0) | 17 | `MITIGATION` |
| Invariant MCP-Scan | https://invariantlabs.ai/blog/introducing-mcp-scan | 2025 | Skaner MCP | 12 | `VENDOR-CLAIM` |
| Cisco AI Defense MCP Scanner | https://github.com/cisco-ai-defense/mcp-scanner | bieżąca | Skaner MCP | 12 | `VENDOR-CLAIM` |
| CaMeL (kod) | https://github.com/google-research/camel-prompt-injection | n/d | Artefakt badawczy (Apache-2.0) | 17 | `RESEARCH` |
| aho-corasick (Java) | https://github.com/robert-bor/aho-corasick | n/d | Dopasowanie sygnatur (Apache-2.0) | 22 | dok. |
| NVIDIA garak | https://github.com/NVIDIA/garak ; https://reference.garak.ai/en/latest/probes/web_injection.html | n/d | Skaner/probes LLM | 20, 26 | `MITIGATION` / narzędzie testowe |
| Rebuff (Protect AI) | https://github.com/protectai/rebuff | zarchiwizowane 2025-05-16 | Wykrywanie prompt injection; skuteczność wg deklaracji | 19 | `VENDOR-CLAIM` |
| OpenSSF malicious-packages | https://pkg.go.dev/github.com/ossf/malicious-packages | n/d | Baza złośliwych paczek | 25 | `MITIGATION` |
| floe-guard | https://pypi.org/project/floe-guard/0.6.0/ | n/d | Test capu przy check-then-act | 14 | `VENDOR-CLAIM`, **niezweryfikowane** |
| pydantic-deepagents — stuck-loop detection | https://cdn.jsdelivr.net/gh/vstorm-co/pydantic-deepagents@main/docs/advanced/stuck-loop-detection.md | n/d | Wykrywanie pętli (dok., nie czytana szczegółowo) | 16 | dok. |
| Pozostałe OSS bez URL w case'ach | Smokescreen, Advocate, ssrf_filter, IPAddress (10); bashlex, tree-sitter-bash, sqlglot, JSqlParser, RE2J, GTFOBins (11); Betterleaks (02); jCasbin, cedar-java (04); Portkey, Envoy AI Gateway (05); libphonenumber (01) | n/d | Wymienione z wiedzy ogólnej — **niezweryfikowane** (licencje, aktywność) | 01, 02, 04, 05, 10, 11 | n/d |

## 9. Incydenty i reporty

| Nazwa | Link | Data | Czego dotyczy | Case'y | Typ dowodu |
|---|---|---|---|---|---|
| OpenAI — March 20 ChatGPT outage | https://openai.com/index/march-20-chatgpt-outage/ ; https://www.helpnetsecurity.com/2023/03/27/chatgpt-data-leak/ ; https://thehackernews.com/2023/03/openai-reveals-redis-bug-behind-chatgpt.html | 2023-03 | Wyciek między użytkownikami (Redis); bezpośrednie pobranie OpenAI: 403 | 06, 19 | `REAL-ATTACK` / `CONFIRMED-VULN` |
| ChatGPT — wyciek danych płatniczych | https://www.bankinfosecurity.net/chatgpt-exposed-payment-card-data-subscribers-a-21528 ; https://in.benzinga.com/news/23/03/31495392/chatgpts-march-20-outage-openai-patches-bug-notifies-affected-users-of-payment-info-exposure | 2023-03 | Dane kart płatniczych | 01 | `CONFIRMED-VULN` |
| Samsung — kod wklejony do ChatGPT | https://incidentdatabase.ai/es/entities/samsung-engineers/ ; https://aphnetworks.com/index.php/news/27002-samsung-software-engineers-busted-pasting-proprietary-code-chatgpt ; https://techcrunch.com/2023/05/02/samsung-bans-use-of-generative-ai-tools-like-chatgpt-after-april-internal-data-leak/ | 2023 (TechCrunch 2023-05-02) | Incydent nieumyślny po stronie wejścia; liczba 1024 bajtów **niezweryfikowana** | 01, 02, 19 | `REAL-ATTACK` |
| BleepingComputer — Asana MCP | https://bleepingcomputer.com/news/security/asana-warns-mcp-ai-feature-exposed-customer-data-to-other-orgs/ | 2025-06 | Wyciek między organizacjami | 06 | `REAL-ATTACK` |
| Replit — usunięcie bazy danych | https://fortune.com/2025/07/23/ai-coding-tool-replit-wiped-database-called-it-a-catastrophic-failure ; https://dc.fortune.com/2025/07/23/ai-coding-tool-replit-wiped-database-called-it-a-catastrophic-failure ; https://www.eweek.com/news/replit-ai-coding-assistant-failure/ | 2025-07-23 | Nadmierna sprawczość agenta (w 16 tylko kontekst) | 04, 06, 16, 27 | `REAL-ATTACK` |
| Capital One 2019 (SSRF / IMDSv1) | https://www.fastly.com/blog/preventing-server-side-request-forgery-ssrf ; https://techearl.com/capital-one-breach-ssrf | 2019 | Źródła wtórne | 10 | `REAL-ATTACK` (wtórne) |
| Sysdig — LLMjacking | https://sysdig.com/blog/llmjacking-stolen-cloud-credentials-used-in-new-ai-attack/ | 2024-05 (2024-05-06) | Kradzież poświadczeń do LLM | 13, 14 | `REAL-ATTACK` |
| Sysdig — LLMjacking targets DeepSeek | https://www.sysdig.com/blog/llmjacking-targets-deepseek | 2025 | j.w. | 13 | `REAL-ATTACK` |
| Dark Reading — LLM Hijackers / DeepSeek | https://www.darkreading.com/application-security/llm-hijackers-deepseek-api-keys | 2025 | Tylko wynik wyszukiwania, treść niepobrana | 14 | `REAL-ATTACK` |
| HackMag — klucz API Gemini ($82 000) | https://hackmag.com/news/gemini-api-key | n/d | Kwoty **niezweryfikowane** niezależnie | 13 | `REAL-ATTACK` |
| „The $47,000 AI Agent Mistake" | https://rapidflowautomation.beehiiv.com/p/the-47-000-ai-agent-mistake-nobody-saw-coming | n/d | Źródło wtórne; oryginał (Towards AI) niepobrany | 16 | `VENDOR-CLAIM` |
| AWS Bedrock $30k | https://aiweekly.co/alerts/aws-bedrock-agent-loop-costs-developer-30000 | n/d | **Niezweryfikowane** (403) | 16 | n/d |
| Google AI Studio — nieoczekiwane rachunki | https://discuss.ai.google.dev/t/unexpected-billing-due-to-infinite-loop-code-generated-by-google-ai-studio-build/170420 | n/d | Tylko tytuł, **niezweryfikowane** | 16 | n/d |
| dev.to — „An agent that can spawn agents is a fork bomb" | https://dev.to/agentiknet/an-agent-that-can-spawn-agents-is-a-fork-bomb-with-good-intentions-2kjh | 2026 | Rekurencyjne sub-agenty | 15 | `CONFIRMED-VULN` (zgłoszenia użytkowników) |
| Shai-Hulud / s1ngularity — InfoQ | https://www.infoq.com/news/2025/10/npm-s1ngularity-shai-hulud | 2025-10 | Ataki npm na sekrety | 02 | `REAL-ATTACK` |
| Wiz — Shai-Hulud | https://wiz.io/blog/shai-hulud-npm-supply-chain-attack | 2025-09 | Robak npm | 02 | `REAL-ATTACK` |
| Flashpoint — Shai-Hulud | https://flashpoint.io/blog/shai-hulud-worm-targeting-npm-supply-chains/ | n/d | Robak npm | 22, 25 | `REAL-ATTACK` |
| Blackpoint — Shai-Hulud | https://blackpointcyber.com/blog/inside-the-shai-hulud-npm-supply-chain-attack/ | n/d | Robak npm | 25 | `REAL-ATTACK` |
| THN / Orca — s1ngularity (Nx) | https://thehackernews.com/2025/08/malicious-nx-packages-in-s1ngularity.html ; https://orca.security/resources/blog/s1ngularity-supply-chain-attack/ | 2025-08 | Złośliwe paczki Nx | 25 | `REAL-ATTACK` |
| Akto — AI coding assistant security | https://www.akto.io/blog/ai-coding-assistant-security-compared | 2026 | Agregat CVE-2025-55284, -64110, -54135; **niezweryfikowane u źródła pierwotnego** | 02 | `CONFIRMED-VULN` wg agregatora |
| postmark-mcp (Koi Security) | https://koi.ai/blog/postmark-mcp-npm-malicious-backdoor-email-theft | 2025-09 | Złośliwy serwer MCP; strona niedostępna dla fetch (treść z mediów) | 07 | `REAL-ATTACK` |
| postmark-mcp — Dark Reading / SC World / CSO | https://www.darkreading.com/application-security/malicious-mcp-server-exfiltrates-secrets-bcc ; https://www.scworld.com/news/open-source-mcp-server-package-caught-stealing-emails ; https://www.csoonline.com/article/4064009/trust-on-mcp-takes-first-in-the-wild-hit-via-squatted-postmark-connector.html | 2025 | j.w. | 07, 12, 25 | `REAL-ATTACK` |
| Rules File Backdoor (THN) | https://thehackernews.com/2025/03/new-rules-file-backdoor-attack-lets.html | 2025-03 | Ukryte instrukcje w plikach reguł | 21, 25, 26 | `REAL-ATTACK` |
| Dependency confusion (Arcjet) | https://arcjet.com/learn/dependency-confusion-attacks | n/d | Źródło wtórne | 25 | `REAL-ATTACK` (wtórne) |
| Log4Shell — obfuskacja | https://answers.securityscientist.net/q/20884/how-did-attackers-bypass-initial-mitigations | n/d | Agregator | 21, 22 | `REAL-ATTACK` (agregator) |
| JFrog — złośliwe modele HF | https://jfrog.com/blog/data-scientists-targeted-by-malicious-hugging-face-ml-models/ | n/d | Modele z kodem wykonywalnym | 23, 24 | `REAL-ATTACK` |
| Dark Reading — 100 złośliwych modeli HF | https://www.darkreading.com/application-security/hugging-face-ai-platform-100-malicious-code-execution-models | n/d | j.w. | 24 | `REAL-ATTACK` |
| ReversingLabs / THN — nullifAI | https://www.reversinglabs.com/blog/rl-identifies-malware-ml-model-hosted-on-hugging-face ; https://thehackernews.com/2025/02/malicious-ml-models-found-on-hugging.html | 2025-02 | Obejście skanera pickle | 24 | `POC` |
| Invariant Labs — Tool Poisoning Attacks | https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks | 2025-04-01 | Tool poisoning | 12 | `POC` |
| Invariant Labs — GitHub MCP | https://invariantlabs.ai/blog/mcp-github-vulnerability | 2025-05-26 | Wyciek przez GitHub MCP | 07, 12, 17 | `POC` (w 17 także `CONFIRMED-VULN`) |
| Willison — Supabase MCP (lethal trifecta) | https://simonwillison.net/2025/Jul/6/ | 2025-07-06 | Trójka ryzyk w MCP | 07, 12 | `POC` |
| PromptArmor / Willison — Slack AI | https://promptarmor.com/resources/data-exfiltration-from-slack-ai-via-indirect-prompt-injection ; https://simonwillison.net/2024/Aug/20/data-exfiltration-from-slack-ai/ | 2024-08-20 | Exfiltracja z Slack AI | 19, 20 | `POC` |
| Willison — tag markdown-exfiltration | https://simonwillison.net/tags/markdown-exfiltration | odczytano 2026 | ChatGPT, Bard, Writer.com, Amazon Q, NotebookLM, AI Studio | 19, 20 | `POC` / `RESEARCH` |
| Willison / Rehberger — Google Bard exfiltration | https://simonwillison.net/2023/Nov/4/hacking-google-bard-from-prompt-injection-to-data-exfiltration/ ; https://embracethered.com/blog/posts/2023/google-bard-data-exfiltration/ | 2023-11-04 (oryginał nie otwarty bezpośrednio) | Exfiltracja przez obrazy markdown | 20 | `POC` |
| Willison — ChatGPT markdown image (Samoilenko) | https://simonwillison.net/2023/Apr/14/new-prompt-injection-attack-on-chatgpt-web-version-markdown-imag/ ; https://systemweakness.com/new-prompt-injection-attack-on-chatgpt-web-version-ef717492c5c2 | 2023-04-14 | j.w. | 20 | `POC` |
| Willison — GitHub Copilot Chat prompt injection | https://simonwillison.net/2024/Jun/16/github-copilot-chat-prompt-injection/ | 2024-06-16 | Exfiltracja | 20 | `CONFIRMED-VULN` |
| Willison / Rehberger — unfurling hiperlinków | https://simonwillison.net/2024/Aug/21/dangers-of-ai-agents-unfurling/ ; https://embracethered.com/blog/posts/2024/the-dangers-of-unfurling-and-what-you-can-do-about-it/ | 2024-08-21 | Unfurling linków | 20 | `RESEARCH` / `MITIGATION` |
| THN — Microsoft naprawia ASCII smuggling | https://thehackernews.com/2024/08/microsoft-fixes-ascii-smuggling-flaw.html ; https://www.govinfosecurity.com/microsoft-copilot-fixes-ascii-smuggling-vulnerability-a-26161 | 2024-08 | ASCII smuggling w Copilocie | 20, 21 | `CONFIRMED-VULN` / `REAL-ATTACK` |
| The Next Web — ASCII smuggling w phishingu | https://thenextweb.com/news/ascii-smuggling-phishing-microsoft-unicode-tag-characters | n/d | **Niezweryfikowane** poza streszczeniem wyszukiwarki | 20 | `REAL-ATTACK` |
| Willison — Johann Rehberger / Claude iOS | https://simonwillison.net/2024/Dec/17/johann-rehberger/ | 2024-12-17 | Szczegóły **niezweryfikowane** | 20 | `CONFIRMED-VULN` |
| Willison / Legit — GitLab Duo | https://simonwillison.net/2025/May/23/remote-prompt-injection-in-gitlab-duo/ ; https://www.legitsecurity.com/blog/remote-prompt-injection-in-gitlab-duo | 2025-05-23 | Zdalny prompt injection | 20 | `CONFIRMED-VULN` |
| Zenity Labs — „It's Always DNS in Claude's Sandbox" | https://labs.zenity.io/post/it-s-always-dns-in-claude-s-sandbox-from-data-exfiltration-to-a-bidirectional-dns-shell | n/d | Tylko tytuł, **niezweryfikowane** | 20 | `RESEARCH` |
| BleepingComputer — imperfect fix ChatGPT leak | https://www.bleepingcomputer.com/news/security/openai-rolls-out-imperfect-fix-for-chatgpt-data-leak-flaw/ | 2023 | URL wg wyniku wyszukiwania, bez pełnego odczytu | 19 | `CONFIRMED-VULN` `MITIGATION` |
| Bing Chat „Sydney" — wyciek promptu | https://incidentdatabase.ai/reports/2666 ; https://gigazine.net/gsc_news/en/20230214-bing-chatgpt-discloses-secrets | 2023-02 | Ujawnienie system promptu | 19 | `REAL-ATTACK` |

## 10. Znane luki weryfikacji

Brak źródeł w kategoriach:

- **MITRE** — brak jakichkolwiek źródeł (ATT&CK/ATLAS/CWE/CAPEC) w case'ach.
- **NIST** — tylko SP 800-92 (27), wtórne omówienie SP 800-53 AU (27) i strony NVD; brak NIST AI RMF, AI 600-1 i oryginału SP 800-53.
- **CISA** — tylko feed KEV (22); brak biuletynów CISA.

Oznaczone w case'ach jako niezweryfikowane lub wtórne (skrótowo):

- **01** — dowód osobisty (wagi/specyfikacja normatywna brak; tylko przykład `ABA300000`); zakresy IIN kart, długości IBAN, wagi NRB, NHS mod 11, reguły SSN (SSA), ograniczenia maskowania PCI DSS, licencje python-stdnum i django-localflavor, URL libphonenumber.
- **02** — regexy Slack user token, Slack/Discord webhook, `whsec_`; długość AWS secret key (40); progi entropii detect-secrets; licencja Presidio; istnienie/URL Betterleaks; pełny regex JWT z Gitleaks; checksum nowych tokenów GitHub; CVE-2025-55284/-64110/-54135 tylko wg agregatora Akto (NVD bez treści); regexy Gitleaks pobrane przez narzędzie streszczające.
- **03** — RFC 8693/8705/8707 niepobrane; CVE-2026-47825 tylko źródło wtórne; OWASP Agentic (ASI03) przez Giskard (wtórne).
- **04** — licencje jCasbin i dostępność cedar-java; porównanie Cerbos pisze dostawca.
- **05** — funkcje allowlist Portkey i Envoy AI Gateway oraz licencje.
- **06** — OWASP LLM Top 10 (LLM02/LLM08) niezweryfikowane; strona OpenAI zwróciła 403 (potwierdzenie przez wyszukiwarkę i Help Net Security).
- **07** — Koi Security niedostępne dla fetch (treść z mediów).
- **09** — `openat2(RESOLVE_BENEATH)` i Landlock z wiedzy ogólnej (sprawdzić w man7.org / docs.kernel.org); strona OWASP LLM Top 10 zweryfikowana tylko przez wyniki wyszukiwania.
- **10** — projekty OSS (Smokescreen, Advocate, ssrf_filter, IPAddress) bez weryfikacji licencji i aktywności; Capital One tylko źródła wtórne.
- **11** — narzędzia OSS (bashlex, tree-sitter-bash, sqlglot, JSqlParser, RE2J, GTFOBins) bez weryfikacji; CVE-2025-6514 przez źródło wtórne (potwierdzić w NVD/JFrog); brak zweryfikowanych CVE dla `git --upload-pack` poza mcp-server-git.
- **12** — Trail of Bits: tylko wyniki wyszukiwania, bez lektury wpisów.
- **13** — klasy integracji Bucket4j z SCG, API `XForwardedRemoteAddressResolver`, licencje LiteLLM/Redis, opóźnienia Redis/Postgres; HackMag (kwoty niezależnie niezweryfikowane, brak daty); CVE-2026-55501 wtórne.
- **14** — floe-guard (`VENDOR-CLAIM`, niezweryfikowane); Token Count Comparison; opisy `stream_options.include_usage` w Ollama OpenAI-compat na naszej wersji; Dark Reading i Portkey tylko z wyników wyszukiwania.
- **15** — data publikacji OWASP Agentic 2026 niezweryfikowana; mapowanie ASI02 -> T2/T4/T16 wtórne; wartości liczbowe z agregatora claudeissues.com; Cursor forum niepełna lektura; domyślne wartości LangGraph (25) i LangChain (15) z wyników wyszukiwania; liczby 47 000 USD / 11 dni niezweryfikowane i nieużyte.
- **16** — kwota w Claude Code #35166 niezweryfikowana; „$47,000" i AWS Bedrock $30k (403) niezweryfikowane; Google AI Studio tylko tytuł; pydantic-deepagents niedokładnie czytane; szczegóły dat Cursor niezweryfikowane.
- **17** — nazwy wzorców z „Design Patterns" (sekcja 3), repozytoria kodu FIDES/Progent/AgentSpec, status Invariant po ewentualnym przejęciu, wymiary CVSS poza źródłami.
- **18** — Spring Cloud Gateway `RequestSize` (404); zachowanie Ollamy przy rozłączeniu klienta w trakcie prefill/generacji (wymaga testu na Pi, LIMIT-T020); CVE-2026-28975 powiązanie ze Spring niepotwierdzone; część CVE tylko ze snippetów/agregatorów.
- **19** — liczba 1024 bajtów w Samsung; licencje oznaczone „do weryfikacji"; szacunki latencji (sekcja 5A) to obliczenia `THEORETICAL`; teza o nieskuteczności canary w Vigil/Rebuff ze streszczenia; CVE-2025-32711 wtórne (Aim Security 403).
- **20** — Zenity Labs (tylko tytuł); Rehberger/Claude iOS (szczegóły); Next Web (poza streszczeniem); lethal trifecta (z listy wpisów); oryginał Bard nie otwarty bezpośrednio.
- **21** — OWASP LLM01 niepobrane; Log4Shell tylko agregator.
- **22** — brak adnotacji o weryfikacji w sekcji 15 (źródła bez dat; Log4Shell — agregator).
- **23** — magic bytes (AC ED 00 05, opcody pickle, markery PHP/.NET) z wiedzy ogólnej.
- **25** — slopsquatting oraz dependency confusion tylko źródła wtórne.
- **26** — OWASP LLM01 niepobrane.
- **27** — RFC 8785, W3C Trace Context, OWASP Logging Cheat Sheet niepobrane; CVE-2024-5225 / GHSA-h6m6-jj8v-94jj do weryfikacji w NVD; SP 800-53 tylko omówienie wtórne.
