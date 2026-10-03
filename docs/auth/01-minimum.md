# Auth — wersja do akceptacji zespołu (lokalna)

Status: **propozycja do akceptacji**. Wykonanie: [`02-plan-wdrozenia.md`](02-plan-wdrozenia.md).
Architektura: [`VISION.md`](../../VISION.md), sekcje 1, 2, 3, 4, 7, 8.

## Problem

Gateway ma wpuszczać tylko zidentyfikowanych callerów i egzekwować, do czego każdy z nich ma
dostęp: modele, narzędzia MCP i źródła pamięci. Cztery grupy ruchu:

| Kto | Jak wchodzi | Co robi | Tożsamość |
|---|---|---|---|
| Agenci i SDK | `/v1/**` | wołają model przez kontrole | klucz API per agent |
| Runner testów, jury | `/v1/**` | odpala suite, prompty ad hoc | klucz API demo, `CL_API_KEY` |
| Osoba z czatu | przeglądarka, `/v1/**` przez UI | pisze prompty | konto lokalne, rola `chat` |
| Admin | przeglądarka, `/api/**` | polityki, audyt, dashboard | konto lokalne, rola `admin` |

## Wymagania z PDF, które to uzasadniają

| Wymaganie | Źródło | Jak spełnia plan |
|---|---|---|
| Kontrola „authentication or access requirements” jako deterministyczna | CRITERIA §4.2.1 | klucze API i konta lokalne przed wywołaniem modelu |
| Jedna konfiguracja: kontrole, modele, budżety | CRITERIA §4.1 | uprawnienia w `policy.yaml`, nie w bazie |
| Dostęp do zasobów: modele, narzędzia, pamięć | CRITERIA §1 („access resources they shouldn't access”) | `allow`/`deny` per principal dla trzech typów zasobów |
| Test suite uruchamiany przez jury bez przygotowania | CRITERIA §6 | klucz demo i konta demo opisane w README |
| Działanie na własnym sprzęcie, bez płatnych usług | CRITERIA §7 | brak SSO i hostowanego IdP |
| Tożsamość agentów, odwołanie dostępu | CRITERIA §1 („impersonate other actors”) | klucz per agent, `revoked_at`, `X-Caller-Id` ustawiany przez gateway |
| Audyt: kto co zrobił | CRITERIA §4.5 | principal zapisywany w audycie |

Dosłownie PDF nie wymaga ani SSO, ani lokalnych kont. Wymaga kontroli dostępu i działania bez
zewnętrznych usług. Plan wynika z tych wymagań, a nie z ich dosłownego brzmienia.

## Decyzje

| ID | Decyzja | Dlaczego |
|---|---|---|
| D1 | **Tożsamość maszyn: klucze API** w Postgres (hash SHA-256, prefiks, `revoked_at`). Klucz nie niesie uprawnień. | Działa offline, jury wchodzi bez konfiguracji, odwołanie bez restartu. |
| D2 | **Tożsamość ludzi: konta lokalne** w Postgres (hasło BCrypt), logowanie formularzem, sesja w cookie. Bez Google i bez OAuth. | Zero-preparation dla jury; brak tunelu i redirectów. |
| D3 | **Uprawnienia w jednej polityce `policy.yaml`** (sekcja `principals`): `allow`/`deny` dla modeli, narzędzi MCP i źródeł pamięci. Deny wygrywa, brak wpisu = odmowa. Hot reload. | Spełnia §4.1 (jedna konfiguracja) i §6 (jury zmienia config na żywo). |
| D4 | **Deployment: cały stack na Raspberry Pi** w jednej sieci docker. Klienci wchodzą przez adres Pi w sieci, w której stoi Pi. Tunel HTTPS opcjonalnie, tylko dla dostępu spoza tej sieci. | Brak tunelu jest możliwy, bo nie ma redirectów zewnętrznych. |
| D5 | **Logowanie i klucze działają bez internetu.** Internet tylko dla zewnętrznych LLM-ów. | Zgodne z §7 i z VISION §1 (działanie bez płatnych usług). |
| D6 | **Priorytet:** klucze API i `deny` w polityce najpierw. Formularz logowania i panel admina po nich. | Najwięcej punktów (Robustness, Self-Testing) zależy od blokowania ruchu. |

## Poza zakresem MVP

- SSO, Google, OAuth i hostowany IdP;
- OAuth dla agentów (client credentials) i resource server JWT;
- zarządzanie użytkownikami z UI (konta tworzone przy starcie z env);
- automatyczne wygasanie kluczy API;
- wieloinstancyjne sesje.

## Co musi zrobić zespół (poza kodem)

- **Narzędzia MCP i źródła pamięci w MVP:** dziś ich nie ma (VISION §1: MCP nie jest warunkiem MVP).
  Bez nich uprawnienia do narzędzi i pamięci są puste. Trzeba zdefiniować demo-narzędzie (np.
  `demo.search`, `demo.exec`) i demo-zasób pamięci, żeby testy negatywne miały co blokować.
- **Jedna lista modeli:** `control-layer.models` jest w `application.yml`, a plan chce trzymać
  listę w `policy.yaml`. Trzeba wybrać jedno źródło (CRITERIA §4.1).
- **Hasła i klucze demo** w README do prezentacji, ustawiane zmiennymi env, nie w repo.
- **Test sieci konferencyjnej:** czy klienci widzą Pi (izolacja klientów Wi-Fi bywa włączona).
- **Model Pi:** 4 GB czy 8 GB RAM. Od tego zależy, czy zmieszczą się Ollama, backend i baza.

## Ryzyka

| Ryzyko | Skutek | Ograniczenie |
|---|---|---|
| Sieć konferencyjna izoluje klientów | jurorzy nie wejdą na Pi | test przed prezentacją; plan B: opcjonalny tunel HTTPS, auth bez zmian |
| Słabe hasła demo | ktoś przejmie konto czatu lub admina | hasła z env, limit prób logowania, konta demo bez dostępu do danych |
| Cookie bez flagi `Secure` na HTTP | słabsza ochrona sesji | flaga przez `AUTH_COOKIE_SECURE`, `true` tylko za HTTPS |
| Brak narzędzi i pamięci w MVP | testy negatywne tylko na modelach | demo-narzędzia i demo-zasób (patrz wyżej) |
| Termin: zgłoszenie 4.10.2026, 23:00 | niedokończony formularz logowania | zostają klucze API i `deny` w polityce; formularz jest dodatkiem |

## Akceptacja

Każda osoba z zespołu zaznacza swoją decyzję w poniższej tabeli (wpis w PR albo komentarz):

| ID | Akceptuję | Uwagi |
|---|---|---|
| D1 klucze API dla maszyn, bez uprawnień w bazie | [ ] | |
| D2 konta lokalne i formularz dla ludzi, bez Google | [ ] | |
| D3 uprawnienia w `policy.yaml` (allow/deny) | [ ] | |
| D4 cały stack na Pi, bez obowiązkowego tunelu | [ ] | |
| D5 logowanie i klucze offline | [ ] | |
| D6 priorytet: najpierw klucze API i `deny` | [ ] | |
