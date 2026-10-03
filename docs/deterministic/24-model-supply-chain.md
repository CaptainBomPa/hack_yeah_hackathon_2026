# Model Supply Chain (artefakty modeli, Ollama, rejestry)
> **ID:** MODEL-SC-001..008  | **Kategoria:** supply-chain | **Priorytet:** MUST (dla lokalnego Ollama: tak) | **Złożoność:** M | **Punkt egzekwowania:** session/admission (przy `pull`/`create`/załadowaniu modelu), tool-call (API zarządzające Ollamą), pipeline CI

## 1. Overview
Nasz stack to lokalny Ollama na Raspberry Pi. Wagi modelu to kod-adjacent artefakt: format pickle (`.bin`/`.pt`/`.pkl`/`.joblib`) wykonuje kod przy ładowaniu; GGUF/safetensors nie wykonują kodu, ale GGUF ma historię błędów parsera (memory corruption), a Modelfile/`TEMPLATE`/`SYSTEM` mogą wstrzykiwać zachowanie. Chronimy: (a) jakie modele wolno ściągnąć/uruchomić (allowlista rejestrów, nazw, **digestów sha256**), (b) integralność (hash/podpis), (c) dostęp do API zarządzania Ollamą (`/api/pull`, `/api/create`, `/api/push`, `/api/delete`) — gateway powinien być jedynym wejściem.

## 2. Threat / Attack
1. Złośliwy model na publicznym hubie (HF, nazwa podobna do popularnej — typosquatting modelu) z payloadem w pickle (`__reduce__`) → RCE przy `torch.load`/`joblib.load`.
2. Skaner (picklescan) omijany: zmiana rozszerzenia, uszkodzony CRC ZIP, 7z zamiast zip, „broken pickle", nieoflagowane moduły (`pip`, podklasy asyncio).
3. Manifest-poisoning/MITM rejestru: złośliwy rejestr zwraca manifest z `digest` zawierającym `../` (CVE-2024-37032) → zapis dowolnego pliku → RCE na serwerze Ollama.
4. Złośliwy GGUF: pliki z fałszywymi nagłówkami (liczba tensorów/KV) → przepełnienie sterty w parserze ggml/llama.cpp.
5. Backdoor w wagach (zatruty fine-tune) — nie wykrywalny hashem ani skanem pickle; tylko behawioralnie.
6. Podmiana modelu po weryfikacji (TOCTOU) lub nieobecność weryfikacji — „model:latest".

## 3. Real-World Evidence
- `[REAL-ATTACK]` **JFrog (luty 2024)**: ~100 złośliwych modeli na Hugging Face (PyTorch/TF); przykład `baller423/goober2` — `__reduce__` → reverse shell na zewnętrzny IP. https://jfrog.com/blog/data-scientists-targeted-by-malicious-hugging-face-ml-models/ ; https://www.darkreading.com/application-security/hugging-face-ai-platform-100-malicious-code-execution-models
- `[REAL-ATTACK/POC]` **nullifAI (ReversingLabs, luty 2025)**: 2 modele (`glockr1/ballr7`, `who-r-u0000/…`) w PyTorch spakowanym jako 7z zamiast zip + „broken pickle" (payload na początku strumienia, błąd dalej) — ominęły Picklescan. Uznane raczej za PoC niż aktywną kampanię. https://www.reversinglabs.com/blog/rl-identifies-malware-ml-model-hosted-on-hugging-face ; https://thehackernews.com/2025/02/malicious-ml-models-found-on-hugging.html
- `[CONFIRMED-VULN]` **picklescan** (JFrog, 2 grudnia 2025): CVE-2025-10155 (rozszerzenie .bin/.pt), CVE-2025-10156 (CRC ZIP), CVE-2025-10157 (podklasa niebezpiecznego modułu), wszystkie CVSS 9.3; wcześniej CVE-2025-1716 (`pip` nie na liście; < 0.0.21). https://www.infosecurity-magazine.com/news/picklescan-flaws-expose-ai-supply ; https://osv.dev/vulnerability/CVE-2025-1716
- `[RESEARCH]` „The Art of Hide and Seek: Making Pickle-Based Model Supply Chain Poisoning Stealthy Again" (arXiv 2508.19774) — systematyczne omijanie skanerów. https://arxiv.org/html/2508.19774v1 (treść niezweryfikowana szczegółowo).
- `[CONFIRMED-VULN]` **Ollama CVE-2024-37032 „Probllama"** (Wiz, czerwiec 2024): brak walidacji formatu `digest` (sha256, 64 hex) w `/api/pull` → path traversal → arbitrary file write → RCE; fix 0.1.34; Docker: root + 0.0.0.0 domyślnie. https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032
- `[CONFIRMED-VULN]` **Ollama (Oligo 2024)**: CVE-2024-39719/39720/39721/39722 (file existence, OOB read, DoS, path traversal w push); 2 dodatkowe zakwestionowane przez maintainerów (model poisoning/theft via /api/pull,/api/push do niezaufanego rejestru). https://oligo.security/blog/more-models-more-probllms
- `[CONFIRMED-VULN]` **GGUF/ggml parser**: CVE-2024-25664 i pokrewne (Databricks, Cisco Talos/Neil Archibald, marzec 2024) — heap overflow z nagłówka GGUF; dotyczy llama.cpp, i pochodnych (Ollama, LM Studio, llama-cpp-python). https://www.databricks.com/blog/ggml-gguf-file-format-vulnerabilities (URL zwrócony wyszukiwarką w wersji /kr/; treść niepobrana). Wzmianki o „CVE-2026-7482 Bleeding Llama" (Ollama, GGUF → wyciek pamięci) pochodzą wyłącznie z agregatora — **niezweryfikowane**.
- `[MITIGATION]` **OpenSSF/Sigstore model-signing v1.0** (Google, NVIDIA, HiddenLayer, 2025): `model_signing sign/verify`, podpis całego drzewa katalogów modelu, Sigstore (keyless) lub klucze/PKI. https://blog.sigstore.dev/model-transparency-v1.0 ; https://blog.google/security/taming-wild-west-of-ml-practical-mode/
- `[MITIGATION]` Hugging Face + Protect AI Guardian / picklescan skanują huby (4M modeli w 6 miesięcy wg vendora → `[VENDOR-CLAIM]`). https://huggingface.co/blog/pai-6-month
- `[MITIGATION]` safetensors — format bez wykonania kodu, rekomendowany zamiast pickle (HF); (audyt bezpieczeństwa Trail of Bits/EleutherAI/HF 2023: niezweryfikowane w tej sesji). Ollama importuje safetensors/GGUF.

## 4. Deterministic Detection
1. **Allowlista rejestrów**: dla `/api/pull` `name` = `[host/][namespace/]model[:tag]`; domyślnie `registry.ollama.ai/library/*`; każdy inny host = BLOCK. Regex strukturalny + parser (nie regex na surowym tekście): odrzucaj `..`, `\`, `%2e`, `@`, `://`, ukośniki w nietypowych miejscach, znaki kontrolne.
2. **Allowlista modeli i digestów**: plik `models.lock.yaml` (nazwa:tag → `sha256:…` manifestu + digesty warstw). `ollama pull` zawsze po digeście (`model@sha256:…` lub weryfikacja po pull przez `/api/show`/odczyt manifestu). Digest musi pasować do `^sha256:[0-9a-f]{64}$` (dokładnie to, czego zabrakło w CVE-2024-37032).
3. **Walidacja wejścia API zarządzania**: filtr blokujący `/api/create`, `/api/push`, `/api/copy`, `/api/delete`, `/api/blobs/*` dla zwykłych klientów (rola admin-only); dla `create` zakaz `FROM` ze ścieżki/URL spoza allowlisty i zakaz `ADAPTER`/`TEMPLATE` z niezaufanego źródła.
4. **Rozpoznanie formatu po magic bytes**, nie rozszerzeniu: GGUF = `47 47 55 46` ("GGUF") + wersja; safetensors = 8-bajtowy LE rozmiar nagłówka + JSON nagłówek `{`…; ZIP = `PK\x03\x04`; pickle = `80 0N`; 7z = `37 7A BC AF 27 1C`; HDF5 = `\x89HDF`. Niezgodność rozszerzenie↔treść = QUARANTINE (por. CVE-2025-10155).
5. **Walidacja strukturalna GGUF/safetensors** przed ładowaniem: limity `n_tensors`, `n_kv`, długości stringów, offset+size ≤ rozmiar pliku, brak nakładania się tensorów (safetensors spec).
6. **Skan pickle**: allowlista GLOBAL (nie denylist) — patrz 23-unsafe-deserialization; pickle w ogóle zakazany w polityce produkcyjnej.
7. **Hash/podpis**: SHA-256 artefaktu vs lock; `model_signing verify` (Sigstore bundle lub klucz publiczny offline) przed admission.
8. **Metadane**: model card/Modelfile — skan na PI (ukryte instrukcje w SYSTEM/TEMPLATE), znaki niewidoczne (CANON).
9. **Wersja Ollamy** ≥ poprawki (SIG-003).

## 5. Detection Pipeline
(Request → Canonicalization → AuthN → Policy) → **Rules MODEL-SC** na wywołaniach API zarządzających i przy zmianie `model` w żądaniu chat (tylko modele z allowlisty) → LLM. Dodatkowo proces admission offline (skrypt `verify-models.sh` w CI/przy starcie): sprawdza `~/.ollama/models/manifests` względem lock; niezgodność → gateway w trybie degradacji (odmowa żądań do tego modelu). Output: bez wpływu (poza SIG).

## 6. Possible Actions
BLOCK: pull z nieautoryzowanego rejestru/nazwy, digest niezgodny z regexem; QUARANTINE: artefakt ze skanem „suspicious", niezgodnym typem lub bez podpisu (nie ładować, zachować do analizy); REVIEW: nowy model w allowliście (zatwierdzenie człowieka); CHALLENGE: wymagany admin token dla `/api/create`; RATE_LIMIT: pull; ALLOW: model z lock + digest zgodny.

## 7. Bypass / Limitations
- Skanery pickle: wielokrotnie obejścia (patrz wyżej) → **nie polegać na skanerze jako jedynej bramce**; format safetensors/GGUF + hash lock.
- Hash chroni przed podmianą, nie przed *od początku* złośliwym/zatrutym modelem (backdoor/trojan w wagach; sleeper agents) — wymaga zaufanego źródła i testów behawioralnych (sidecar/red-team, garak).
- Podpis Sigstore wymaga, by wydawca podpisywał; większość modeli publicznych nie jest podpisana → allowlista + własny podpis po manualnej weryfikacji.
- Model Ollama library: weryfikacja digestów względem rejestru Ollama jest TOFU (trust on first use) — pierwszy pull musi być zatwierdzony.
- GGUF: parser to kod natywny; błędy zero-day możliwe nawet dla poprawnie podpisanych plików z zaufanego źródła, jeśli źródło skompromitowane.
- FP: legalne custom Modelfile, lokalny fine-tune (nowy digest) → przepływ zatwierdzenia.
- Wydajność: hash GB-ów (np. 4–8 GB na RPi) trwa; robić jednorazowo przy admission, cache wyniku (mtime+size), nie per request.

## 8. Deterministic vs AI
Deterministycznie: rejestry, nazwy, digesty, magic bytes, struktura, podpis, wersje. AI/sidecar: ocena zachowania modelu (backdoory: testy behawioralne, wykrywanie trigger-phrases), ocena treści model card/Modelfile pod kątem injection. Skan wag pod kątem backdoorów to temat badawczy — **nie obiecywać**.

## 9. Implementation Options
- Java: `GatewayFilterFactory` na ścieżkach `/api/*` Ollamy (path+method allowlist, parser nazwy modelu, porównanie z lock); `MessageDigest` SHA-256 streamingowo; wywołanie CLI `model_signing verify` lub biblioteka sigstore-java (`dev.sigstore:sigstore-java`, Apache-2.0, niezweryfikowane wersje).
- Python sidecar/skrypt: `model-signing`, `modelscan`, `picklescan`, `fickling`, `huggingface_hub` (sprawdzenie `safetensors` metadata). Admission w CI.
- Infra: Ollama bind tylko na localhost/sieć wewnętrzną RPi, bez ekspozycji — gateway jedynym klientem (Wiz zaleca reverse proxy z auth).

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| model-signing (OpenSSF/Sigstore) | https://github.com/sigstore/model-transparency | Python | Apache-2.0 | Podpis/weryfikacja modeli | Standard, v1.0 | Wymaga podpisu przez wydawcę; Sigstore keyless wymaga sieci (tryb kluczy offline OK) | średnia | częściowo | wysoka |
| ModelScan (Protect AI) | https://github.com/protectai/modelscan | Python | Apache-2.0 | Skan serializacji | Wiele formatów | Denylist, bypassy | niska | tak | średnia |
| picklescan | https://github.com/mmaitre314/picklescan | Python | MIT | Skan pickle | Lekki | CVE-2025-1716/10155-10157 | niska | tak | niska/średnia |
| fickling | https://github.com/trailofbits/fickling | Python | LGPL-3.0 (niezweryfikowane) | Analiza pickle | AST | Wolny | średnia | tak | wysoka |
| safetensors | https://github.com/huggingface/safetensors | Rust/Py | Apache-2.0 | Bezpieczny format | Zero kodu | Tylko tensory | niska | tak | rekomendacja |
| Protect AI Guardian | https://protectai.com | — | komercyjne | Skan modeli | 35+ checków | Komercyjny — poza założeniem offline/OSS | — | nie | niska `[VENDOR-CLAIM]` |
| OSV-Scanner | https://github.com/google/osv-scanner | Go | Apache-2.0 | Podatne wersje Ollama/llama.cpp | Offline dump | Opóźnienie | niska | tak | wysoka |

## 11. Proposed Control
- MODEL-SC-001 Registry allowlist for pull/create (BLOCK)
- MODEL-SC-002 Model name+digest lock (`models.lock.yaml`) (BLOCK/QUARANTINE)
- MODEL-SC-003 Digest/name format validation (`^sha256:[0-9a-f]{64}$`, brak `..`) (BLOCK, CRITICAL)
- MODEL-SC-004 Management API protection (`/api/create|push|copy|delete|blobs`) — admin only (CHALLENGE/BLOCK)
- MODEL-SC-005 Format by magic bytes + structural validation (GGUF/safetensors) (QUARANTINE)
- MODEL-SC-006 Pickle-format ban in prod (BLOCK) + allowlist scan in dev
- MODEL-SC-007 Signature/hash verification at admission (BLOCK jeśli niezgodny)
- MODEL-SC-008 Runtime version gate (Ollama ≥ fix) — wspólne z SIG-003

## 12. Example Configuration
```yaml
- id: MODEL-SC-003
  name: Ollama pull digest/name strict validation (anti CVE-2024-37032)
  category: supply-chain
  enabled: true
  priority: 15
  scope: { direction: [input], agents: ["*"], tools: ["ollama.pull", "ollama.create"], environments: ["*"] }
  conditions: { path: ["/api/pull", "/api/create", "/api/push"] }
  matcher:
    type: structured
    fields:
      - { jsonpath: "$.name", regex: '^(?:registry\.ollama\.ai/)?(?:library/)?[a-z0-9][a-z0-9._-]{0,63}(?::[A-Za-z0-9._-]{1,64})?$' }
      - { jsonpath: "$..digest", regex: '^sha256:[0-9a-f]{64}$' }
    deny_contains: ["..", "\\", "%2e", "%2f", "://", "\u0000"]
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM03], cve: [CVE-2024-37032], references: ["https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032"] }
- id: MODEL-SC-002
  name: Model must match lockfile digest
  category: supply-chain
  enabled: true
  priority: 16
  scope: { direction: [input, session], agents: ["*"], tools: [], environments: ["prod"] }
  conditions: {}
  matcher: { type: lockfile, file: models.lock.yaml, key: "$.model", compare: manifest_sha256 }
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM03], references: ["https://blog.sigstore.dev/model-transparency-v1.0"] }
- id: MODEL-SC-005
  name: Artifact format mismatch
  category: supply-chain
  enabled: true
  priority: 17
  scope: { direction: [session], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: magic-bytes, expect_by_ext: { ".gguf": "47475546", ".safetensors": "json-header", ".pt": "504b0304", ".bin": "any-of:47475546,504b0304,json-header" }, deny_formats: [pickle, 7z] }
  action: QUARANTINE
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM03], cve: [CVE-2025-10155], references: ["https://www.infosecurity-magazine.com/news/picklescan-flaws-expose-ai-supply"] }
```

## 13. Example Requests
```json
{"req":{"method":"POST","path":"/api/pull","body":{"name":"evil.example.com/x/qwen"}},"expect":{"decision":"BLOCK","rule":"MODEL-SC-001"}}
{"req":{"method":"POST","path":"/api/pull","body":{"name":"llama3.2:3b"}},"expect":{"decision":"ALLOW","note":"jeśli w lock; w przeciwnym razie REVIEW"}}
{"req":{"method":"POST","path":"/api/pull","body":{"manifest_layer_digest":"../../etc/cron.d/x"}},"expect":{"decision":"BLOCK","rule":"MODEL-SC-003"}}
{"req":{"method":"POST","path":"/api/create","user":"guest"},"expect":{"decision":"CHALLENGE","rule":"MODEL-SC-004"}}
```

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| MODEL-SC-T001 | pull z `evil.example.com/...` | BLOCK |
| MODEL-SC-T002 | digest `../../../etc/passwd` | BLOCK |
| MODEL-SC-T003 | digest `sha256:` + 63 hex | BLOCK |
| MODEL-SC-T004 | pull modelu z lock, zgodny digest | ALLOW |
| MODEL-SC-T005 | model z lock, plik zmieniony 1 bajt | BLOCK/QUARANTINE |
| MODEL-SC-T006 | `.bin` zawierający pickle z `os.system` | QUARANTINE |
| MODEL-SC-T007 | `.pt` jako 7z (nullifAI-style) | QUARANTINE |
| MODEL-SC-T008 | `.gguf` z `n_tensors` = 2^40 | QUARANTINE (struktura) |
| MODEL-SC-T009 | `/api/create` od roli user | CHALLENGE/BLOCK |
| MODEL-SC-T010 | Ollama 0.1.30 | SIG-003 QUARANTINE |
| MODEL-SC-T011 (bypass) | Poprawny hash zatrutego modelu z backdoorem | nie wykrywalne; test behawioralny/sidecar |
| MODEL-SC-T012 (FP) | Lokalny fine-tune, nowy digest | REVIEW |

## 15. Sources
- JFrog malicious HF models — https://jfrog.com/blog/data-scientists-targeted-by-malicious-hugging-face-ml-models/ — [REAL-ATTACK]
- Dark Reading — https://www.darkreading.com/application-security/hugging-face-ai-platform-100-malicious-code-execution-models — [REAL-ATTACK]
- ReversingLabs nullifAI — https://www.reversinglabs.com/blog/rl-identifies-malware-ml-model-hosted-on-hugging-face — [POC]
- THN nullifAI (luty 2025) — https://thehackernews.com/2025/02/malicious-ml-models-found-on-hugging.html — [POC]
- picklescan zero-days — https://www.infosecurity-magazine.com/news/picklescan-flaws-expose-ai-supply ; OSV CVE-2025-1716 — https://osv.dev/vulnerability/CVE-2025-1716 — [CONFIRMED-VULN]
- Hide and Seek (arXiv 2508.19774) — https://arxiv.org/html/2508.19774v1 — [RESEARCH]
- Wiz Probllama — https://www.wiz.io/blog/probllama-ollama-vulnerability-cve-2024-37032 — [CONFIRMED-VULN]
- Oligo — https://oligo.security/blog/more-models-more-probllms — [CONFIRMED-VULN]
- Databricks GGUF — https://www.databricks.com/blog/ggml-gguf-file-format-vulnerabilities — [CONFIRMED-VULN]
- Sigstore model-signing v1.0 — https://blog.sigstore.dev/model-transparency-v1.0 ; Google — https://blog.google/security/taming-wild-west-of-ml-practical-mode/ — [MITIGATION]
- HF + Protect AI — https://huggingface.co/blog/pai-6-month — [VENDOR-CLAIM]
