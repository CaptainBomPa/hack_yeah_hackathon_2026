# Polityki w bazie + zarządzanie z UI

**Status: zaimplementowane** (decyzja: baza jako jedyne źródło prawdy). Poniżej plan, według którego powstało.

Cel: admin widzi aktywną politykę, zmienia ją w prostym formularzu, a po zapisie **następne
żądanie** działa już według nowej wersji — bez restartu. Wymagania: CRITERIA §4.1 (jedna
konfiguracja: kontrole, progi, dozwolone modele, budżety), §6 (jury zmienia konfigurację na żywo),
VISION §4 (polityka jako dane, wersja + hash, atomowe przeładowanie, błędna wersja nie zastępuje
ostatniej poprawnej).

## 1. Stan obecny

Konfiguracja jest w trzech miejscach i **każda zmiana wymaga restartu backendu**:

| Co | Gdzie | Kto czyta | Kiedy |
|---|---|---|---|
| Role → dozwolone modele, dzienny budżet tokenów | `backend/config/policy.yaml` (`PolicyProperties`) | `ModelAccessPolicy`, `BudgetGate`, `DashboardService`, `SeedUsers`, `AddUserCommand` | raz, przy starcie |
| Katalog modeli (tag, base URL, enabled), timeout | `application.yml` (`ModelCatalogProperties`) | `ModelCatalog` | raz, przy starcie |
| Guardy: włączony, kolejność, parametry (PII: próg, wyłączone/blokujące/monitorowane recognizery; SEM-001: próg, timeout, fail-open/closed) | `application.yml` (`GuardProperties`) | `GuardChain` buduje łańcuch w konstruktorze | raz, przy starcie |
| Limity wejścia/wyjścia (tokeny) | `application.yml` (`BudgetLimitsProperties`) | `BudgetGate` | raz, przy starcie |
| Definicje recognizerów PII (regexy, walidatory) | `rules/pii/recognizers.yaml` | `PiiRecognizerGuard` | raz, przy starcie |

Dobra wiadomość: guardy dostają `GuardSettings` przy **każdym** wywołaniu i są bezstanowe, więc
podmiana konfiguracji w locie nie wymaga zmian w samych guardach — tylko w tym, skąd
`GuardChain` bierze ustawienia.

## 2. Co trafia do polityki (a co nie)

**Polityka (edytowalna z UI, wersjonowana):**
- **role**: dozwolone modele (lista albo „wszystkie”), dzienny limit tokenów (puste = bez limitu);
- **modele**: które tagi z katalogu są włączone (allowlista);
- **guardy**: per id — włączony, kolejność, parametry (zob. §5.3);
- **limity żądania**: max tokenów wejścia, max tokenów wyjścia.

**Zostaje poza polityką (infrastruktura / dane):**
- base URL modeli i sidecara, timeouty sieciowe — konfiguracja wdrożenia (env), nie decyzja bezpieczeństwa;
- konta użytkowników (`app_user`, `users.yaml`) — osobny temat;
- definicje recognizerów PII (`recognizers.yaml`) — to „feed” danych; z UI tylko włączanie/wyłączanie
  i akcja per recognizer, bez edycji regexów (ReDoS, walidacja — za ryzykowne na hackathon).

## 3. Model danych

Jedna tabela, **append-only** jak audyt — każda zmiana to nowa wersja, aktywna = najnowsza:

```sql
-- V5__policy_version.sql
CREATE TABLE policy_version (
    version     BIGINT PRIMARY KEY,           -- 1, 2, 3…
    document    TEXT         NOT NULL,        -- cała polityka jako JSON (kanoniczny)
    hash        VARCHAR(64)  NOT NULL,        -- SHA-256 dokumentu
    author      VARCHAR(100) NOT NULL,        -- login admina albo "seed"
    source      VARCHAR(20)  NOT NULL,        -- seed | ui | import | restore
    comment     VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
-- + trigger blokujący UPDATE/DELETE (jak audit_event)
ALTER TABLE audit_event ADD COLUMN policy_version BIGINT;   -- która wersja zdecydowała
```

**Dlaczego jeden dokument JSON, a nie znormalizowane tabele:** wersjonowanie i atomowość za
darmo (jedna wersja = jeden wiersz), łatwy diff między wersjami, eksport/import YAML 1:1, a
parametry guardów różnią się kształtem. Struktura jest pilnowana typami w Javie + walidacją, nie schematem SQL.

**Rollback** = nowa wersja z treścią starej (`source: restore`) — historia nigdy nie jest przepisywana.

**Pierwszy start:** pusta tabela → wersja 1 budowana z obecnych plików (`policy.yaml` + sekcje z
`application.yml`), `source: seed`. Dzięki temu migracja jest bezbolesna, a pliki zostają jako
wartości startowe (i dokumentacja).

## 4. Backend

### 4.1 Rdzeń

- `PolicyDocument` — rekord Javy odpowiadający dokumentowi (roles, models, guards, limits).
- `PolicyStore` — trzyma aktywną politykę w `AtomicReference<ActivePolicy(version, hash, document)>`:
  - przy starcie czyta najnowszą wersję z bazy (albo seeduje wersję 1);
  - `current()` — snapshot dla żądania; **żądanie bierze snapshot raz na początku**, więc w trakcie
    jednego żądania polityka się nie zmienia;
  - `apply(document, baseVersion, author, comment)` — waliduje → zapis nowej wersji w transakcji →
    podmiana referencji dopiero po commicie (błędna wersja nigdy nie staje się aktywna).
- `PolicyValidator` — zwraca listę błędów z ścieżką pola (`roles.chat.models[1]`):
  - model w roli musi istnieć w katalogu; id guarda musi odpowiadać beanowi `Guard`;
  - progi w 0–1, limity ≥ 0, kolejność > 0, `failureMode` ∈ {open, closed};
  - **nie można usunąć roli, którą mają konta w `app_user`** (użytkownicy straciliby dostęp);
  - **rola `admin` musi istnieć** (inaczej nikt nie odzyska panelu).
- Konsumenci przechodzą z `@ConfigurationProperties` na `PolicyStore.current()`: `ModelAccessPolicy`,
  `BudgetGate`, `ModelCatalog` (flaga enabled), `GuardChain` (łańcuch budowany per wersja i cache'owany),
  `DashboardService`, `SeedUsers`, `AddUserCommand`.
- Audyt: `policy_version` w każdym rekordzie; `GuardedChatResponse.policyVersion` / `policyHash`
  (frontend już to wyświetla w X-ray).

### 4.2 API (rola ADMIN)

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/policy` | aktywna wersja: `{ version, hash, author, createdAt, document, catalog }` — `catalog` to co UI może wybrać: tagi modeli, guardy z opisem parametrów, recognizery PII, role używane przez konta |
| POST | `/api/policy/validate` | `{ document }` → `{ valid, errors: [{ path, message }] }` — walidacja na sucho, do formularza |
| PUT | `/api/policy` | `{ baseVersion, document, comment }` → `200` nowa aktywna wersja / `409` ktoś zapisał w międzyczasie / `422` błędy walidacji |
| GET | `/api/policy/versions` | historia: wersja, autor, czas, źródło, komentarz, hash |
| GET | `/api/policy/versions/{v}` | dokument wersji (do podglądu i diffu) |
| POST | `/api/policy/versions/{v}/restore` | przywrócenie = nowa wersja z treścią `v` |
| GET | `/api/policy/export` | aktywna polityka jako YAML (do pobrania) |
| POST | `/api/policy/import` | YAML → walidacja → zapis jak PUT (`source: import`) |

## 5. Frontend — ekran Policies

### 5.1 Nagłówek
Aktywna wersja (`v7 · 3f2a9c1`), kto i kiedy zapisał, przycisk **History**.

### 5.2 Formularz (sekcje, nie surowy YAML)

1. **Roles & model access** — tabela: wiersz = rola, kolumny = modele (checkboxy) + „all models”,
   pole „Daily token budget” (puste = unlimited). Role z kontami oznaczone („3 users”) — nie do usunięcia.
2. **Models** — lista tagów z przełącznikiem enabled; base URL tylko do podglądu.
3. **Guards** — karta per guard: przełącznik, kolejność, parametry:
   - **PII-RECOGNIZERS**: suwak progu (0–1); tabela recognizerów (PESEL, IBAN, karta…) z akcją
     per recognizer: *redact / block / monitor / off*;
   - **SEM-001**: próg blokady, timeout (ms), tryb awarii *fail-closed / fail-open*
     (z ostrzeżeniem, że fail-open przepuszcza ruch przy awarii sidecara).
4. **Request limits** — max input / output tokens.

### 5.3 Zapis
- Pasek na dole: „3 unsaved changes”, **Review & save** → podgląd diffu (stara → nowa wartość
  per pole) + komentarz → zapis.
- Walidacja na bieżąco (`/api/policy/validate`, debounce), błędy przy polach.
- `409` → „Someone saved v8 in the meantime” + przeładuj / pokaż różnice.
- Po zapisie: baner „Policy v8 is active — applies to new requests”. Dowód działania: X-ray w
  Playground pokazuje `policy v8`, rekord w audycie ma `policy_version`.

### 5.4 Historia
Lista wersji (autor, czas, komentarz, źródło), podgląd diffu względem poprzedniej, **Restore**.

### 5.5 Advanced
Podgląd YAML aktywnej wersji, **Export YAML**, **Import YAML** (wklej/plik → walidacja → zapis) —
dla jury, które woli edytować plik (CRITERIA §6 mówi o modyfikowaniu plików konfiguracji).

## 6. Testy

- `PolicyValidator`: każdy błąd z §4.1 (nieznany model, nieznany guard, próg poza zakresem,
  usunięcie roli z kontami, brak admina).
- Integracyjne (H2): zapis zmienia **następne** żądanie — rola traci model → 403; zmniejszony
  budżet → 429; wyłączony PII → brak redakcji; próg semantyczny; błędny dokument → 422 i stara
  wersja dalej aktywna; dwa zapisy z tym samym `baseVersion` → drugi 409; restart → aktywna
  ostatnia wersja; rekord audytu ma właściwy `policy_version`.
- Front: formularz → diff → zapis → baner; konflikt 409.

## 7. Kolejność i szacunek

| Krok | Zakres | ~ |
|---|---|---|
| 1 | Migracja V5 + `PolicyDocument` + `PolicyStore` (seed z plików) + walidator | 2 h |
| 2 | Przepięcie konsumentów na `PolicyStore` + `policy_version` w audycie i odpowiedzi | 2 h |
| 3 | API (`GET/PUT/validate/versions/restore`) + testy integracyjne | 2 h |
| 4 | Front: formularz (role/modele/guardy/limity) + zapis z diffem | 3 h |
| 5 | Historia + restore, eksport/import YAML | 1,5 h |
| 6 | Aktualizacja VISION §4 (polityka w bazie, nie w `policy.yaml`) i `docs/auth` (D3) | 0,5 h |

## 8. Decyzje do potwierdzenia

1. **Baza jako jedyne źródło prawdy** (pliki tylko jako seed przy pierwszym starcie + eksport/import),
   zamiast obserwowania pliku na dysku. To zmienia VISION §4 („docelowo `policy.yaml`”) i
   `docs/auth` D3. Alternatywa: plik zostaje źródłem, UI zapisuje do pliku — prostsze dla jury
   edytującego plik, ale bez wersjonowania i autora zmian, i z ryzykiem wyścigu przy dwóch edytorach.
2. **Tworzenie nowych ról z UI** — w MVP tylko edycja istniejących ról i dodanie nowej (bez kont
   nic nie robi); przypisywanie kont do ról to osobny ekran (poza zakresem).
3. **Tryb shadow** (VISION §4: nowa polityka raportowana obok aktywnej) — po tym planie, bo opiera
   się na tym samym `PolicyStore` (druga referencja „shadow”).
4. **`SEMANTIC_GUARD_ENABLED` z docker-compose** — po migracji to tylko wartość startowa wersji 1;
   późniejsze włączanie/wyłączanie SEM-001 robi się w UI.
