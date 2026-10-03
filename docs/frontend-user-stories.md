# Frontend: user stories (DRAFT do weryfikacji)

Backlog frontu dla AI Control Layer. Decyzje i priorytety: [`VISION.md`](../VISION.md)
(nadrzędne: [`CRITERIA AI Control Layer.pdf`](../project-spec/CRITERIA%20AI%20Control%20Layer.pdf)).
Kontrakt API i przepływy F0–F7: [`frontend-flows-and-api.md`](frontend-flows-and-api.md).

## Zasady

- Priorytety według VISION §1 i §5: **wyróżniki B (Red Team Arena) i E (Explainable Verdict / X-ray) są głównym MVP**.
  A (Data-Flow Firewall), C (Policy Time Machine), D (Policy Copilot), pełny MCP i rozbudowane role są opcjonalne.
- **P0**: bez tego nie ma demo/oceny; **P1**: wyraźnie podnosi ocenę; **Opcja**: tylko jeśli zostanie czas i backend to zrobi.
- Rozmiar: **S** (≤ 1 h), **M** (1–3 h), **L** (3–6 h).
- Każda story najpierw działa na mockach (`VITE_USE_MOCKS=true`), 1:1 z kontraktem.
  „Done na mockach” → „Done E2E”, gdy działa na żywym gatewayu.
- Audyt nie przechowuje pełnych promptów ani surowego PII (VISION §6): UI nigdy tego nie oczekuje od `/api/*`.
- Treści z modelu i logów renderujemy jako tekst (nigdy `dangerouslySetInnerHTML`).

## Persony

| Persona | Czego chce |
|---|---|
| **Juror** | wpisać własny prompt, zobaczyć dlaczego zapadła decyzja, zmienić config, zobaczyć mierzalną skuteczność |
| **Analityk security** | zdarzenia, powody decyzji, eksport logów |
| **Manager** | postawa bezpieczeństwa, zużycie, trend |
| **Admin polityk** | zmiana trybów, progów i polityki bez restartu |

---

## Epik 0. Fundamenty

### FE-01 Warstwa API zgodna z backendem · P0 · M
Jako **developer frontu** chcę typów, klienta i mocków zgodnych z tym, co backend już zwraca, żeby podłączać ekrany od razu.
- [ ] Typy dla `GuardedChatResponse` 1:1 z `backend/.../chat/GuardedChatResponse.java` + opcjonalne rozszerzenia z kontraktu §5.1.
- [ ] Enumy z VISION §4: `allow | monitor | redact | require_approval | block`, status `ok | degraded | error`.
- [ ] Klient traktuje odpowiedź `/v1/chat/completions` jako `GuardedChatResponse` także dla `400/403/502` (backend tak zwraca błędy).
- [ ] Typy i mocki dla Arena, audytu, polityki, metryk, health (§5.2–5.8).
- [ ] CSRF (`X-XSRF-TOKEN`) i globalna obsługa `401`/`403`.

### FE-02 Strumień live (SSE) · P0 · M
Jako **juror** chcę widzieć zmiany bez odświeżania strony.
- [ ] Hook `useStream()` na jednym `EventSource('/api/stream')`; wskaźnik live/łączenie/rozłączono.
- [ ] Odświeżenie danych po reconnect; w trybie mock emitter zdarzeń.

### FE-03 Nawigacja i layout · P0 · S
Jako **użytkownik** chcę prostej nawigacji między ekranami.
- [ ] Sidebar: Playground, Red Team Arena, Przegląd, Zdarzenia, Polityka (+ ekrany P1/opcje za flagą).
- [ ] Filtry i wybrane elementy w URL. Czytelne na laptopie i projektorze (≥ 1280 px).

---

## Epik 1. Logowanie

### FE-04 Logowanie przez dostawcę tożsamości · P0 · M
Jako **użytkownik dashboardu** chcę zalogować się przez Google lub Keycloak, żeby dane bezpieczeństwa widziały tylko uprawnione osoby.
- [ ] `GET /api/me` przy starcie; `401` → ekran logowania z przyciskami z `GET /api/auth/providers`.
- [ ] Redirect na dostawcę i powrót na ścieżkę, z której przyszedłem; wygasła sesja → ekran logowania bez utraty ścieżki.
- [ ] Menu użytkownika (imię, e-mail) i „Wyloguj” (`POST /logout` z CSRF).
- [ ] Na mockach przełącznik zalogowany/niezalogowany.

### FE-05 Role viewer/admin · P1 · S
Jako **viewer** nie chcę widzieć akcji, których nie mogę wykonać. (VISION §1: rozbudowane role nie są warunkiem MVP.)
- [ ] `useCan()` na podstawie `roles` z `/api/me`; viewer bez edycji polityki i bez uruchamiania Arena.

---

## Epik 2. Playground + Explainable Verdict (wyróżnik E)

### FE-06 Czat przez gateway · P0 · M
Jako **juror** chcę wpisać dowolny prompt i zobaczyć, co gateway z nim zrobił.
- [ ] `POST /v1/chat/completions` (działa w backendzie); `X-Session-Id`, przycisk „Nowa sesja”.
- [ ] Wiadomość oznaczona akcją: allow, monitor (ostrzeżenie „wykryto, przepuszczono”), redact (podświetlone `[REDACTED:…]`), require_approval/block (komunikat z `blockedBy`).
- [ ] Błędy `400` (walidacja), `403` (model spoza allowlisty), `502` (model nie odpowiada) pokazane jako czytelne decyzje, nie jako „coś poszło nie tak”.
- [ ] Stan „model myśli…” z licznikiem czasu i Anuluj (Ollama na Pi: kilka–kilkanaście s).
- [ ] Gotowe przykładowe prompty (po angielsku, jak cały UI): benign, PII, sekret, jailbreak, prompt injection.
- **Done E2E możliwe od razu**, bo endpoint istnieje.

### FE-07 Wybór modelu · P0 · S
Jako **juror** chcę przełączać model, żeby zobaczyć, że allowlista działa.
- [ ] Dropdown z `GET /api/models` (status available/unavailable). Do czasu endpointu: lista z configu frontu (dwa tagi z `application.yml`).
- [ ] Pole „wpisz inny tag”, żeby pokazać blokadę `model.allowlist`.

### FE-08 X-ray decyzji · P0 · L
Jako **juror/analityk** chcę zobaczyć, dlaczego zapadła decyzja, żeby uwierzyć, że kontrola faktycznie zadziałała (VISION §5 E).
- [ ] Ścieżka kontroli z `trace`: polityka, rodzaj (deterministyczna/semantyczna), etap (input/output), tryb, akcja, latencja, powód.
- [ ] Wykres/waterfall latencji kontroli + czas modelu + łączny narzut gatewaya.
- [ ] Sygnały: pewność vs próg, provider semantyczny, status `ok/degraded/error`; `degraded/error` wyraźnie inaczej niż „czysto”.
- [ ] Podświetlone fragmenty tekstu użytkownika (`spans`), tylko w Playground (audyt nie ma tekstu).
- [ ] Wersja i hash polityki, zużycie tokenów.
- [ ] Gdy działa shadow (P1): „aktywna: allow / shadow: block”.
- [ ] Graceful degradation: dziś backend zwraca tylko `policy/kind/action/latencyMs/detail`, więc brakujące pola po prostu się nie pokazują.
- [ ] Ten sam komponent w Zdarzeniach (FE-12) i Arena (FE-10).

---

## Epik 3. Red Team Arena (wyróżnik B)

### FE-09 Uruchomienie przebiegu Arena · P0 · M
Jako **juror** chcę jednym kliknięciem puścić korpus ataków przeciw modelowi bez ochrony i przez Control Layer, żeby zobaczyć mierzalny dowód odporności.
- [ ] Lista korpusów (`GET /api/arena/corpora`): liczba ataków i benign, kategorie.
- [ ] Wybór korpusu i modelu → `POST /api/arena/runs`.
- [ ] Postęp na żywo (SSE `arena.progress`/`arena.case` albo polling): pasek, licznik, ostatnie przypadki.

### FE-10 Wyniki Arena · P0 · L
Jako **juror/manager** chcę porównania „bez ochrony vs z ochroną” w liczbach.
- [ ] Nagłówek: ataki, które przeszły bez ochrony vs przez Control Layer (np. 31/40 → 3/40).
- [ ] **Benign-block rate** (fałszywe alarmy) jako osobny, równie widoczny wskaźnik.
- [ ] Narzut latencji p50/p95.
- [ ] Rozbicie per kategoria.
- [ ] Tabela przypadków z filtrami; kliknięcie → X-ray żądania chronionego (FE-08).
- [ ] Wersja polityki, przy której był przebieg.

### FE-11 Historia przebiegów · P1 · S
Jako **juror** chcę porównać przebiegi między wersjami polityki, żeby zobaczyć, że zmiana configu poprawiła/pogorszyła wyniki.
- [ ] Lista przebiegów (`GET /api/arena/runs`) z kluczowymi liczbami; porównanie dwóch przebiegów obok siebie.

---

## Epik 4. Audyt i reporting

### FE-12 Zdarzenia z filtrami · P0 · M
Jako **analityk security** chcę filtrować zdarzenia i widzieć powody decyzji.
- [ ] Tabela: czas, sesja, model, akcja, polityka (`blockedBy`), status, latencja, tokeny, wersja polityki.
- [ ] Filtry w URL, paginacja; nowe zdarzenia z SSE jako „N nowych”.
- [ ] Szczegóły → X-ray (FE-08) z zamaskowanymi fragmentami zamiast tekstu.

### FE-13 Eksport audytu · P0 · S
Jako **analityk** chcę eksportu CSV/JSON z bieżącymi filtrami.
- [ ] `GET /api/events/export?format=csv|json&…`.

### FE-14 Przegląd (widok zarządczy) · P0 · M
Jako **manager** chcę w jednym miejscu zobaczyć ruch, blokady, zużycie i wydajność.
- [ ] Kafelki: żądania, allow/monitor/redact/block, degraded/error, tokeny vs limit, p50/p95.
- [ ] Trend decyzji w czasie, top polityk według trafień.
- [ ] Stan kontrolek: ile enforced / monitor / off / degraded (zamiast wymyślonego „wyniku bezpieczeństwa”).
- [ ] Ostatni wynik Arena jako kafelek z linkiem.
- [ ] Odświeżanie przez SSE.

---

## Epik 5. Status systemu i polityka

### FE-15 Pasek statusu · P0 · M
Jako **juror** chcę na każdym ekranie widzieć aktywną politykę i stan zależności.
- [ ] Wersja i hash polityki (+ wersja shadow, gdy działa).
- [ ] Gateway, provider semantyczny (nazwa, lokalny/zewnętrzny), Ollama, baza: `ok/degraded/error`.
- [ ] Wskaźnik live, menu użytkownika.

### FE-16 Baner zmiany polityki · P0 · S
Jako **juror** edytujący `policy.yaml` chcę natychmiast zobaczyć, czy zmiana weszła.
- [ ] `policy.activated` → zielony baner z listą zmienionych kontrolek; `policy.rejected` → czerwony, nie znika sam, „aktywna pozostaje vN”.

### FE-17 Katalog kontrolek · P0 · M
Jako **juror/admin** chcę widzieć wszystkie kontrolki z trybem, progiem i stanem i móc je szybko przełączać.
- [ ] Tabela: nazwa, rodzaj, etap, tryb (off/monitor/redact/require_approval/block), próg, fail-open/closed, status, trafienia, p95.
- [ ] Zmiana trybu/progu inline (`PATCH /api/controls/{id}`) → nowa wersja → baner FE-16.
- [ ] Przełącznik profilu permissive/balanced/strict (VISION §4).

### FE-18 Edytor polityki · P0 · M
Jako **admin polityk** chcę edytować politykę i dostać walidację na bieżąco.
- [ ] `textarea` z numerami linii (edytor z podświetlaniem składni to upiększenie na koniec).
- [ ] Walidacja w trakcie pisania z błędami przy liniach; „Zastosuj” z `baseVersion`, obsługa `409`.
- [ ] Lista wersji (kto, kiedy, skąd).

### FE-19 Shadow nowej polityki · P1 · M
Jako **admin polityk** chcę uruchomić nową wersję w trybie shadow, żeby zobaczyć jej decyzje obok aktywnych bez wpływu na ruch (VISION §4).
- [ ] „Uruchom jako shadow” / „Zakończ shadow” / „Awansuj na aktywną”.
- [ ] Licznik rozbieżności aktywna vs shadow na Przeglądzie; rozbieżne żądania w Zdarzeniach.

### FE-20 Budżety · P1 · S (kafelek w FE-14 jest P0)
Jako **manager** chcę widzieć zużycie tokenów względem limitów.
- [ ] Paski per zakres (global/model/wywołujący); `block` z `budget.*` widoczny w Playground.

---

## Epik 6. Opcjonalne wyróżniki (tylko jeśli backend je zrobi)

| Story | Wyróżnik | Zakres frontu |
|---|---|---|
| FE-21 · Opcja · L | A, Data-Flow Firewall | lista sesji + graf (React Flow) z etykietami taint, węzły na żywo |
| FE-22 · Opcja · M | C, Policy Time Machine | „Podgląd skutków” w edytorze: ile decyzji by się zmieniło |
| FE-23 · Opcja · S | D, Policy Copilot | w szczegółach zdarzenia „Zaproponuj regułę” → diff do zatwierdzenia |
| FE-24 · Opcja · M | pełny MCP | tool call z edytorem argumentów w Playground |

---

## Kolejność realizacji

| Krok | Stories | Efekt |
|---|---|---|
| 1 | FE-01, FE-02, FE-03 | fundament; typy już zgodne z działającym backendem |
| 2 | FE-06, FE-07, FE-08 | **Playground + X-ray na żywym backendzie** (endpoint działa) |
| 3 | FE-04 | logowanie |
| 4 | FE-09, FE-10 | **Red Team Arena** (na mockach, dopóki backend nie da `/api/arena`) |
| 5 | FE-15, FE-16 | pasek statusu, reakcja na zmianę polityki |
| 6 | FE-12, FE-13, FE-14 | audyt, eksport, Przegląd |
| 7 | FE-17, FE-18 | polityka i kontrolki |
| 8 | FE-11, FE-19, FE-20, FE-05 | P1 |
| 9 | FE-21..24 | opcje |

P0 to ok. 20–24 h pracy jednej osoby. Jeśli trzeba ciąć: FE-14 bez trendu, FE-18 bez listy wersji,
FE-10 bez rozbicia per kategoria. **Nie ciąć:** FE-06/08 (E) i FE-09/10 (B), bo to główna narracja prezentacji.

## Zależności od backendu

| Endpoint | Potrzebny w | Status |
|---|---|---|
| `POST /v1/chat/completions` | FE-06..08 | ✅ działa (allowlista + Ollama); rozszerzenia trace ⏳ |
| `/api/me`, `/api/auth/providers`, `/logout`, OAuth2 | FE-04 | ⏳ |
| `/api/stream` (SSE) | FE-02, FE-10, FE-12, FE-14..16 | ⏳ |
| `/api/models` | FE-07 | ⏳ (dane są w `ModelCatalog`) |
| `/api/arena/*` | FE-09..11 | ⏳ (+ decyzja, jak oceniać atak bez ochrony) |
| `/api/health` | FE-15 | ⏳ (jest `/actuator/health`) |
| `/api/events*` | FE-12, FE-13 | ⏳ |
| `/api/metrics/*` | FE-14 | ⏳ |
| `/api/policy*`, `/api/controls` | FE-16..19 | ⏳ (czeka na `policy.yaml`, VISION §9 krok 1) |
| `/api/budgets` | FE-20 | ⏳ |
