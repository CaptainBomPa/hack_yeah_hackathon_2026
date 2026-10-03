# Tooling — decyzje pomocnicze

Ten plik jest dodatkiem do [`VISION.md`](../VISION.md). Zawiera kandydatów implementacyjnych,
ale nie zmienia architektury ani zakresu MVP. W razie rozbieżności obowiązuje `VISION.md`.

## Wybrane narzędzia

| Obszar | Narzędzie | Status i zastosowanie |
|---|---|---|
| Gateway | Java 25, Spring Boot 4.1, WebFlux, Spring Cloud Gateway | wybrane; transport i orkiestracja javowego decision pipeline |
| Konfiguracja | Jackson YAML + Jakarta Validation | planowane; schema, wersjonowanie i atomowy hot reload `policy.yaml` |
| Persistencja | JPA/Hibernate + JDBC, H2, PostgreSQL, Flyway | wybrane; H2 lokalnie, PostgreSQL i migracje w profilu `prod` |
| Odporność | Resilience4j | kandydat; timeouty i circuit breaker dla Ollamy oraz sidecara |
| Telemetria | Micrometer + OpenTelemetry | kandydat; metryki, trace i latencja per kontrola |
| Testy backendu | JUnit 5, Testcontainers, WireMock | JUnit wybrany; Testcontainers i WireMock są kandydatami do integracji |
| Frontend | React, TypeScript, Vite, Tailwind, React Router, Recharts | wybrane; demo chat i dashboard |
| Graf sesji | React Flow | opcjonalna wizualizacja Data-Flow Firewall (wyróżnik A) |
| Sidecar ML | Python, FastAPI | planowane; cienki adapter do lokalnych modeli klasyfikujących |
| Chroniony LLM | Ollama | wybrane; lokalny model z Raspberry Pi, bazowo `qwen2.5:1.5b-instruct-q4_K_M` |

## Lokalny klasyfikator semantyczny

Pierwszym kandydatem do krótkiego benchmarku jest **Laya**, ponieważ oferuje lokalne modele,
API podobne do Jev i możliwość uruchomienia przez Python/ONNX. Nie jest jednak automatycznie
wybranym modelem: przed włączeniem do MVP trzeba sprawdzić licencję konkretnego checkpointu,
jakość dla polskiego i angielskiego, false-positive rate, pamięć oraz latencję na sprzęcie demo.

Jeżeli Laya nie spełni kryteriów, sidecar zachowuje ten sam wewnętrzny kontrakt i może użyć
innego lokalnego modelu Hugging Face/ONNX. Wybór modelu nie może zmieniać javowego pipeline ani
formatu `ControlResult`.

Przydatne źródła:

- [Laya repository](https://github.com/NandhaKishorM/laya)
- [Laya model card](https://huggingface.co/convaiinnovations/laya)
- [Laya benchmarks](https://github.com/NandhaKishorM/laya/blob/main/BENCHMARKS.md)

## Opcjonalny adapter zewnętrzny

TypeSafe Jev może zostać dodany jako opcjonalny adapter do eksperymentów lub trybu shadow.
Nie jest kontrolą autoryzacyjną i nie należy do obowiązkowego demo, ponieważ wymaga usługi
zewnętrznej, wysyła dane poza lokalne środowisko i sam może podlegać manipulacji przez input.
Brak klucza lub awaria Jev nie mogą wyłączyć lokalnej semantyki.

## Narzędzia niewybrane do rdzenia

- LiteLLM, Portkey, Bifrost i gotowe gatewaye mogą być inspiracją, ale dublowałyby javowy rdzeń.
- SQLite nie jest używany; wspólnym magazynem trwałym jest PostgreSQL.
- Python/FastAPI nie implementuje gatewaya, polityki, budżetów ani ostatecznych decyzji.
- OpenJev i inne modele z ograniczeniami niekomercyjnymi nie mogą trafić do rozwiązania bez
  ponownej weryfikacji licencji.

## Kryteria akceptacji modelu

Kandydat może wejść do MVP dopiero po zapisaniu wyników dla tego samego korpusu testowego:

1. skuteczność na bezpośrednich i pośrednich prompt injection;
2. benign-block rate dla polskiego i angielskiego;
3. p50/p95 na sprzęcie demo;
4. maksymalne zużycie RAM/VRAM;
5. zachowanie na timeout, błędny format i przeciążenie;
6. zgodność licencji kodu i wag z wykorzystaniem konkursowym.
