from collections.abc import Callable

from app.detectors.base import Detector
from app.detectors.obfuscation import ObfuscationDetector

# Fabryki detektorów: nazwa -> funkcja budująca detektor z `params` z konfiguracji.
FACTORIES: dict[str, Callable[[dict], Detector]] = {
    "obfuscation": lambda params: ObfuscationDetector(**params),
}
