# Backend — AI Control Layer (Spring Cloud Gateway)

Architektura i katalog kontroli: [`../VISION.md`](../VISION.md).
Stack: Java 25, Spring Boot 4.1, Spring Cloud 2025.1 (gateway webflux), Gradle 9 (wrapper w repo).

## Wymagania

- JDK 17+ do odpalenia Gradle (np. w IntelliJ: *Project Structure → SDK → Download JDK → 25*).
  JDK 25 do kompilacji Gradle pobierze sam (toolchain + foojay).
- Docker Desktop — tylko dla profilu `prod`.

## Profile

| Profil | Baza | Schemat | Kiedy |
|---|---|---|---|
| `local` (domyślny) | H2 in-memory | Hibernate `create-drop` | codzienny dev, testy — zero zależności |
| `prod` | PostgreSQL (`db` z `docker-compose.yml`) | Flyway (`src/main/resources/db/migration`), Hibernate `validate` | zgodność z produkcją, demo |

## Uruchomienie

W IntelliJ (configi współdzielone z `.run/`, pojawią się same po otwarciu repo i podpięciu
`backend/` jako projektu Gradle):

- **Backend (local)** — `bootRun` na H2.
- **Backend (prod, postgres)** — najpierw stawia `Postgres (docker)`, potem `bootRun` z profilem `prod`.
- **Backend tests** — `gradle test`.

Z terminala:

```bash
./gradlew bootRun                                   # profil local
docker compose up -d db                             # z roota repo
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun       # profil prod
docker compose up -d --build db backend             # całość w kontenerach (prod)
```

Gateway słucha na `http://localhost:8000`, health: `/actuator/health`.
`/llm/**` to passthrough do Ollamy (`OLLAMA_BASE_URL`, domyślnie `http://raspberrypi.local:11434`).
