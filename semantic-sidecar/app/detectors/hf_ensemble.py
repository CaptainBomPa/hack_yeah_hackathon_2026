"""Zespół klasyfikatorów HF: średnia marginesów logitów kilku modeli, jedna wspólna kalibracja.

Dwa modele o różnej pojemności (np. Horizon small i base) rzadko mylą się tak samo, a średnia marginesów była w pomiarach lepsza od
mniejszego modelu na jawnych próbach zmiany reguł, przy mniejszym kosztem legalnych promptów niż sam większy model (docs/models.md).

Członkowie to zwykłe `HFClassifierDetector` bez własnej kalibracji. Budżet czasu jest wspólny: pierwszy model dostaje przynajmniej jedną
porcję okien, a kolejne oceniają tylko, dopóki budżet nie minął. Gdy budżet minie przed drugim modelem, wynik opiera się na pierwszym
(skala marginesów obu modeli jest zbliżona), a `coverage` to uczciwie obniża.
"""

import math
import time

from app.calibration import PlattCalibration, load_calibration, sigmoid
from app.contract import Checkpoint, ClassifyRequest, Finding, RawEvidence
from app.detectors.base import Detector
from app.detectors.hf_classifier import ROOT, HFClassifierDetector
from app.normalize import Normalized
from pathlib import Path


class HFEnsembleDetector(Detector):
    def __init__(
        self,
        name: str,
        members: list[dict],
        calibration: str | None = None,
        target_prior: float | None = None,
        label: str = "positive",
        checkpoints: list[str] | None = None,
        max_variants: int = 6,
        time_budget_ms: int | None = None,
    ):
        if len(members) < 2:
            raise ValueError("Zespół wymaga co najmniej dwóch modeli")
        self.name, self.label = name, label
        self.checkpoints = frozenset(Checkpoint(c) for c in (checkpoints or ["P1", "P2", "P5"]))
        self.max_variants, self.time_budget_ms = max_variants, time_budget_ms
        self.members = [
            HFClassifierDetector(name=f"{name}[{i}]", **{**m, "calibration": None, "target_prior": None, "label_threshold": None})
            for i, m in enumerate(members)
        ]
        cal_path = Path(calibration) if calibration else None
        if cal_path and not cal_path.is_absolute():
            cal_path = ROOT / cal_path
        self.calibration: PlattCalibration | None = load_calibration(cal_path) if cal_path else None
        if target_prior is not None and self.calibration is None:
            raise ValueError("target_prior wymaga kalibracji (parametr `calibration`)")
        self.target_prior = target_prior
        self.version = "+".join(m.version for m in self.members) + ("+cal" if self.calibration else "")

    def warmup(self) -> None:
        for m in self.members:
            m.warmup()

    def _fused(self, text: str, deadline: float | None, force_first: bool) -> tuple[float, tuple[int, int] | None, int, int]:
        """(średni margines ocenionych członków, zakres najmocniejszego okna, ocenione okna, wszystkie okna)."""
        margins, spans, done_all, total_all = [], [], 0, 0
        for i, m in enumerate(self.members):
            margin, span, done, total = m._margin(text, deadline, force_first=force_first and i == 0)
            done_all += done
            total_all += total
            if math.isfinite(margin):
                margins.append(margin)
                spans.append((margin, span))
        if not margins:
            return -math.inf, None, done_all, total_all
        return sum(margins) / len(margins), max(spans, key=lambda x: x[0])[1], done_all, total_all

    def margin(self, text: str, deadline: float | None = None) -> tuple[float, tuple[int, int] | None, float]:
        """Ten sam kształt co `HFClassifierDetector.margin` (używa go scripts/calibrate.py)."""
        m, span, done, total = self._fused(text, deadline, force_first=True)
        return m, span, done / total

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        best, best_span, best_v = -math.inf, None, None
        evaluated = total_windows = 0
        deadline = time.perf_counter() + self.time_budget_ms / 1000 if self.time_budget_ms else None
        for v in norm.variants()[: self.max_variants]:
            margin, span, done, total = self._fused(v.text, deadline, force_first=not evaluated)
            evaluated += done
            total_windows += total
            if margin > best:
                best, best_span, best_v = margin, span, v
        raw = sigmoid(best)
        score = self.calibration.apply(best, self.target_prior) if self.calibration else raw
        evidence = RawEvidence(text=best_v.text[best_span[0]:best_span[1]] if best_span else best_v.text[:200],
                               variant=best_v.name, span=best_span)
        return Finding(score=score, raw_score=raw, coverage=round(evaluated / total_windows, 4) if total_windows else 1.0,
                       label=None, evidence=evidence)
