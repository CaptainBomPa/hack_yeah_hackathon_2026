"""Generyczny detektor oparty na klasyfikatorze tekstu z Hugging Face (encoder). Model jest wymienny przez konfigurację:
nowy model to nowy wpis w `config/*.yaml` (ścieżka, indeks klasy pozytywnej), bez zmian w kodzie.

Bazą wyniku jest margines logitów (logit klasy pozytywnej minus logsumexp pozostałych), który nie nasyca się jak softmax w
float32. Bez kalibracji wynik to sigmoid(margines). Z kalibracją (`calibration`: plik JSON z scripts/calibrate.py) wynik to
skalibrowane prawdopodobieństwo (zob. app/calibration.py). Długi tekst jest dzielony na okna z nakładaniem (limit kontekstu
modeli to zwykle 512 tokenów), a wynik to maksimum po oknach i wariantach tekstu.
"""

import json
import math
import threading
from pathlib import Path

from app.calibration import PlattCalibration, load_calibration, sigmoid
from app.contract import Checkpoint, ClassifyRequest, Finding, RawEvidence
from app.detectors.base import Detector
from app.normalize import Normalized

ROOT = Path(__file__).resolve().parent.parent.parent


class HFClassifierDetector(Detector):
    def __init__(
        self,
        name: str,
        model_dir: str,
        positive_label: int | str = 1,
        label: str = "positive",
        checkpoints: list[str] | None = None,
        max_length: int = 512,
        stride: int = 64,
        max_windows: int = 8,
        max_variants: int = 6,
        threads: int | None = None,
        calibration: str | None = None,
        target_prior: float | None = None,
        label_threshold: float | None = None,
    ):
        import torch  # import leniwy: tylko ten detektor wymaga torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self.name = name
        self.label = label
        self.checkpoints = frozenset(Checkpoint(c) for c in (checkpoints or ["P1", "P2", "P5"]))
        self.max_length, self.stride, self.max_windows, self.max_variants = max_length, stride, max(1, max_windows), max_variants
        path = Path(model_dir)
        path = path if path.is_absolute() else ROOT / path
        if threads:
            torch.set_num_threads(threads)
        self._torch = torch
        self._tok = AutoTokenizer.from_pretrained(path)
        self._model = AutoModelForSequenceClassification.from_pretrained(path).eval()
        self._lock = threading.Lock()  # szybkie tokenizatory HF nie są bezpieczne przy współbieżnym użyciu
        id2label = self._model.config.id2label or {}
        if isinstance(positive_label, str):
            matches = [i for i, n in id2label.items() if n == positive_label]
            if not matches:
                raise ValueError(f"Model {path.name} nie ma etykiety {positive_label!r}: {id2label}")
            positive_label = int(matches[0])
        self._pos = int(positive_label)
        self.version = self._revision(path)
        cal_path = Path(calibration) if calibration else None
        if cal_path and not cal_path.is_absolute():
            cal_path = ROOT / cal_path
        self.calibration: PlattCalibration | None = load_calibration(cal_path) if cal_path else None
        self.target_prior = target_prior
        self.label_threshold = label_threshold  # None = nigdy nie ustawiaj `label`: decyzję podejmuje gateway z progu w polityce
        if target_prior is not None and self.calibration is None:
            raise ValueError("target_prior wymaga kalibracji (parametr `calibration`)")
        if self.calibration:
            self.version += "+cal"

    @staticmethod
    def _revision(path: Path) -> str:
        manifest = path.parent / "MANIFEST.json"
        try:
            return json.loads(manifest.read_text())[path.name]["revision"][:10]
        except Exception:
            return "local"

    def warmup(self) -> None:
        self.margin("warmup")

    def windows(self, text: str) -> tuple[list[tuple[list[int], tuple[int, int] | None]], int]:
        """Dzieli tekst na okna tokenów z nakładaniem. Zwraca (okna do oceny, liczba wszystkich okien).

        Okna układamy ręcznie, bo `return_overflowing_tokens` + `stride` w transformers 5.18 zwraca tylko 2 okna i
        zostawia resztę długiego tekstu nieocenioną. Gdy okien jest więcej niż `max_windows`, oceniamy równomiernie
        rozłożone (z pierwszym i ostatnim), a wywołujący dostaje `coverage` < 1.
        """
        enc = self._tok(text, add_special_tokens=False, return_offsets_mapping=True, verbose=False)  # bez ostrzeżenia o >512 tokenach: okna to obsługują
        ids, offs = enc["input_ids"], enc["offset_mapping"]
        room = self.max_length - self._tok.num_special_tokens_to_add(pair=False)
        step = room - self.stride
        if step <= 0:
            raise ValueError("stride musi być mniejszy niż max_length minus tokeny specjalne")
        starts = [0]
        while starts[-1] + room < len(ids):
            starts.append(starts[-1] + step)
        total = len(starts)
        if total > self.max_windows:
            idx = sorted({round(i * (total - 1) / (self.max_windows - 1)) for i in range(self.max_windows)}) if self.max_windows > 1 else [0]
            starts = [starts[i] for i in idx]
        out = []
        for st in starts:
            chunk = ids[st: st + room]
            span = (offs[st][0], offs[st + len(chunk) - 1][1]) if chunk else None
            out.append((chunk, span))
        return out, total

    def margin(self, text: str) -> tuple[float, tuple[int, int] | None, float]:
        """Maksymalny po oknach margines logitów klasy pozytywnej, zakres znaków okna, które go dało, i pokrycie tekstu."""
        wins, total = self.windows(text)
        # Okna wyznaczamy po tokenach, ale oceniamy jako podciągi tekstu: zwykłe wywołanie tokenizera dokłada tokeny specjalne
        # i dopełnienie, bez użycia metod wewnętrznych, które zmieniają się między wersjami transformers.
        pieces = [text[sp[0]:sp[1]] if sp else "" for _, sp in wins]
        with self._lock:  # szybkie tokenizatory HF nie są bezpieczne przy współbieżnym użyciu
            batch = self._tok(pieces, padding=True, truncation=True, max_length=self.max_length, return_tensors="pt")
        with self._torch.inference_mode():
            logits = self._model(**batch).logits.double()
            others = self._torch.cat([logits[:, : self._pos], logits[:, self._pos + 1:]], dim=-1)
            margins = logits[:, self._pos] - self._torch.logsumexp(others, dim=-1)
        best = int(margins.argmax())
        return float(margins[best]), wins[best][1], len(wins) / total

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        best_margin, best, coverage = -math.inf, None, 1.0
        for v in norm.variants()[: self.max_variants]:
            margin, span, cov = self.margin(v.text)
            coverage = min(coverage, cov)
            if margin > best_margin:
                best_margin = margin
                best = RawEvidence(text=v.text[span[0]:span[1]] if span else v.text[:200], variant=v.name, span=span)
        raw = sigmoid(best_margin)
        score = self.calibration.apply(best_margin, self.target_prior) if self.calibration else raw
        flagged = self.label_threshold is not None and score >= self.label_threshold
        return Finding(score=score, raw_score=raw, coverage=round(coverage, 4), label=self.label if flagged else None, evidence=best)
