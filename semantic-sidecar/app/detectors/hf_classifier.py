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
import time
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
        time_budget_ms: int | None = None,
        window_batch: int = 4,
    ):
        import torch  # import leniwy: tylko ten detektor wymaga torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self.name = name
        self.label = label
        self.checkpoints = frozenset(Checkpoint(c) for c in (checkpoints or ["P1", "P2", "P5"]))
        self.max_length, self.stride, self.max_windows, self.max_variants = max_length, stride, max(1, max_windows), max_variants
        self.window_batch = max(1, window_batch)  # ile okien na jedno wywołanie modelu; po każdej porcji sprawdzamy budżet czasu
        self.time_budget_ms = time_budget_ms  # None = bez limitu; inaczej oceniamy okna do wyczerpania budżetu i zgłaszamy niepełne `coverage`
        path = Path(model_dir)
        path = path if path.is_absolute() else ROOT / path
        if threads:
            torch.set_num_threads(threads)
        self._torch = torch
        self._tok = AutoTokenizer.from_pretrained(path, trust_remote_code=False)  # jawnie: bez pytania i bez kodu zdalnego
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

    @staticmethod
    def _spread_order(n: int) -> list[int]:
        """Kolejność ocen okien: pierwsze, ostatnie, potem połowienie przedziałów. Przerwane w dowolnym momencie zostawia
        okna rozłożone równomiernie po tekście (atak w środku nie jest systematycznie pomijany)."""
        order, seen = [], set()
        queue = [(0, n - 1)] if n > 1 else []
        for i in ([0, n - 1] if n > 1 else [0]):
            if i not in seen:
                order.append(i); seen.add(i)
        while queue:
            lo, hi = queue.pop(0)
            mid = (lo + hi) // 2
            if mid not in seen:
                order.append(mid); seen.add(mid)
            if mid - lo > 1:
                queue.append((lo, mid))
            if hi - mid > 1:
                queue.append((mid, hi))
        return order

    def margin(self, text: str, deadline: float | None = None) -> tuple[float, tuple[int, int] | None, float]:
        """Maksymalny po oknach margines logitów klasy pozytywnej, zakres znaków okna, które go dało, i pokrycie tekstu.

        `deadline` (czas `time.perf_counter()`): po jego przekroczeniu kolejne porcje okien nie są oceniane, a `coverage` maleje.
        Pierwsza porcja jest oceniana zawsze, więc wynik zawsze istnieje."""
        m, span, done, total = self._margin(text, deadline, force_first=True)
        return m, span, done / total

    def _margin(self, text: str, deadline: float | None, force_first: bool) -> tuple[float, tuple[int, int] | None, int, int]:
        """(margines, zakres, liczba ocenionych okien, liczba wszystkich okien). Gdy `force_first` jest fałszem i termin minął,
        nic nie jest oceniane (margines -inf): tak wspólny budżet nie jest łamany przez kolejne warianty tekstu."""
        wins, total = self.windows(text)
        # Okna wyznaczamy po tokenach, ale oceniamy jako podciągi tekstu: zwykłe wywołanie tokenizera dokłada tokeny specjalne
        # i dopełnienie, bez użycia metod wewnętrznych, które zmieniają się między wersjami transformers.
        pieces = [text[sp[0]:sp[1]] if sp else "" for _, sp in wins]
        order = self._spread_order(len(wins))
        chunk = self.window_batch
        best_margin, best_idx, done = -math.inf, 0, 0
        for c0 in range(0, len(order), chunk):
            if deadline is not None and time.perf_counter() > deadline and (done or not force_first):
                break
            idx = order[c0:c0 + chunk]
            with self._lock:  # szybkie tokenizatory HF nie są bezpieczne przy współbieżnym użyciu
                batch = self._tok([pieces[i] for i in idx], padding=True, truncation=True, max_length=self.max_length, return_tensors="pt")
            with self._torch.inference_mode():
                logits = self._model(**batch).logits.double()
                others = self._torch.cat([logits[:, : self._pos], logits[:, self._pos + 1:]], dim=-1)
                margins = logits[:, self._pos] - self._torch.logsumexp(others, dim=-1)
            k = int(margins.argmax())
            if float(margins[k]) > best_margin:
                best_margin, best_idx = float(margins[k]), idx[k]
            done += len(idx)
        return best_margin, wins[best_idx][1], done, total

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        best_margin, best = -math.inf, None
        evaluated = total_windows = 0
        deadline = time.perf_counter() + self.time_budget_ms / 1000 if self.time_budget_ms else None
        for v in norm.variants()[: self.max_variants]:
            # pierwszy wariant (oryginał) zawsze dostaje przynajmniej jedną porcję; kolejne tylko, dopóki budżet czasu nie minął
            margin, span, done, total = self._margin(v.text, deadline, force_first=not evaluated)
            evaluated += done
            total_windows += total
            if margin > best_margin:
                best_margin = margin
                best = RawEvidence(text=v.text[span[0]:span[1]] if span else v.text[:200], variant=v.name, span=span)
        coverage = evaluated / total_windows if total_windows else 1.0
        raw = sigmoid(best_margin)
        score = self.calibration.apply(best_margin, self.target_prior) if self.calibration else raw
        flagged = self.label_threshold is not None and score >= self.label_threshold
        return Finding(score=score, raw_score=raw, coverage=round(coverage, 4), label=self.label if flagged else None, evidence=best)
