# HackYeah Hackathon 2026 — AI Control Layer

[![Backend tests](https://github.com/CaptainBomPa/hack_yeah_hackathon_2026/actions/workflows/backend-tests.yml/badge.svg?branch=main)](https://github.com/CaptainBomPa/hack_yeah_hackathon_2026/actions/workflows/backend-tests.yml)

Gateway bezpieczeństwa przed LLM: guardraile deterministyczne i semantyczne, budżetowanie,
audyt oraz dashboard.

Najważniejszym materiałem źródłowym jest
[`CRITERIA AI Control Layer.pdf`](project-spec/CRITERIA%20AI%20Control%20Layer.pdf), a
[`VISION.md`](VISION.md) jest jedynym wiążącym opisem naszej architektury, technologii,
zakresu MVP i planu implementacji. Przeczytaj oba przed rozpoczęciem pracy.

## Repozytorium

- [`backend/`](backend/) — Java 25, Spring Boot 4 i Spring Cloud Gateway;
- [`frontend/`](frontend/) — React + TypeScript, playground i dashboard;
- [`docs/tooling.md`](docs/tooling.md) — pomocnicze wybory bibliotek i modeli;
- [`docker-compose.yml`](docker-compose.yml) — środowisko aplikacyjne.

Aktualne instrukcje uruchomienia są w README poszczególnych komponentów. Stan implementacji
i kolejność prac opisuje `VISION.md`.

Przebudowa całego stacka w PowerShell: `.\scripts\rebuild.ps1`.
Po zmianach backendu: `.\scripts\rebuild.ps1 -Target backend`.
Szczegóły: [backend/README.md](backend/README.md#przebudowa-po-zmianach-powershell-z-katalogu-głównego-repo).

## IntelliJ / Gradle

Otwórz **ten katalog** (root repo) w IntelliJ — `settings.gradle` w roocie to composite build
(`includeBuild('backend')`), więc `backend/` zostanie od razu rozpoznany i zaimportowany jako
projekt Gradle, bez ręcznego "Link Gradle Project" na `backend/build.gradle`. `backend/`
zachowuje przy tym własny, w pełni samodzielny build (Dockerfile i `docker-compose.yml` dalej
budują go niezależnie) — root nic w nim nie zmienia, tylko ułatwia pracę w IDE.

Z terminala, z poziomu roota:

```bash
./gradlew :backend:bootRun   # profil local (H2)
./gradlew :backend:test
```

`frontend/` to osobny projekt Node/Vite (`npm install && npm run dev` w `frontend/`) — nie
wchodzi w ten composite build.
