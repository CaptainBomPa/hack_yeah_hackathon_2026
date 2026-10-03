from abc import ABC, abstractmethod

from app.contract import Checkpoint, ClassifyRequest, Finding
from app.normalize import Normalized


class Detector(ABC):
    """Wspólny interfejs detektora. Nowy model = nowa implementacja tej klasy.

    Detektor dostaje żądanie oraz wyniki normalizacji (`norm.variants()` to wersje tekstu do przeskanowania,
    bierzemy gorszy wynik). Zawsze zwraca `Finding`, także gdy nic nie wykrył (score 0.0), bo agregator i ewaluacja
    potrzebują skalibrowanego wyniku, a nie tylko trafień. Błąd to wyjątek, który runner zamienia na status `error`.
    """

    name: str
    version: str
    checkpoints: frozenset[Checkpoint]

    @abstractmethod
    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding: ...
