"""Kontrakt wejścia i wyjścia sidecara. Zob. docs/ai-control-layer/semantic-validator-analysis.md §4."""

from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class Checkpoint(str, Enum):
    P1 = "P1"  # prompt użytkownika
    P2 = "P2"  # dane niezaufane (narzędzia, RAG, opisy MCP, inni agenci)
    P3 = "P3"  # proponowane wywołanie narzędzia
    P4 = "P4"  # odpowiedź modelu
    P5 = "P5"  # pamięć agenta (zapis i odczyt)


class Status(str, Enum):
    OK = "ok"
    TIMEOUT = "timeout"
    ERROR = "error"
    SKIPPED = "skipped"


class Context(BaseModel):
    session_id: str | None = None
    task: str | None = None
    language: str | None = None
    source_trust: Literal["user", "tool", "agent", "document", "memory"] = "user"


class ClassifyRequest(BaseModel):
    checkpoint: Checkpoint
    text: str
    context: Context = Field(default_factory=Context)


class Evidence(BaseModel):
    text: str
    variant: Literal["original", "normalized", "deobfuscated", "decoded"] = "original"
    span: tuple[int, int] | None = None


class Finding(BaseModel):
    """Co zwraca sam detektor. Status i czas dolicza runner."""

    score: float = Field(ge=0.0, le=1.0)
    label: str | None = None
    evidence: Evidence | None = None


class DetectorResult(BaseModel):
    detector: str
    version: str
    status: Status
    score: float | None = Field(default=None, ge=0.0, le=1.0)
    label: str | None = None
    evidence: Evidence | None = None
    latency_ms: float


class NormalizationInfo(BaseModel):
    """Podsumowanie normalizacji do audytu i debugowania. Nie zawiera odkodowanego tekstu."""

    changed: bool
    variants: list[str]
    signals: dict


class ClassifyResponse(BaseModel):
    checkpoint: Checkpoint
    results: list[DetectorResult]
    normalization: NormalizationInfo | None = None
