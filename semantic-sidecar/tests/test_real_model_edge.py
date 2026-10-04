"""Przypadki brzegowe na PRAWDZIWYM modelu (pomijane, gdy modelu nie ma na dysku). Sprawdzają solidność, nie jakość detekcji."""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parent.parent
pytestmark = pytest.mark.skipif(not (ROOT / "models" / "injection_classifier_horizon" / "model.safetensors").exists(), reason="brak modelu na dysku")


@pytest.fixture(scope="module")
def c():
    from app.config import load_config
    from app.main import create_app

    return TestClient(create_app(config=load_config(ROOT / "config" / "semantic.models.yaml")), raise_server_exceptions=False)


def one(c, text):
    r = c.post("/classify", json={"checkpoint": "P1", "text": text})
    assert r.status_code == 200, r.text[:200]
    return r.json()


@pytest.mark.parametrize("text", ["", " ", "\n" * 50, "\x00", "😀" * 200, "é" * 200, "‮text", "a" * 20000, "a " * 10000, "1234567890" * 2000])
def test_garbage_inputs_give_a_score_not_an_error(c, text):
    d = one(c, text)
    res = d["results"][0]
    assert res["status"] == "ok" and 0.0 <= res["score"] <= 1.0 and d["complete"] is True


def test_token_dense_long_text_reports_partial_coverage_instead_of_pretending_full_check(c):
    # 20000 znaków prozy mieści się w oknach (pokrycie 1.0), ale tekst bogaty w tokeny (cyfry) już nie: atak ukryty w nim mógłby umknąć.
    res = one(c, "1234567890" * 2000)["results"][0]
    assert res["status"] == "ok" and res["coverage"] is not None and res["coverage"] < 1.0


def test_ordinary_prose_at_the_limit_is_fully_covered(c):
    prose = ("The committee reviewed the annual budget and agreed to postpone the renovation until spring. " * 300)[:20000]
    assert one(c, prose)["results"][0]["coverage"] == 1.0


def test_short_text_is_fully_covered(c):
    assert one(c, "How do I sort a list in Python?")["results"][0]["coverage"] == 1.0


def test_latency_at_the_size_limit_stays_within_the_request_deadline(c):
    res = one(c, ("Quarterly figures were stable across all regions. " * 500)[:20000])["results"][0]
    assert res["latency_ms"] < 2000, f"20000 znaków trwa {res['latency_ms']} ms przy deadline 2000 ms"
