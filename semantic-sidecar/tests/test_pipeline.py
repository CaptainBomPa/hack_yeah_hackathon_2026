from fastapi.testclient import TestClient

from app.config import Config, DetectorConfig
from app.contract import Checkpoint, ClassifyRequest, Finding
from app.detectors.base import Detector
from app.main import create_app
from app.normalize import Normalized


class FixedDetector(Detector):
    name = "fixed"
    version = "test"
    checkpoints = frozenset({Checkpoint.P1})

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        return Finding(score=0.9, label="test")


class BrokenDetector(Detector):
    name = "broken"
    version = "test"
    checkpoints = frozenset({Checkpoint.P1})

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        raise RuntimeError("boom")


def client(detectors: dict[str, Detector], enabled: list[str], max_chars: int = 100) -> TestClient:
    cfg = Config(
        limits={"max_input_chars": max_chars},
        detectors={n: DetectorConfig(enabled=True) for n in enabled},
    )
    return TestClient(create_app(config=cfg, available=detectors))


def test_health_and_empty_pipeline():
    c = client({}, [])
    h = c.get("/health").json()
    assert h["status"] == "ok" and h["detectors"] == [] and h["pre_normalized"] is True and h["details"] == []
    r = c.post("/classify", json={"checkpoint": "P1", "text": "hello"})
    assert r.status_code == 200
    assert r.json()["checkpoint"] == "P1" and r.json()["results"] == []


def test_detector_result_follows_contract():
    c = client({"fixed": FixedDetector()}, ["fixed"])
    body = c.post("/classify", json={"checkpoint": "P1", "text": "x"}).json()
    res = body["results"][0]
    assert res["detector"] == "fixed" and res["status"] == "ok" and res["score"] == 0.9
    assert res["latency_ms"] >= 0


def test_detector_skipped_for_other_checkpoint():
    c = client({"fixed": FixedDetector()}, ["fixed"])
    body = c.post("/classify", json={"checkpoint": "P4", "text": "x"}).json()
    assert body["results"] == []


def test_failing_detector_is_error_not_zero():
    c = client({"broken": BrokenDetector()}, ["broken"])
    res = c.post("/classify", json={"checkpoint": "P1", "text": "x"}).json()["results"][0]
    assert res["status"] == "error" and res["score"] is None


def test_input_limit_returns_413():
    c = client({}, [], max_chars=5)
    assert c.post("/classify", json={"checkpoint": "P1", "text": "123456"}).status_code == 413


def test_unknown_detector_in_config_fails_loudly():
    import pytest

    with pytest.raises(ValueError):
        client({}, ["nope"])
