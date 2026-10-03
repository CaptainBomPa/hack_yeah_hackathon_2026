# Unsafe Deserialization & Serialized-Object Payloads
> **ID:** DESER-001..008  | **Kategoria:** input | **Priorytet:** SHOULD | **Złożoność:** M | **Punkt egzekwowania:** input, tool-call (argumenty i uploady), output narzędzi; artefakty modeli (admission)

## 1. Overview
Gateway przepuszcza dane, które downstream (narzędzie MCP, backend RAG, loader modelu, framework LLM) może zdeserializować w niebezpieczny sposób: Java serialization, Python pickle/joblib/`torch.load`, YAML z tagami obiektowymi (`!!python/object`, SnakeYAML `Constructor`), PHP `unserialize`, .NET `BinaryFormatter`, oraz szablony (Jinja2 SSTI), które nie są deserializacją, ale mają tę samą klasę skutków (wykonanie kodu z danych). Chronimy: hosty narzędzi, loadery modeli, pipeline'y RAG/ingest.

## 2. Threat / Attack
1. Atakujący dostarcza obiekt zserializowany (bezpośrednio w argumencie narzędzia, uploadzie, wyniku narzędzia „zatrutym" dokumentem, lub w base64 w prompcie, który agent przekaże do `pickle.loads`).
2. Deserializer buduje graf obiektów; „gadget chain" (Java: commons-collections/…; Python: `__reduce__` → `os.system`) wykonuje kod.
3. Skutek: RCE na hoście narzędzia/serwera inferencji, wyciek sekretów, pivot.
W LLM: dodatkowy wektor — *prompt injection → generacja kodu/obiektu → wykonanie* (PALChain), oraz wstrzyknięcie struktur serializacji frameworka (LangChain `lc` key).

## 3. Real-World Evidence
- `[CONFIRMED-VULN]` **vLLM CVE-2025-47277** (CVSS 9.8; 0.6.5–0.8.4; fix 0.8.5, ujawnione 20.05.2025) — `pickle.loads` na danych od klienta w PyNcclPipe (KV-cache transfer, V0) → RCE. https://www.wiz.io/vulnerability-database/cve/cve-2025-47277
- `[CONFIRMED-VULN]` **PyTorch CVE-2025-32434** (CVSS 9.3; ≤2.5.1; fix 2.6.0) — RCE nawet z `torch.load(weights_only=True)`; pokazuje, że „tryb bezpieczny" nie jest gwarancją. https://nvd.nist.gov/vuln/detail/CVE-2025-32434 ; https://www.kaspersky.com/blog/vulnerability-in-pytorch-framework/53311/
- `[CONFIRMED-VULN]` **Keras:** CVE-2024-3660 (Lambda layers, marshalled code), CVE-2025-1550 (obejście `safe_mode=True` przez `config.json` w `.keras`), CVE-2025-8747 (obejście poprawki). JFrog: https://jfrog.com/blog/keras-safe_mode-bypass-vulnerability/
- `[CONFIRMED-VULN]` **skops CVE-2025-54886** (≤0.12.0, fix 0.13.0) — `Card.get_model` cicho wraca do joblib (pickle) → RCE. https://nvd.nist.gov/vuln/detail/CVE-2025-54886
- `[CONFIRMED-VULN]` **LangChain:** CVE-2023-36258 (PALChain `exec` kodu z LLM; < 0.0.236) i bypass CVE-2023-44467 (`__import__`, langchain_experimental <0.0.306); CVE-2025-68664 „LangGrinch" (CVSS 9.3; serialization injection przez klucz `lc` w danych od użytkownika, wyciek sekretów; fix langchain-core 0.3.81 / 1.2.5; zgłoszenie 4.12.2025). https://nvd.nist.gov/vuln/detail/CVE-2023-36258 ; https://advisories.gitlab.com/pkg/pypi/langchain-experimental/CVE-2023-44467/ ; https://thehackernews.com/2025/12/critical-langchain-core-vulnerability.html
- `[CONFIRMED-VULN]` **SnakeYAML CVE-2022-1471** (CVSS 9.8; ≤1.30; `Constructor` nie ogranicza typów; v2.0 domyślnie SafeConstructor) — typowe w stackach Java (Spring). https://www.veracode.com/blog/resolving-cve-2022-1471-snakeyaml-20-release-0/ . Konkretne CVE „SnakeYAML w MCP serverze": **niezweryfikowane** (nie znaleziono).
- `[REAL-ATTACK]` **Log4Shell CVE-2021-44228** — jako przykład sygnatury: `${jndi:ldap://…}`; JNDI lookup → deserializacja/ładowanie zdalnej klasy; omijane obfuskacją. https://answers.securityscientist.net/q/20884/how-did-attackers-bypass-initial-mitigations
- `[REAL-ATTACK]` Złośliwe modele pickle w Hugging Face (JFrog: ~100 modeli, `baller423/goober2`, reverse shell via `__reduce__`) — https://jfrog.com/blog/data-scientists-targeted-by-malicious-hugging-face-ml-models/ (szczegóły: 24-model-supply-chain.md).
- `[CONFIRMED-VULN]` MCP: CVE-2025-6514 (mcp-remote, command injection), CVE-2025-49596 (Inspector, RCE) — nie deserializacja, ale ta sama klasa „dane → wykonanie" na narzędziach MCP. https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html
- Ollama: CVE-2024-37032 (path traversal przez digest → RCE) — pokrewne; https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032
- Claim z briefu „CVE w Ollama dotyczące deserializacji": **niezweryfikowane** — Ollama (Go) nie używa pickle do GGUF; znane podatności to path traversal/OOB/DoS.

## 4. Deterministic Detection
Magic bytes / sygnatury (po CANON — dekodowanie base64/hex!):
| Format | Sygnatura | Uwagi |
|---|---|---|
| Java serialization | bajty `AC ED 00 05`; w base64 prefiks `rO0AB` (gzip+b64: `H4sIAAAAAAAA`); hex `aced0005` | `STREAM_MAGIC 0xACED`, `STREAM_VERSION 5` |
| Python pickle | protokół ≥2 zaczyna się `80 0x02..05`; opcodes: `c` GLOBAL, `\x93` STACK_GLOBAL, `R` REDUCE, `i`/`o` INST/OBJ, `b` BUILD; zakończenie `.` (STOP) | analiza opcode'ów bez wykonania (`pickletools.genops`) — wykrywanie `GLOBAL os system`, `posix`, `subprocess`, `builtins eval/exec`, `__import__` |
| PyTorch zip | PK zip z `data.pkl` | skanować `data.pkl` |
| YAML niebezpieczny | `!!python/object`, `!!python/object/apply`, `!!python/name`, `!!java.…`, `!!javax.script.ScriptEngineManager`, `!<tag:yaml.org,2002:…>` | regex na tagach `!!(python|java|javax|ruby)` |
| PHP | `O:\d+:"[A-Za-z_\\]+":\d+:{`, `a:\d+:{` | |
| .NET BinaryFormatter | `00 01 00 00 00 FF FF FF FF`; Json.NET `"$type":` ; ViewState `/wEy`, `/wEP` | |
| Jinja2/SSTI | `{{ ... }}`, `{% ... %}`, `__class__`, `__mro__`, `__subclasses__`, `lipsum.__globals__`, `cycler.__init__` | tylko tam, gdzie wejście trafia do szablonu |
| JNDI/Log4j | `\$\{[^}]*j[^}]*n[^}]*d[^}]*i` + parser rekurencyjny | po CANON |
| LangChain | JSON z kluczem `"lc": 1` + `"type": "constructor"` w danych użytkownika | wzorzec dla CVE-2025-68664 |
Dodatkowo: wykrywanie *typu* przez Apache Tika/`file`-like magic zamiast rozszerzenia (patrz picklescan CVE-2025-10155 — rozszerzenie != treść); Content-Type `application/x-java-serialized-object`, `application/x-python-pickle` → BLOCK dla endpointów, które nie powinny ich przyjmować.

## 5. Detection Pipeline
Request → Canonicalization (dekodowanie base64/hex → widoki, DESER skanuje bajty zdekodowanych warstw) → AuthN → Policy (które narzędzia w ogóle przyjmują binaria) → **Rules DESER-001..008** → LLM/MCP → Output (wyniki narzędzi/uploady z RAG: skan zanim trafią do kontekstu/loadera). Admission artefaktów (modele, pliki) przy rejestracji — wspólne z MODEL-SC.

## 6. Possible Actions
BLOCK: magic Java serialization/pickle w polach tekstowych i argumentach narzędzi; tagi YAML obiektowe; QUARANTINE: pliki modeli/uploady z pickle zawierającym niebezpieczne GLOBAL; REVIEW: pickle bez znanego niebezpiecznego importu (allowlistowane tylko `collections.OrderedDict`, `torch._utils._rebuild_tensor_v2`…); REDACT: nie dotyczy; ALLOW: dane bez sygnatur; RATE_LIMIT: serie prób.

## 7. Bypass / Limitations
- Pickle ma wiele dróg do wykonania (picklescan zaliczył serię obejść: CVE-2025-1716 `pip.main`, CVE-2025-10155/10156/10157 — rozszerzenie, CRC ZIP, podklasa niebezpiecznego modułu; JFrog, grudzień 2025). **Denylisty importów są z natury dziurawe → allowlist importów** albo odmowa pickle w ogóle. `[CONFIRMED-VULN]` https://www.infosecurity-magazine.com/news/picklescan-flaws-expose-ai-supply
- Obfuskacja: kompresja (gzip/zip/7z — nullifAI), szyfrowanie, kodowanie wielowarstwowe poza limitem głębokości, podział na fragmenty, niestandardowy format.
- Rozbieżność parserów (scanner vs. loader) — źródło większości bypassów.
- FP: base64 przypadkowo zaczynające się od `rO0AB`; legalny pickle w pipeline ML (dozwolone tylko od zaufanych podpisanych źródeł); posty edukacyjne cytujące `!!python/object` w treści promptu (rozróżnić: dane do narzędzia vs. czat).
- Wydajność: skan prefiksu O(1); pełna analiza opcode'ów liniowa, ograniczyć rozmiar.
- Gateway nie naprawi niebezpiecznego kodu downstream: realna kontrola to **zakaz** deserializatorów (JSON/safetensors, `yaml.safe_load`, `SafeConstructor`, `ObjectInputFilter`), a gateway = defense in depth.

## 8. Deterministic vs AI
Deterministycznie: sygnatury formatu, analiza opcode'ów pickle, tagi YAML, content-type, allowlist. Sidecar/AI: ocena, czy wstrzyknięty *kod w wygenerowanym snippetcie* (PALChain-style, `exec` generowanego Pythona) jest złośliwy; wykrycie prompt injection nakłaniającego agenta do wywołania `pickle.loads`. Sygnały z DESER zasilają hybrid score.

## 9. Implementation Options
- Java: skaner prefiksów (bytes) + `java.io.ObjectInputFilter` (JEP 290) w usługach Java; własny mini-parser opcode'ów pickle (~200 linii) lub wywołanie sidecara; SnakeYAML `SafeConstructor`/`LoaderOptions`; regexy RE2J dla tagów.
- Python sidecar: `fickling` (analiza pickle, static), `picklescan`, `modelscan`, `pickletools`; endpoint `/scan/artifact`.

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| fickling | https://github.com/trailofbits/fickling | Python | LGPL-3.0 (niezweryfikowane) | Analiza/dekompilacja pickle | Pełny AST pickle, allowlist-podejście | Wolniejszy | średnia | tak | wysoka |
| picklescan | https://github.com/mmaitre314/picklescan | Python | MIT | Denylist importów | Szybki, używany przez HF | Liczne bypassy (CVE-2025-1716, 10155-10157) | niska | tak | średnia (nie jako jedyny) |
| ModelScan | https://github.com/protectai/modelscan | Python | Apache-2.0 | Skan pickle/H5/SavedModel | Wiele formatów | Denylist | niska | tak | średnia |
| ysoserial / marshalsec | https://github.com/frohoff/ysoserial | Java | MIT | Generowanie payloadów Java (do testów) | Korpus testowy | Ofensywne | niska | tak | testy |
| NotSoSerial / JEP 290 filters | JDK | Java | GPL+CPE | Filtrowanie klas | Wbudowane | Wymaga konfiguracji w usłudze | niska | tak | wysoka (downstream) |
| Apache Tika | https://tika.apache.org | Java | Apache-2.0 | Detekcja typu po magic | Dojrzałe | Nie wykrywa pickle idealnie | niska | tak | średnia |
| safetensors | https://github.com/huggingface/safetensors | Rust/Py | Apache-2.0 | Bezpieczny format wag | Brak wykonania kodu | Tylko tensory | — | tak | rekomendacja |

## 11. Proposed Control
- DESER-001 Java serialization magic (`AC ED 00 05`/`rO0AB`) w input/tool args — BLOCK CRITICAL
- DESER-002 Pickle opcode analyzer (denylist + allowlist importów) — BLOCK/QUARANTINE
- DESER-003 YAML object tags — BLOCK
- DESER-004 PHP/.NET serialized markers — BLOCK
- DESER-005 SSTI markers w polach trafiających do szablonów — REVIEW/BLOCK
- DESER-006 Serialization-framework injection (`"lc":1`) — BLOCK
- DESER-007 Content-type/format mismatch (rozszerzenie vs magic) — QUARANTINE
- DESER-008 Log4Shell-style JNDI parser (rekurencyjny) — BLOCK (wspólny z SIG-001)

## 12. Example Configuration
```yaml
- id: DESER-001
  name: Java serialized object magic bytes
  category: input
  enabled: true
  priority: 30
  scope: { direction: [input, tool-call, output], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: { views: [canonical, decoded] }
  matcher:
    type: regex
    patterns:
      - '(?:^|[^A-Za-z0-9+/])rO0AB[A-Za-z0-9+/]{8,}'
      - '(?i)\baced0005\b'
      - 'H4sIAAAAAAAA[A-Za-z0-9+/]{20,}'   # gzip+b64 -> przekaż do dekodera i ponów
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM05, LLM03], references: ["https://www.wiz.io/vulnerability-database/cve/cve-2025-47277"] }
- id: DESER-002
  name: Pickle dangerous globals
  category: input
  enabled: true
  priority: 31
  scope: { direction: [input, tool-call, output], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: { content_kind: [binary, base64] }
  matcher: { type: pickle-opcode, mode: allowlist, allow_globals: ["collections.OrderedDict", "torch._utils._rebuild_tensor_v2", "numpy.core.multiarray._reconstruct"], deny_modules: [os, posix, nt, subprocess, builtins, sys, pip, asyncio, socket, shutil] }
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM03], references: ["https://jfrog.com/blog/data-scientists-targeted-by-malicious-hugging-face-ml-models/"] }
- id: DESER-003
  name: YAML object tags
  category: input
  enabled: true
  priority: 32
  scope: { direction: [input, tool-call], agents: ["*"], tools: ["*"], environments: ["*"] }
  conditions: {}
  matcher: { type: regex, pattern: '(?m)!!(?:python|java|javax|ruby|perl)[/\w.:]*' }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: [ { agent: docs-assistant, action: REVIEW } ]
  metadata: { owasp: [LLM05], cve: [CVE-2022-1471] }
```

## 13. Example Requests
```json
{"req":{"tool":"load_object","args":{"data":"rO0ABXNyABdqYXZhLnV0aWwuUHJpb3JpdHlRdWV1ZZTaMLT7P4Kx..."}},"expect":{"decision":"BLOCK","rule":"DESER-001"}}
{"req":{"messages":[{"role":"user","content":"Parse: !!python/object/apply:os.system ['id']"}],"tool":"yaml_parse"},"expect":{"decision":"BLOCK","rule":"DESER-003"}}
{"req":{"messages":[{"role":"user","content":"Jak działa Java serialization?"}]},"expect":{"decision":"ALLOW"}}
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| DESER-T001 | ysoserial CommonsCollections base64 (`rO0AB…`) | BLOCK |
| DESER-T002 | Ten sam payload dodatkowo gzip+base64 | BLOCK po CANON |
| DESER-T003 | pickle `cos\nsystem\n(S'id'\ntR.` | BLOCK |
| DESER-T004 | pickle z `pip.main` (CVE-2025-1716-style) | BLOCK (allowlist) |
| DESER-T005 | Benign pickle (dict) w zaufanym kanale | ALLOW/REVIEW wg polityki |
| DESER-T006 | `!!python/object/apply:subprocess.check_output` | BLOCK |
| DESER-T007 | `yaml: {a: 1}` | ALLOW |
| DESER-T008 | `{{ ''.__class__.__mro__[1].__subclasses__() }}` do narzędzia szablonów | BLOCK |
| DESER-T009 | `{"lc":1,"type":"constructor","id":["langchain",…]}` w polu użytkownika | BLOCK |
| DESER-T010 | Plik `.bin` o treści pickle (mismatch) | QUARANTINE |
| DESER-T011 (bypass) | pickle 7z-owany/zaszyfrowany | niewykryty deterministycznie -> QUARANTINE nieznanych kontenerów |
| DESER-T012 (FP) | Losowy base64 zaczynający się `rO0AB` bez poprawnego nagłówka obiektu | REVIEW po walidacji struktury |

## 15. Sources
- vLLM CVE-2025-47277 — https://www.wiz.io/vulnerability-database/cve/cve-2025-47277 — [CONFIRMED-VULN]
- PyTorch CVE-2025-32434 — https://nvd.nist.gov/vuln/detail/CVE-2025-32434 — [CONFIRMED-VULN]
- Kaspersky o CVE-2025-32434 — https://www.kaspersky.com/blog/vulnerability-in-pytorch-framework/53311/ — [CONFIRMED-VULN]
- JFrog Keras safe_mode bypass — https://jfrog.com/blog/keras-safe_mode-bypass-vulnerability/ — [CONFIRMED-VULN]
- skops CVE-2025-54886 — https://nvd.nist.gov/vuln/detail/CVE-2025-54886 — [CONFIRMED-VULN]
- LangChain CVE-2023-36258 — https://nvd.nist.gov/vuln/detail/CVE-2023-36258 ; CVE-2023-44467 — https://advisories.gitlab.com/pkg/pypi/langchain-experimental/CVE-2023-44467/ — [CONFIRMED-VULN]
- LangGrinch CVE-2025-68664 — https://thehackernews.com/2025/12/critical-langchain-core-vulnerability.html — [CONFIRMED-VULN]
- SnakeYAML CVE-2022-1471 — https://www.veracode.com/blog/resolving-cve-2022-1471-snakeyaml-20-release-0/ — [CONFIRMED-VULN]
- picklescan zero-days — https://www.infosecurity-magazine.com/news/picklescan-flaws-expose-ai-supply — [CONFIRMED-VULN]
- JFrog malicious HF models — https://jfrog.com/blog/data-scientists-targeted-by-malicious-hugging-face-ml-models/ — [REAL-ATTACK]
- mcp-remote — https://thehackernews.com/2025/07/critical-mcp-remote-vulnerability.html — [CONFIRMED-VULN]
- Magic bytes (AC ED 00 05, opcodes pickle, PHP/.NET markers) — wiedza ogólna, niezweryfikowana źródłem w tej sesji.
