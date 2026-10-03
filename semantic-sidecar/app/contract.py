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


class RawEvidence(BaseModel):
    """Dowód widziany tylko przez detektor i runner. NIGDY nie opuszcza procesu: runner zamienia go na `Evidence`.

    Fragment tekstu może zawierać sekrety (także odkodowane z base64), więc typ publiczny go nie ma.
    """

    text: str
    variant: Literal["original", "normalized", "deobfuscated", "decoded"] = "original"
    span: tuple[int, int] | None = None


class Evidence(BaseModel):
    """Dowód bezpieczny do logów i odpowiedzi: gdzie i jaki, bez treści."""

    variant: Literal["original", "normalized", "deobfuscated", "decoded"]
    span: tuple[int, int] | None = None
    length: int
    digest: str  # HMAC-SHA256 fragmentu (skrócony): pozwala korelować powtórzenia, nie pozwala odgadywać treści
    preview: str | None = None  # opcjonalny, zamaskowany podgląd (domyślnie wyłączony)


class Finding(BaseModel):
    """Co zwraca sam detektor. Status, czas i bezpieczny dowód dolicza runner."""

    score: float = Field(ge=0.0, le=1.0)
    raw_score: float | None = Field(default=None, ge=0.0, le=1.0)  # wynik przed kalibracją (audyt, porównania)
    coverage: float | None = Field(default=None, ge=0.0, le=1.0)  # ułamek okien tekstu, który oceniono (<1: tekst dłuższy niż limit okien)
    label: str | None = None
    evidence: RawEvidence | None = None


class DetectorResult(BaseModel):
    detector: str
    version: str
    status: Status
    reason: str | None = None  # kod powodu, gdy status != ok (np. timeout, exception, normalization_failed)
    score: float | None = Field(default=None, ge=0.0, le=1.0)
    raw_score: float | None = Field(default=None, ge=0.0, le=1.0)
    coverage: float | None = Field(default=None, ge=0.0, le=1.0)
    label: str | None = None
    evidence: Evidence | None = None
    latency_ms: float


class MissingCheck(BaseModel):
    """Kontrola, która miała się wykonać, a nie dała wyniku. Gateway decyduje, co z tym zrobić (domyślnie fail-closed)."""

    check: str  # nazwa detektora albo "normalization"
    status: Status
    reason: str


class NormalizationInfo(BaseModel):
    """Podsumowanie normalizacji do audytu i debugowania. Nie zawiera odkodowanego tekstu."""

    changed: bool
    variants: list[str]
    signals: dict


class ClassifyResponse(BaseModel):
    checkpoint: Checkpoint
    results: list[DetectorResult]
    complete: bool = True  # false, gdy `missing_checks` nie jest puste
    missing_checks: list[MissingCheck] = Field(default_factory=list)
    normalization: NormalizationInfo | None = None
