# AGENTS.md

Przed zmianą kodu przeczytaj [`VISION.md`](VISION.md). Jest jedynym źródłem prawdy o wizji,
architekturze, technologiach i planie; nie powielaj tych decyzji w tym pliku.

Nadrzędnym materiałem źródłowym zadania jest
[`CRITERIA AI Control Layer.pdf`](project-spec/CRITERIA%20AI%20Control%20Layer.pdf).

Zasady pracy:

- backend i decision pipeline rozwijamy przede wszystkim w Javie;
- Python jest dopuszczony wyłącznie jako cienki, lokalny sidecar ML;
- polityki i przypadki testowe są danymi, nie hardcodowanymi regułami aplikacji;
- nie zapisujemy surowego PII ani sekretów w logach;
- zmianę decyzji architektonicznej zapisujemy w `VISION.md` w tym samym commicie;
- polecenia komponentów i testów znajdują się w ich README oraz konfiguracji builda.
