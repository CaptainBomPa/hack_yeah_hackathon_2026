# HackYeah Hackathon 2026 — AI Control Layer

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
