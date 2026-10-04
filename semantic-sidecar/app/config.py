import os
from pathlib import Path

import yaml
from pydantic import BaseModel, Field

DEFAULT_PATH = Path(os.environ.get("SEMANTIC_CONFIG") or Path(__file__).resolve().parent.parent / "config" / "semantic.yaml")


class Limits(BaseModel):
    max_input_chars: int = 20000


class InputConfig(BaseModel):
    """Kontrakt wejścia. `pre_normalized: true` (ustalenie z zespołem): gateway wysyła tekst już znormalizowany, a sidecar
    go nie rusza. `false` = tryb samodzielny: sidecar normalizuje sam (referencyjna implementacja w `app/normalize/`)."""

    pre_normalized: bool = True


class Deadlines(BaseModel):
    """Limity czasu. Wartości to założenia do zmierzenia na docelowym sprzęcie, nie wyniki pomiaru."""

    request_deadline_ms: int = 2000  # całe żądanie
    normalization_timeout_ms: int = 500
    detector_timeout_ms: int = 1000
    max_workers: int = 8  # pula wątków detektorów
    # Ile żądań może jednocześnie wykonywać modele. Wątku modelu nie da się przerwać po timeoucie, więc bez limitu osierocone
    # wątki piętrzą się, zabierają CPU kolejnym żądaniom i powodują kaskadę timeoutów. Slot jest zwalniany dopiero, gdy wątki
    # naprawdę skończą (nie przy zwrocie odpowiedzi). Zbyt wolne żądania dostają szybko status `skipped`/`overloaded`.
    max_concurrent_inference: int = 2
    queue_wait_ms: int = 1500  # jak długo żądanie czeka na wolny slot, zanim dostanie `overloaded`


class EvidenceConfig(BaseModel):
    include_preview: bool = False  # zamaskowany podgląd w `evidence.preview`
    digest_key_env: str = "SEMANTIC_EVIDENCE_KEY"  # klucz HMAC z środowiska; bez niego losowy na proces


class DecodersConfig(BaseModel):
    base64: bool = True
    hex: bool = True
    url: bool = True
    rot13: bool = True
    reversed: bool = True
    unicode_tags: bool = True


class DeobfuscateConfig(BaseModel):
    leet: bool = True
    spaced: bool = True
    case: bool = True
    markup: bool = True


class NormalizationConfig(BaseModel):
    enabled: bool = True
    max_decode_depth: int = 3  # głębokość zagnieżdżonych kodowań
    max_segments: int = 16
    max_decoded_chars: int = 50000
    decoders: DecodersConfig = Field(default_factory=DecodersConfig)
    deobfuscate: DeobfuscateConfig = Field(default_factory=DeobfuscateConfig)


class DetectorConfig(BaseModel):
    enabled: bool = True
    kind: str | None = None  # rodzaj detektora (fabryka); domyślnie nazwa wpisu
    params: dict = Field(default_factory=dict)


class Config(BaseModel):
    input: InputConfig = Field(default_factory=InputConfig)
    limits: Limits = Field(default_factory=Limits)
    deadlines: Deadlines = Field(default_factory=Deadlines)
    evidence: EvidenceConfig = Field(default_factory=EvidenceConfig)
    normalization: NormalizationConfig = Field(default_factory=NormalizationConfig)
    detectors: dict[str, DetectorConfig] = Field(default_factory=dict)


def load_config(path: Path | None = None) -> Config:
    raw = yaml.safe_load((path or DEFAULT_PATH).read_text(encoding="utf-8")) or {}
    return Config.model_validate(raw)
