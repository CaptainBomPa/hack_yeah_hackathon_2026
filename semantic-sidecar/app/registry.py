from collections.abc import Callable

from app.detectors.base import Detector
from app.detectors.obfuscation import ObfuscationDetector


def _hf_classifier(name: str, params: dict) -> Detector:
    from app.detectors.hf_classifier import HFClassifierDetector  # leniwie: wymaga torch

    return HFClassifierDetector(name=name, **params)


def _hf_ensemble(name: str, params: dict) -> Detector:
    from app.detectors.hf_ensemble import HFEnsembleDetector  # leniwie: wymaga torch

    return HFEnsembleDetector(name=name, **params)


# Fabryki detektorów: rodzaj (`kind`, domyślnie nazwa wpisu) -> funkcja (nazwa, params) -> detektor.
FACTORIES: dict[str, Callable[[str, dict], Detector]] = {
    "obfuscation": lambda name, params: ObfuscationDetector(**params),
    "hf_classifier": _hf_classifier,
    "hf_ensemble": _hf_ensemble,
}
