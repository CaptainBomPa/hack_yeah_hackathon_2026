"""Przypadki brzegowe kontraktu API (atrapa detektora, bez modelu): rozmiary na granicy limitu, dziwne wejścia, równoległość.

Zasada: sidecar nigdy nie kończy się 500 ani wyjątkiem na wejściu od klienta; zły kontrakt to 4xx, a zbyt duży tekst to 413.
"""

import json
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from app.config import Config, DetectorConfig
from app.contract import Checkpoint, ClassifyRequest, Finding
from app.detectors.base import Detector
from app.main import create_app
from app.normalize import Normalized

LIMIT = 1000


class Echo(Detector):
    name = "echo"
    version = "t"
    checkpoints = frozenset({Checkpoint.P1, Checkpoint.P2, Checkpoint.P5})

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        return Finding(score=min(1.0, len(req.text) / 5000), label=None)


@pytest.fixture(scope="module")
def c():
    cfg = Config(limits={"max_input_chars": LIMIT}, detectors={"echo": DetectorConfig(enabled=True)})
    return TestClient(create_app(config=cfg, available={"echo": Echo()}), raise_server_exceptions=False)


@pytest.mark.parametrize("n", [0, 1, 2, LIMIT - 1, LIMIT])
def test_sizes_up_to_limit_are_accepted(c, n):
    r = c.post("/classify", json={"checkpoint": "P1", "text": "a" * n})
    assert r.status_code == 200 and r.json()["results"][0]["status"] == "ok"


@pytest.mark.parametrize("n", [LIMIT + 1, LIMIT * 2, LIMIT * 100])
def test_over_limit_is_413_not_500(c, n):
    assert c.post("/classify", json={"checkpoint": "P1", "text": "a" * n}).status_code == 413


def test_limit_counts_characters_not_bytes(c):
    assert c.post("/classify", json={"checkpoint": "P1", "text": "ł" * LIMIT}).status_code == 200      # 2 bajty na znak
    assert c.post("/classify", json={"checkpoint": "P1", "text": "😀" * LIMIT}).status_code == 200    # 4 bajty na znak
    assert c.post("/classify", json={"checkpoint": "P1", "text": "😀" * (LIMIT + 1)}).status_code == 413


@pytest.mark.parametrize("text", [
    "", " ", "\n\n\n", "\t" * 50, "\x00", "\x00\x01\x02\x1f", "😀" * 50, "👨‍👩‍👧", "é" * 40, "‮abc‬", "﻿bom start",
    "مرحبا", "你好", "Ｉｇｎｏｒｅ", "a​b​c", "<" * 200, "{" * 300 + "}" * 300, "\\" * 100, "\"" * 100, "%00%0a%0d" * 20,
    "line1\r\nline2\rline3", "   separators", "x" * LIMIT, "a " * (LIMIT // 2),
])
def test_odd_texts_never_crash(c, text):
    r = c.post("/classify", json={"checkpoint": "P1", "text": text})
    assert r.status_code == 200, r.text[:200]
    assert 0.0 <= r.json()["results"][0]["score"] <= 1.0


def test_lone_surrogate_is_handled_without_500(c):
    r = c.post("/classify", content='{"checkpoint":"P1","text":"abc\\ud800def"}', headers={"content-type": "application/json"})
    assert r.status_code in (200, 422)


@pytest.mark.parametrize("body", [
    "not json", "", "[]", "null", "42", '"text"', "{}", '{"checkpoint":"P1"}', '{"text":"x"}', '{"checkpoint":"P1","text":null}',
    '{"checkpoint":"p1","text":"x"}', '{"checkpoint":"P6","text":"x"}', '{"checkpoint":["P1"],"text":"x"}', '{"checkpoint":"P1","text":["x"]}',
    '{"checkpoint":"P1","text":"x","context":"nope"}', '{"checkpoint":"P1","text":"x","context":{"source_trust":"hacker"}}',
    '{"checkpoint":"P1","text":"x","context":{"session_id":123}}', '{"checkpoint":"P1","text":' + '[' * 3000 + ']' * 3000 + '}',
])
def test_malformed_requests_are_client_errors(c, body):
    r = c.post("/classify", content=body, headers={"content-type": "application/json"})
    assert 400 <= r.status_code < 500, (r.status_code, body[:60])


def test_wrong_content_type_and_methods(c):
    assert 400 <= c.post("/classify", content='{"checkpoint":"P1","text":"x"}', headers={"content-type": "text/plain"}).status_code < 500
    assert c.get("/classify").status_code == 405
    assert c.get("/nope").status_code == 404


def test_extra_fields_are_ignored_and_context_is_optional(c):
    assert c.post("/classify", json={"checkpoint": "P1", "text": "x", "unknown": 1, "context": {"extra": True}}).status_code == 200
    assert c.post("/classify", json={"checkpoint": "P1", "text": "x", "context": {"session_id": "s" * 100_000}}).status_code == 200


def test_every_checkpoint_is_answered_and_uncovered_ones_say_so(c):
    for cp, covered in (("P1", True), ("P2", True), ("P3", False), ("P4", False), ("P5", True)):
        body = c.post("/classify", json={"checkpoint": cp, "text": "x"}).json()
        assert body["checkpoint"] == cp and body["covered"] is covered and body["complete"] is covered


def test_parallel_requests_do_not_mix_results(c):
    texts = ["a" * n for n in range(1, 161)]

    def call(t):
        r = c.post("/classify", json={"checkpoint": "P1", "text": t})
        return len(t), r.status_code, r.json()["results"][0]["score"]

    with ThreadPoolExecutor(max_workers=32) as ex:
        out = list(ex.map(call, texts))
    assert all(code == 200 for _, code, _ in out)
    assert all(abs(score - min(1.0, n / 5000)) < 1e-9 for n, _, score in out), "wynik jednego żądania trafił do innego"


def test_health_is_stable_under_load(c):
    with ThreadPoolExecutor(max_workers=16) as ex:
        codes = list(ex.map(lambda _: c.get("/health").status_code, range(100)))
    assert set(codes) == {200}


def test_response_is_valid_json_with_contract_fields(c):
    body = c.post("/classify", json={"checkpoint": "P1", "text": "x"}).json()
    assert {"checkpoint", "results", "complete", "covered", "missing_checks", "normalization"} <= set(body)
    json.dumps(body)
