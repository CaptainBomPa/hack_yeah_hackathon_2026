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

Otwórz **root repo** (nie ten katalog) w IntelliJ — `settings.gradle` w roocie jest composite
buildem, który dociąga `backend/` automatycznie (szczegóły: [`../README.md`](../README.md)).

W IntelliJ (configi współdzielone z `.run/`, pojawią się same po otwarciu roota):

- **Backend (local)** — `bootRun` na H2.
- **Backend (prod, postgres)** — najpierw stawia `Postgres (docker)`, potem `bootRun` z profilem `prod`.
- **Backend tests** — `gradle test`.

Z terminala (z tego katalogu `backend/`; z roota repo analogicznie przez `./gradlew :backend:<task>`):

```bash
./gradlew bootRun                                   # profil local
docker compose up -d db                             # z roota repo
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun       # profil prod
docker compose up -d --build db backend             # całość w kontenerach (prod)
```

Gateway słucha na `http://localhost:8000`, health: `/actuator/health`.

## Przebudowa po zmianach (PowerShell, z katalogu głównego repo)

Docker Desktop musi być uruchomiony. Gotowy skrypt buduje obrazy, uruchamia kontenery
i czeka na gotowość usług; przerywa przy błędzie builda. Zachowuje bazę i pobrane modele.

```powershell
# Po zmianach w backendzie (Java, resources, konfiguracja builda):
.\scripts\rebuild.ps1 -Target backend

# Po zmianach w UI:
.\scripts\rebuild.ps1 -Target frontend

# Cały stack:
.\scripts\rebuild.ps1

# Logi backendu (Ctrl+C kończy podgląd):
docker compose logs -f --tail 100 backend
```

Bez skryptu, po zmianie backendu: `docker compose up -d --build backend`.
Skrypt korzysta z cache Dockera i nie wyłącza cache Gradle.
Samo `docker compose restart backend` nie buduje nowego kodu. Build Dockerowy pomija testy;
testy backendu uruchamiaj osobno: `.\gradlew.bat :backend:test`.
UI jest na http://localhost:3000. Gotowość modeli przy pierwszym uruchomieniu sprawdzisz
przez `docker compose logs --tail 20 ollama-init` (kończy się sukcesem).

`POST /v1/chat/completions` woła skonfigurowany model (`{model, messages}` na wejściu,
`GuardedChatResponse` na wyjściu — kontrakt w `frontend/src/api/types.ts`). Dziś jedyną realną
kontrolą w `trace` jest allowlista modeli (`control-layer.models` w `application.yml`); reszta
decision pipeline z `VISION.md` §9 jeszcze nie istnieje. Model trzeba najpierw dopisać do tej
listy, inaczej dostaniesz `403 model.allowlist`. Adres providera: `OLLAMA_BASE_URL`
(domyślnie `http://localhost:11434` — nadpisywane w `docker-compose.yml` na `http://ollama:11434`
dla wdrożenia na Raspberry Pi).
