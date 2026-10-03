"""Przypadki testowe jako dane: YAML (ręczne) i JSONL (pobrane z publicznych zbiorów)."""

import json
from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, Field

from app.contract import Checkpoint, Context

Kind = Literal["attack", "benign", "hard_negative"]


class Case(BaseModel):
    id: str
    checkpoint: Checkpoint
    text: str
    kind: Kind
    family: str = "general"
    context: Context = Field(default_factory=Context)
    source: str = "own"
    tags: list[str] = Field(default_factory=list)

    @property
    def is_attack(self) -> bool:
        return self.kind == "attack"


def _read_file(path: Path) -> list[dict]:
    if path.suffix in (".yaml", ".yml"):
        return yaml.safe_load(path.read_text(encoding="utf-8")) or []
    if path.suffix == ".jsonl":
        return [json.loads(line) for line in path.read_text(encoding="utf-8").split("\n") if line.strip()]
    return []


def load_cases(paths: list[Path]) -> list[Case]:
    """Wczytuje przypadki z plików i katalogów. Zduplikowane id przerywają wczytanie."""
    files: list[Path] = []
    for p in paths:
        files.extend(sorted(f for f in p.rglob("*") if f.is_file()) if p.is_dir() else [p])
    cases: list[Case] = []
    seen: set[str] = set()
    for f in files:
        for raw in _read_file(f):
            case = Case.model_validate(raw)
            if case.id in seen:
                raise ValueError(f"Zduplikowane id przypadku: {case.id} ({f})")
            seen.add(case.id)
            cases.append(case)
    return cases
