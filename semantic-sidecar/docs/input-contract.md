# Kontrakt wejścia sidecara

Ustalenie z zespołem (2026-10-03): **semantic-sidecar dostaje tekst już znormalizowany.** Normalizację robi warstwa
deterministyczna po stronie gatewaya (Java), bo to kod deterministyczny, potrzebny też innym kontrolom (PII, sekrety,
sygnatury). Sidecar niczego nie normalizuje (`input.pre_normalized: true`, domyślnie).

Kontekst: wg `../../VISION.md` semantyka to wymienny provider za interfejsem Javy, a ten sidecar jest jednym z jego
adapterów. Java odpowiada za orkiestrację, politykę i decyzję. Sidecar zwraca **sygnały**, nie akcje.

> **Status integracji (2026-10-03):** gateway woła sidecara z guarda `SEM-001` (`backend/.../guard/semantic/`), na razie tylko dla wejścia (P1).
> Wysyła tekst **bez normalizacji** (normalizacji w Javie jeszcze nie ma), więc zakodowane ataki zależą od odporności samego klasyfikatora.
> Gateway traktuje `results: []`, `complete: false` i każdy błąd jako brak kontroli (domyślnie fail-closed). Zob. `docs/local-stack.md` w korzeniu repo.

## 1. Co gateway robi przed wywołaniem sidecara

Kolejność ma znaczenie: **najpierw normalizacja, potem wykrywanie PII i sekretów, potem redakcja**, bo sekret ukryty w
base64 albo zapisany homoglifami jest niewidoczny dla regexów na surowym tekście.

Referencyjna implementacja normalizacji jest w `app/normalize/` (Python). Służy jako **specyfikacja do przeniesienia do
Javy**, a nie jako część runtime sidecara. Co robi:

| Etap | Zachowanie | Uwagi |
|---|---|---|
| Znaki niewidoczne | usuń kategorie Cf i Cc (poza `\t\n\r`), selektory wariantów, kilka znaków-wypełniaczy | **Znaki tagów U+E0020..E007E kodują ukryty tekst ASCII** ("ASCII smuggling"). Warto zgłaszać je jako sygnał dla warstwy deterministycznej |
| NFKC | normalizacja Unicode | zamienia też pełnoszerokie i matematyczne litery |
| Homoglify | zamień lookalike'i cyrylicy i greki na łacinę **tylko w słowach mieszających alfabety** | czysty rosyjski/grecki tekst zostaje nietknięty |
| Encje HTML | `html.unescape` | |
| Dekodowanie base64, hex, URL, rot13, odwrócony tekst | patrz niżej | **limity obowiązkowe**: głębokość (3), liczba segmentów (16), rozmiar (50 000 znaków). Przekroczenie to sygnał, nie wyjątek |
| Deobfuskacja | leetspeak, litery rozstrzelone, ukryty tekst z HTML (komentarze, atrybuty) | tworzy wariant, nie zastępuje oryginału |

Pułapki wykryte przy implementacji (każda ma test w `tests/test_normalize.py`):
- URL: dekoduj tylko gdy zakodowano głównie bajty ASCII (percent-kodowanie chińskich znaków w adresie jest zwyczajne).
- Leetspeak: nie ruszaj `mp3`, `rot13`, `covid19` ani długich ciągów base64.
- Nie obcinaj długich tekstów po cichu: atak może leżeć za limitem.
- Normalizacja nie może wyrzucać wyjątków ani się zawieszać. Błąd normalizacji **nie może** wyglądać jak „tekst czysty".

**Wektory testowe:** `evaluation/cases/p1_obfuscation.yaml` (ataki i trudne negatywy) oraz `tests/test_normalize.py`
(oczekiwane zachowanie, w tym przypadki brzegowe). Warto przepuścić je przez implementację w Javie.

## 2. Co wysyła gateway

`POST /classify` (port **8001**, zgodnie z `VISION.md` §7):

```json
{
  "checkpoint": "P1",
  "text": "<tekst znormalizowany>",
  "context": { "session_id": "...", "task": "...", "source_trust": "user" }
}
```

`checkpoint`: `P1` prompt, `P2` dane niezaufane (RAG, narzędzia, opisy MCP, inni agenci), `P3` wywołanie narzędzia,
`P4` odpowiedź modelu, `P5` pamięć agenta. Limit: `limits.max_input_chars` (domyślnie 20 000), powyżej HTTP 413.

## 3. Co zwraca sidecar i jak to mapuje się na `ControlResult`

Propozycja mapowania (do uzgodnienia z zespołem Java):

| `ControlResult` (VISION §4) | Źródło w odpowiedzi sidecara |
|---|---|
| identyfikator polityki | `semantic.<results[].detector>` |
| wynik | `results[].score` (0-1, skalibrowany; `null`, gdy status != ok) |
| proponowana akcja | **Java**, z progu w polityce. Sidecar akcji nie proponuje |
| pewność | brak na razie. Do ustalenia, czy wystarczy `score` |
| krótki powód | `results[].label` i `reason` |
| czas wykonania | `results[].latency_ms` |
| status techniczny `ok / degraded / error` | `complete: true` = `ok`, `missing_checks` niepuste = `degraded`, brak odpowiedzi lub timeout HTTP = `error` |

**Brak wyniku nie jest wynikiem 0.** `results[].status` ma wartości `ok`, `error`, `timeout`, `skipped`. Gdy status != ok,
`score` jest `null`, a kontrola trafia do `missing_checks`. Strategię `fail-open`/`fail-closed` per kontrola wybiera Java
(`VISION.md` §4). Sidecar nigdy nie decyduje za gateway i nie powinien być jedynym punktem autoryzacji.

Dodatkowe pola odpowiedzi dla detektorów z modelem:
- `raw_score`: wynik przed kalibracją (audyt). `score` jest skalibrowany tylko, gdy w wersji detektora jest `+cal`.
- `coverage` (0-1): jaka część okien tekstu została oceniona. Poniżej 1, gdy tekst jest dłuższy niż limit okien (domyślnie 8 okien, ok. 3600 tokenów).
  Gateway może to potraktować jak ocenę częściową.
- `GET /health` zwraca listę detektorów z wersją, punktami kontroli i czasem rozgrzewki.
- `label` jest zwykle puste: sidecar nie decyduje, czy wynik jest „atakiem". Próg i akcję ustawia polityka w Javie.

`evidence` nie zawiera treści (zakres, wariant, długość, skrót HMAC), bo fragment może być sekretem. Klucz HMAC:
zmienna środowiskowa `SEMANTIC_EVIDENCE_KEY`.

## 4. Otwarte pytania do zespołu

1. **Odkodowane segmenty.** Sidecar dostaje jeden tekst. Jeśli atak jest zakodowany (base64, hex), to albo gateway
   **podstawia odkodowaną treść w miejsce zakodowanej** (jedna wartość tekstowa), albo kontrakt dostaje opcjonalne pole
   `variants: [...]` z dodatkowymi tekstami do przeskanowania. Bez żadnej z opcji sidecar tych ataków nie zobaczy.
2. **Odpowiedź modelu (P4).** Czy gateway normalizuje ją tak samo jak wejście?
3. **Redakcja przed wywołaniem.** `VISION.md` §4 wymaga redakcji sekretów i PII przed wysłaniem do providera
   zewnętrznego. Czy to samo dotyczy lokalnego sidecara?
4. **Długie teksty.** Sidecar zwraca 413 powyżej limitu. Czy gateway dzieli tekst na okna, czy przycina?
5. **`context`.** Czy gateway zna `task` (zadanie użytkownika) i `source_trust`? Bez nich kontrole dla P2/P3 są słabsze.
6. **Język.** `CRITERIA` go nie określa. Czy gateway ma bramkę języka, czy to zadanie sidecara?
