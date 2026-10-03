from pathlib import Path

import yaml
from pydantic import BaseModel, Field

DEFAULT_PATH = Path(__file__).resolve().parent.parent / "config" / "semantic.yaml"


class Limits(BaseModel):
    max_input_chars: int = 20000


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
    params: dict = Field(default_factory=dict)


class Config(BaseModel):
    limits: Limits = Field(default_factory=Limits)
    normalization: NormalizationConfig = Field(default_factory=NormalizationConfig)
    detectors: dict[str, DetectorConfig] = Field(default_factory=dict)


def load_config(path: Path | None = None) -> Config:
    raw = yaml.safe_load((path or DEFAULT_PATH).read_text(encoding="utf-8")) or {}
    return Config.model_validate(raw)
