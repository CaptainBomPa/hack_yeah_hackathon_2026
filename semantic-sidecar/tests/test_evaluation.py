from collections import Counter
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Config, DetectorConfig
from app.contract import Checkpoint, ClassifyRequest, Finding
from app.detectors.base import Detector
from app.main import create_app
from app.normalize import Normalized
from evaluation.cases import Case, load_cases
from evaluation.metrics import auroc, build_report, percentile, threshold_at_fpr, wilson
from evaluation.run import run_cases

CASES_DIR = Path(__file__).resolve().parent.parent / "evaluation" / "cases"


def test_seed_cases_are_valid_and_balanced_per_checkpoint():
    cases = load_cases([CASES_DIR])
    assert len({c.id for c in cases}) == len(cases)
    kinds = Counter((c.checkpoint.value, c.is_attack) for c in cases)
    for cp in ("P1", "P2", "P3", "P4", "P5"):
        assert kinds[(cp, True)] > 0, f"brak ataków dla {cp}"
        assert kinds[(cp, False)] > 0, f"brak negatywów dla {cp}"
    assert any(c.kind == "hard_negative" for c in cases)


def test_duplicate_ids_rejected(tmp_path):
    f = tmp_path / "x.yaml"
    f.write_text("- {id: a, checkpoint: P1, text: t, kind: benign}\n- {id: a, checkpoint: P1, text: u, kind: benign}\n")
    with pytest.raises(ValueError):
        load_cases([f])


def test_wilson_interval_contains_proportion_and_handles_empty():
    lo, hi = wilson(8, 10)
    assert lo < 0.8 < hi
    assert wilson(0, 0) == (0.0, 0.0)


def test_auroc_perfect_random_and_ties():
    assert auroc([0.9, 0.8], [0.1, 0.2]) == 1.0
    assert auroc([0.1], [0.9]) == 0.0
    assert auroc([0.5], [0.5]) == 0.5
    assert auroc([], [0.5]) is None


def test_percentile_and_threshold_at_fpr():
    assert percentile([1, 2, 3, 4], 0.5) == 2
    neg = [0.1, 0.2, 0.3, 0.4, 0.9]
    t = threshold_at_fpr(neg, 0.2)  # dopuszczamy 1 z 5 fałszywych alarmów
    assert sum(1 for s in neg if s >= t) / len(neg) <= 0.2
    assert t <= 0.9


class KeywordDetector(Detector):
    name = "keyword"
    version = "test"
    checkpoints = frozenset(Checkpoint)

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        return Finding(score=0.9 if "ignore" in req.text.lower() else 0.1)


def _client() -> TestClient:
    cfg = Config(detectors={"keyword": DetectorConfig(enabled=True)})
    return TestClient(create_app(config=cfg, available={"keyword": KeywordDetector()}))


def test_runner_and_report_end_to_end_with_fake_detector():
    cases = load_cases([CASES_DIR])
    client = _client()
    obs = run_cases(cases, lambda p: client.post("/classify", json=p).json())
    report = build_report(obs, threshold=0.5, target_fpr=0.05)
    d = report["detectors"]["keyword"]
    assert report["transport_errors"] == 0
    assert d["n_attack"] + d["n_negative"] == len(cases)
    # prosty detektor łapie "ignore", ale nie ataków bez tego słowa i daje fałszywe alarmy na trudnych negatywach
    assert 0 < d["recall"] < 1
    assert d["fpr_hard_negative"] > 0
    assert "p1-hardneg-keyword_overlap-03" in d["false_positives"]  # "ignore all previous warnings"
    assert d["recall_by_family"]["override"]["n"] == 5


def test_report_without_detectors_is_empty_not_crash():
    client = TestClient(create_app(config=Config(), available={}))
    cases = load_cases([CASES_DIR])[:5]
    report = build_report(run_cases(cases, lambda p: client.post("/classify", json=p).json()))
    assert report["detectors"] == {}


def test_transport_error_counted_not_fatal():
    cases = load_cases([CASES_DIR])[:3]

    def boom(_):
        raise RuntimeError("down")

    report = build_report(run_cases(cases, boom))
    assert report["transport_errors"] == 3
