# Jak napisać regułę (guard)

Reguła deterministyczna to **jedna klasa Javy** implementująca `Guard` (pakiet `pl.hackyeah.controllayer.guard`).
YAML tylko ją włącza i podaje parametry. Opis zachowania każdej kontroli jest w jej case file (`01`–`27`),
tu jest tylko mechanika.

## 1. Jak to działa

```
POST /v1/chat/completions
 → allowlista modeli
 → GuardChain.run(INPUT, każda wiadomość)   Block → 403 · Redact → zmieniona treść idzie do modelu
 → model
 → GuardChain.run(OUTPUT, odpowiedź)        Block → 403 · Redact → zmieniona odpowiedź
```

`GuardChain` zbiera wszystkie beany `Guard`, bierze te włączone w YAML dla danego `Stage` i odpala je po kolei
(rosnąco wg `order`, potem wg `id`). `Allow` → następny guard. `Redact` → tekst jest podmieniany i następne guardy
widzą już zredagowaną wersję. `Block` → łańcuch kończy się. Wyjątek w guardzie jest traktowany jak `Block`.

## 2. Krok po kroku

1. **Wybierz `id`** z case file, np. `PII-002`. To klucz w YAML i nazwa polityki w `trace`.
2. **Utwórz klasę** w `guard/<kategoria>/` (np. `guard/pii/NipGuard.java`), dodaj `@Component`, `implements Guard`.
3. **Zwróć `stages()`**: `INPUT` (wiadomości od klienta), `OUTPUT` (odpowiedź modelu), `TOOL_CALL` (argumenty/wynik narzędzia).
4. **Zaimplementuj `check(ctx, settings)`** i zwróć `Verdict`.
5. **Włącz w `application.yml`**:
   ```yaml
   control-layer:
     guards:
       rules:
         PII-002: { enabled: true, order: 110, params: { someThreshold: 0.7 } }
   ```
   Guard bez wpisu jest wyłączony. Klucz bez pasującego guarda przerywa start aplikacji (literówka w id nie przejdzie niezauważona).
   `control-layer.guards.enabled: false` wyłącza wszystko.

## 3. Szablon

```java
@Component
public class NipGuard implements Guard {

    @Override public String id() { return "PII-002"; }

    @Override public Set<Stage> stages() { return Set.of(Stage.INPUT, Stage.OUTPUT); }

    @Override
    public Verdict check(GuardContext ctx, GuardSettings settings) {
        String text = ctx.text();
        // ... wykryj, zwaliduj ...
        if (nothingFound) return Verdict.allow();
        return new Verdict.Redact(redactedText, "2 NIP");   // detail trafia do trace – bez surowych wartości
        // albo: return new Verdict.Block("powód");
    }
}
```

Działający przykład: `guard/pii/PeselGuard.java` (PII-001: regex 11 cyfr + checksum + data urodzenia).

## 4. `Verdict` – kiedy co

| Verdict | Kiedy | Uwagi |
|---|---|---|
| `Allow` | brak trafień albo trafienie poniżej progu | `Verdict.allow()` |
| `Redact(newText, detail)` | wartość do zamaskowania, reszta treści bezpieczna | placeholder `[REDACTED:<id>]`; nie zmieniaj struktury (JSON: tylko wartość) |
| `Block(reason)` | twarde naruszenie (bulk PII, klucz prywatny, SSRF…) | `reason` trafia do `trace`; nie podawaj wzorca ani progu reguły |

Czego jeszcze nie ma: `RATE_LIMIT`, `QUARANTINE`, `REVIEW`, `CHALLENGE` z `decision-model.md`. Gdy będą potrzebne,
dodaje się nowy wariant do `Verdict` i obsługę w `GuardChain`; istniejące guardy się nie zmieniają.

## 5. Parametry

`settings.params()` to mapa z YAML. Helpery: `settings.doubleParam("threshold", 0.6)`, `settings.intParam("max", 10)`.
**Zawsze podawaj wartość domyślną w kodzie** – guard ma działać bez `params`.

## 6. Zasady

- **Bezstanowość i thread-safety.** Jeden bean obsługuje wiele żądań naraz. Wzorce kompiluj raz (`static final Pattern`).
- **Nigdy nie loguj ani nie wkładaj do `detail` surowej wartości** (PESEL, klucza). Liczba trafień i typ wystarczą;
  docelowo HMAC (case 01 §5, case 27).
- **Regexy bez zagnieżdżonych kwantyfikatorów** (ReDoS). Przy bardziej złożonych wzorcach użyj RE2J (jeszcze niedodane do `build.gradle`).
- **Guard nie wywołuje IO w pętli.** Jest odpalany na wątku `boundedElastic`, ale nadal wpływa na latencję (cel: <10 ms p95).
- **Fail-closed.** Nie łap wyjątków „żeby przepuścić" – niech trafią do `GuardChain`, który zablokuje.
- **Jeden guard = jedna reguła z case file.** Wspólne rzeczy (walidatory checksum, kanonikalizacja) wydzielaj do klas pomocniczych w tym samym pakiecie.

## 7. Checklista przed PR

- [ ] `id` zgodne z case file, dodane do `application.yml`.
- [ ] Test pozytywny i negatywny z wektorami z sekcji „Testing” case file (np. `PII-T001`, `PII-T002`).
- [ ] Wpis w `test-catalog.md`, jeśli dodajesz nowy przypadek.
- [ ] Brak surowych wartości w logach i `trace`.

## 8. Powiązane

[decision-model.md](decision-model.md) (semantyka decyzji, w tym te jeszcze niezaimplementowane).

## 9. Pipeline i zasady

Docelowa kolejność przetwarzania żądania:

1. limity na wejściu (body size, odrzucenie `Content-Encoding`, timeout, bulkhead – LIMIT),
2. kanonikalizacja (strict UTF-8, NFKC, Unicode Tags/zero-width/bidi – CANON),
3. uwierzytelnienie (AUTHN),
4. policy (model/narzędzia/tenant – AUTHZ, TENANT),
5. guardy `INPUT`,
6. model,
7. guardy `OUTPUT` (streaming z hold-backiem).

Wywołania narzędzi/MCP i stan sesji to osobna ścieżka (`TOOL_CALL`): schema, ścieżki, URL, komendy, integralność definicji, sekwencje.

Zasady projektowe:

- **Canonicalize first** – regexy działają na widokach po kanonikalizacji; bez niej char-injection obchodzi guardraile (arXiv 2504.11168).
- **Allowlist > denylist** (komendy, rejestry, narzędzia MCP).
- **Deny-by-default i deny-overrides** – nieznane narzędzie traktuj jak egress.
- **Fail-closed** dla reguł bezpieczeństwa i kosztów.
- **Sidecar semantyczny = sygnał**, nie bramka; twarda decyzja jest deterministyczna.
- **Audit bez surowych danych** – HMAC z kluczem serwera, nie gołe SHA-256 (przestrzeń PESEL jest mała).
- **Streaming:** hold-back buffer ≥ najdłuższy wzorzec CRITICAL; wysłanego tekstu nie cofniesz.
- **Cel:** <10 ms p95 dla ścieżki deterministycznej poza streamingiem.

Co jeszcze nie jest zaimplementowane: `scope` reguł, `mode: monitor/shadow`, `exceptions`, `on_error` per reguła,
hot-reload konfiguracji, decyzje `RATE_LIMIT` / `QUARANTINE`.
