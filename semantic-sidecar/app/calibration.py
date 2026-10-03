"""Kalibracja wyników detektorów (skalowanie Platta na marginesie logitów).

Surowy softmax w float32 nasyca się na 1,0, więc wiele przypadków ma identyczny wynik. Dlatego kalibrujemy margines
(logit klasy pozytywnej minus logsumexp pozostałych), który zachowuje rozdzielczość. Skalowanie Platta jest monotoniczne:
NIE zmienia rankingu ani AUROC, tylko skalę i umiejscowienie progu.

Skalibrowany wynik to P(atak | margines) przy częstości ataków ze zbioru dopasowania. Ruch produkcyjny ma zwykle znacznie
mniej ataków, więc `target_prior` pozwala przesunąć wynik na inną częstość bazową (korekta wyrazu wolnego).
Parametry kalibracji to dane (JSON w config/calibration/), tworzone przez scripts/calibrate.py.
"""

import json
import math
from dataclasses import dataclass
from pathlib import Path


def sigmoid(x: float) -> float:
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


def logit(p: float) -> float:
    return math.log(p / (1.0 - p))


@dataclass(frozen=True)
class PlattCalibration:
    a: float
    b: float
    train_prior: float | None = None  # częstość ataków w zbiorze dopasowania

    def apply(self, margin: float, target_prior: float | None = None) -> float:
        z = self.a * margin + self.b
        if target_prior is not None:
            if not 0.0 < target_prior < 1.0:
                raise ValueError("target_prior musi być w przedziale (0, 1)")
            if self.train_prior is None:
                raise ValueError("Ta kalibracja nie zapisuje train_prior, więc nie da się zmienić częstości bazowej")
            z += logit(target_prior) - logit(self.train_prior)
        return sigmoid(z)


def load_calibration(path: str | Path) -> PlattCalibration:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    if d.get("method") != "platt":
        raise ValueError(f"Nieobsługiwana metoda kalibracji: {d.get('method')!r}")
    return PlattCalibration(a=d["a"], b=d["b"], train_prior=d.get("fit", {}).get("prior"))
