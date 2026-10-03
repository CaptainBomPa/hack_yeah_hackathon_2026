# Analiza wyzwania HubMI.pl — plan podejścia

> Analiza na podstawie `project-spec/CRITERIA Wojewodztwo Malopolskie HUBMI.pdf` oraz `project-spec/RULES Wojewodztwo Malopolskie HUBMI.pdf`.

## O co chodzi w wyzwaniu

Zamawiający to **ROPS Kraków** (Regionalny Ośrodek Polityki Społecznej). Chcą platformę cyfrową — "cyfrowe serce" **Małopolskiego Hubu Innowacji Społecznych** (roboczo **HubMI.pl**). Platforma ma łączyć zgłaszane problemy społeczne z istniejącymi rozwiązaniami i wspierać cały cykl życia innowacji społecznej: diagnozowanie → tworzenie → testowanie → upowszechnianie → budowanie partnerstw.

Grupy użytkowników: mieszkańcy/NGO, jednostki samorządu (JST), pracownicy ROPS (admini), eksperci branżowi.

## Moduły (z wyraźnym rozróżnieniem priorytetów)

| # | Moduł | Status | Punktacja |
|---|-------|--------|-----------|
| I | **Matchmaking społeczny** — opis problemu → system szuka podobnych przypadków i proponuje gotowe innowacje | **OBOWIĄZKOWY** | +10% |
| II | Zasobnik wiedzy — biblioteka innowacji, mapa wyzwań, materiały; agregacja trendów (widok admina) | opcjonalny | +5% |
| III | Kreator pomysłów — fiszki pomysłów + generator wniosków grantowych + AI-asystent (podpowiada, wizualizuje) | opcjonalny | +5% |
| IV | Tester innowacji — zgłaszanie się do testów, oceny, feedback | opcjonalny | +5% |
| V | Platforma komunikacji — dialog ROPS ↔ użytkownicy, mentorzy | opcjonalny | +5% |
| VI | Panel administratora — weryfikacja i udostępnianie wiedzy | opcjonalny | +5% |
| VII | Middleman Innowacji — AI dostosowujący innowację do formy usługi wg potrzeb instytucji | opcjonalny | +5% |

## Jak jest punktowane (kluczowe dla strategii)

Ocena w skali 1–10, średnia ważona:

- **40% — Stopień spełnienia wyzwania**: jakość kluczowych funkcji + liczba dodatkowych modułów. Obowiązkowy matchmaking = 10%, każdy kolejny moduł = +5%. Aby wyciągnąć pełne 40% trzeba oprócz matchmakingu dowieźć ~6 dodatkowych modułów działających dobrze.
- **20% — Potencjał wdrożeniowy**: skalowalność, elastyczność, efektywność kosztowa, prostota utrzymania.
- **20% — Dostępność i intuicyjność**: WCAG 2.1 AA, użyteczne dla seniorów i osób z niepełnosprawnościami, dla różnych poziomów umiejętności cyfrowych.
- **20% — Kryteria premiujące**: 10% atrakcyjność/pomysłowość/jakość UI (nieszablonowość!), 10% jakość materiałów i MVP (prezentacja, komunikacja koncepcji).

Próg laureata: **min. 50% maksymalnej punktacji**.

### Wnioski ze struktury punktów

1. **60% punktów (40+20) zależy od funkcjonalności i wdrożeniowości** — ale pozostałe **40% to UX, dostępność, prezentacja i "wow"**. Łatwo przecenić kodowanie, a przegrać na prezentacji i dostępności. Zespoły z dobrym demo + makietą + ładną prezentacją PDF wygrywają.
2. **Matchmaking musi działać naprawdę dobrze** — to serce i jedyny obowiązkowy moduł. Lepiej solidny matchmaking + 3-4 dopracowane moduły niż 7 modułów-zaślepek.
3. **WCAG 2.1 AA to 20% i warunek "produkcyjności"** — trzeba to wbudować od początku (semantyczny HTML, kontrast, nawigacja klawiaturą, aria), nie doklejać na końcu.
4. **"Pomysłowość"** jest wprost premiowana i oceniana — jury wyraźnie pyta: czy tylko sklejacie istniejące funkcje, czy tworzycie nową jakość. Warto mieć jeden element "wow" (np. AI-asystent z wizualizacją pomysłu).

## Wymagania formalne (część oceny!)

- Nazwa + opis rozwiązania
- Prezentacja PDF (max 10 slajdów) **oraz** film max 3 min (MP4) — regulamin wymaga obu, więc warto zrobić oba
- Link do działającego demo + makiety UX/UI
- Przewidywany koszt obsługi/utrzymania + opis zasobów
- Zgłoszenie przez platformę **HackTribe**, **w języku polskim**
- Deadline: **4 października 2026, 11:00** (24h)
- **Nie wolno używać prawdziwych danych osobowych/wrażliwych** — dane syntetyczne

## Wstępny plan implementacji

### Stack (optymalizowany pod 24h + wdrożeniowość + skalowalność)

Repo ma już `backend/`, `frontend/`, `docker-compose.yml` (PostgreSQL + backend + frontend). Propozycja:

- **Frontend**: Next.js (React) + TypeScript + Tailwind + komponenty dostępne (Radix UI / shadcn — dają WCAG za darmo). Next daje dobry UX i łatwy deploy demo (Vercel).
- **Backend**: do decyzji — albo w ramach Next.js (API routes, szybciej), albo osobny FastAPI/Node. Przy module AI FastAPI (Python) jest wygodny.
- **DB**: PostgreSQL + **pgvector** — do matchmakingu semantycznego (embeddingi). To jedno rozwiązanie daje i relacyjną bazę, i wyszukiwanie wektorowe → skalowalność i prostota utrzymania (argument na "potencjał wdrożeniowy").
- **AI/matchmaking**: embeddingi (OpenAI lub lokalny model) + wyszukiwanie wektorowe w pgvector; LLM do asystenta kreatora i Middlemana. Fallback na proste full-text search gdyby API padło na demo.

### Kolejność prac (priorytet = punktacja)

1. **Fundament + dane** (~2-3h): schemat DB (innowacje, problemy, zgłoszenia, użytkownicy, role), seed danych syntetycznych z biblioteki innowacji ROPS, szkielet frontu z layoutem dostępnym (nawigacja, kontrast, focus states) od startu.
2. **Matchmaking (obowiązkowy)** (~4-5h): formularz opisu problemu → embedding → top-N podobnych innowacji z uzasadnieniem dopasowania. Musi być namacalnie trafny na demo.
3. **Zasobnik wiedzy + Panel admina** (~3-4h): biblioteka innowacji (atrakcyjna prezentacja, karty, filmy), CRUD admina, widok trendów/agregacji. Dwa moduły za jednym zamachem (wspólny model danych).
4. **Kreator pomysłów + AI-asystent** (~3-4h): fiszka pomysłu + asystent LLM podpowiadający rozwinięcie i (opcjonalnie) wizualizację. Element "wow" na pomysłowość.
5. **Tester + Komunikacja** (~2-3h): zgłoszenia do testów + feedback, prosty wątek wiadomości ROPS ↔ użytkownik + powiadomienia.
6. **Middleman AI** (jeśli zostanie czas): prompt dostosowujący innowację do formy usługi.
7. **Materiały** (ostatnie ~3h, nie pomijać!): deploy demo, makiety, prezentacja PDF 10 slajdów, film 3 min, szacunek kosztów, opis nazwy/koncepcji.

### Przekrojowe (robione cały czas, nie na końcu)

- WCAG 2.1 AA: semantyka, kontrast AA, obsługa klawiatury, aria-labels, teksty alternatywne, komunikaty błędów. Warto przelecieć axe/Lighthouse przed oddaniem.
- Role (mieszkaniec / JST / ekspert / admin) — nawet uproszczone, pokazują zrozumienie użytkowników.
- Argumenty wdrożeniowe spisywane na bieżąco (koszty, skalowalność) — przydadzą się do prezentacji za 20%.

### Ryzyka

- **Rozdrobnienie**: 7 modułów w 24h to pułapka. Lepiej dowieźć 4-5 porządnie niż 7 pozornie.
- **AI na demo**: zależność od API — mieć fallback i dane cache'owane, żeby demo nie padło bez internetu.
- **Prezentacja na ostatnią chwilę**: 20% punktów za materiały — zablokować na nią czas twardo.
