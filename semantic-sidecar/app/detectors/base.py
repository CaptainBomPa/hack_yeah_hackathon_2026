from abc import ABC, abstractmethod

from app.contract import Checkpoint, ClassifyRequest, Finding
from app.normalize import Normalized


class Detector(ABC):
    """Wspólny interfejs detektora. Nowy model = nowa implementacja tej klasy.

    Detektor dostaje żądanie oraz wyniki normalizacji (`norm.variants()` to wersje tekstu do przeskanowania,
    bierzemy gorszy wynik). Zawsze zwraca `Finding`, także gdy nic nie wykrył (score 0.0), bo agregator i ewaluacja
    potrzebują skalibrowanego wyniku, a nie tylko trafień. Błąd to wyjątek, który runner zamienia na status `error`.

    Dowód zwraca jako `RawEvidence` (surowy tekst). Runner zamienia go na bezpieczny `Evidence` (bez treści).
    Detektor jest wołany z puli wątków, więc musi być bezpieczny wątkowo (bez współdzielonego stanu zapisu).
    """

    name: str
    version: str
    checkpoints: frozenset[Checkpoint]
    # Detektor, który ufa wynikom normalizacji, nie może działać na uszkodzonej normalizacji
    # (wtedy dostaje status error, a nie "wynik 0").
    requires_normalization: bool = True
    # Detektor oparty na sygnałach normalizacji (liczniki z `norm.signals`) nie ma sensu, gdy tekst przychodzi już
    # znormalizowany. Sidecar odmawia startu, jeśli taki detektor jest włączony przy `input.pre_normalized: true`.
    requires_normalization_signals: bool = False

    def warmup(self) -> None:
        """Opcjonalne rozgrzanie (np. pierwsze wywołanie modelu bywa wolne). Wywoływane raz przy starcie aplikacji.
        Wyjątek przerywa start: lepiej wywrócić się od razu niż zwracać błędy przy pierwszym żądaniu."""

    @abstractmethod
    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding: ...
