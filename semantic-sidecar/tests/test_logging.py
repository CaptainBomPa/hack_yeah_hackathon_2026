"""Logi sidecara: struktura, identyfikator żądania i, co najważniejsze, brak treści promptu w jakimkolwiek logu."""

import json
import logging

from fastapi.testclient import TestClient

from app.config import Config, DetectorConfig
from app.contract import Checkpoint, ClassifyRequest, Finding
from app.detectors.base import Detector
from app.logging_setup import JsonFormatter, TextFormatter, configure_logging
from app.main import create_app
from app.normalize import Normalized

CANARY = "TOP-SECRET-CANARY-7731 PESEL 44051401359 sk-live-abcdef"


class Ok(Detector):
    name = "ok"
    version = "t1"
    checkpoints = frozenset({Checkpoint.P1})

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        return Finding(score=0.42, label="x")


class LeakyError(Detector):
    """Wyjątek zawierający wejście, jak robią to prawdziwe biblioteki (np. błąd tokenizatora)."""

    name = "leaky"
    version = "t1"
    checkpoints = frozenset({Checkpoint.P1})

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        raise ValueError(f"cannot parse: {req.text}")


def client(*dets: Detector, max_chars: int = 200) -> TestClient:
    cfg = Config(limits={"max_input_chars": max_chars}, detectors={d.name: DetectorConfig(enabled=True) for d in dets})
    return TestClient(create_app(config=cfg, available={d.name: d for d in dets}), raise_server_exceptions=False)


def everything_logged(caplog) -> str:
    """Wszystko, co trafiło do logów: komunikaty, pola dodatkowe i tekst wyjątków."""
    parts = []
    for r in caplog.records:
        parts.append(r.getMessage())
        parts.append(json.dumps(getattr(r, "ctx", {}), default=str))
        parts.append(str(r.exc_info) if r.exc_info else "")
        parts.append(json.dumps({k: v for k, v in vars(r).items() if k not in ("args", "msg", "exc_info")}, default=str))
    return "\n".join(parts)


def test_prompt_text_never_appears_in_any_log(caplog):
    caplog.set_level(logging.DEBUG)
    c = client(Ok(), LeakyError())
    c.post("/classify", json={"checkpoint": "P1", "text": CANARY})                      # normalny, z detektorem, który rzuca wyjątek z wejściem
    c.post("/classify", json={"checkpoint": "P1", "text": CANARY * 50})                 # za długi: 413
    c.post("/classify", json={"checkpoint": "P9", "text": CANARY})                      # zły punkt kontroli: 422
    c.post("/classify", json={"checkpoint": "P1", "text": 123, "note": CANARY})        # zły typ: 422 z dodatkowym polem
    c.post("/classify", content=CANARY, headers={"content-type": "application/json"})  # niepoprawny JSON
    logs = everything_logged(caplog)
    assert logs.strip(), "nic nie zalogowano"
    for fragment in ("TOP-SECRET-CANARY", "44051401359", "sk-live", "cannot parse"):
        assert fragment not in logs, f"w logach pojawiło się: {fragment}"


def test_invalid_request_response_does_not_echo_input():
    c = client(Ok())
    r = c.post("/classify", json={"checkpoint": "P1", "text": 123, "note": CANARY})
    assert r.status_code == 422
    assert "TOP-SECRET" not in r.text and "request_id" in r.json()


def test_classified_line_has_metadata_but_no_text(caplog):
    caplog.set_level(logging.INFO)
    c = client(Ok())
    c.post("/classify", json={"checkpoint": "P1", "text": CANARY, "context": {"session_id": "s-1", "source_trust": "document"}})
    rec = next(r for r in caplog.records if r.getMessage() == "classified")
    ctx = rec.ctx
    assert ctx["checkpoint"] == "P1" and ctx["text_chars"] == len(CANARY) and len(ctx["text_digest"]) == 12
    assert ctx["session_id"] == "s-1" and ctx["source_trust"] == "document"
    assert ctx["results"] == [{"detector": "ok", "status": "ok", "reason": None, "score": 0.42, "ms": ctx["results"][0]["ms"]}]
    assert ctx["top_detector"] == "ok" and ctx["top_score"] == 0.42 and ctx["covered"] is True and ctx["complete"] is True
    assert ctx["total_ms"] >= 0


def test_same_text_has_same_digest_different_text_differs(caplog):
    caplog.set_level(logging.INFO)
    c = client(Ok())
    for t in ("aaa", "aaa", "bbb"):
        c.post("/classify", json={"checkpoint": "P1", "text": t})
    d = [r.ctx["text_digest"] for r in caplog.records if r.getMessage() == "classified"]
    assert d[0] == d[1] != d[2]


def test_request_id_is_echoed_and_attached_to_logs(caplog):
    caplog.set_level(logging.INFO)
    c = client(Ok())
    r = c.post("/classify", json={"checkpoint": "P1", "text": "x"}, headers={"X-Request-ID": "gw-req-123"})
    assert r.headers["X-Request-ID"] == "gw-req-123"
    assert {rec.request_id for rec in caplog.records if rec.getMessage() == "classified"} == {"gw-req-123"}


def test_missing_or_malicious_request_id_is_replaced():
    c = client(Ok())
    generated = c.post("/classify", json={"checkpoint": "P1", "text": "x"}).headers["X-Request-ID"]
    assert len(generated) == 16
    for bad in ("a b", "x" * 200, "<script>", "id;drop"):
        r = c.post("/classify", json={"checkpoint": "P1", "text": "x"}, headers={"X-Request-ID": bad})
        assert r.headers["X-Request-ID"] != bad and len(r.headers["X-Request-ID"]) == 16


def test_failures_are_logged_as_warnings_with_reason_only(caplog):
    caplog.set_level(logging.INFO)
    c = client(Ok(), LeakyError())
    c.post("/classify", json={"checkpoint": "P1", "text": CANARY})
    c.post("/classify", json={"checkpoint": "P4", "text": "x"})  # brak pokrycia
    msgs = [(r.levelname, r.getMessage(), getattr(r, "ctx", {})) for r in caplog.records]
    assert any(m == "check_missing" and ctx.get("check") == "leaky" and ctx.get("reason") == "exception" and lvl == "WARNING" for lvl, m, ctx in msgs)
    assert any(m == "check_missing" and ctx.get("check") == "coverage" for _, m, ctx in msgs)


def test_oversize_and_startup_are_logged(caplog):
    caplog.set_level(logging.INFO)
    c = client(Ok(), max_chars=10)
    c.post("/classify", json={"checkpoint": "P1", "text": "x" * 11})
    names = [r.getMessage() for r in caplog.records]
    assert "sidecar_started" in names and "input_too_large" in names and "http_error" in names
    big = next(r for r in caplog.records if r.getMessage() == "input_too_large")
    assert big.ctx["text_chars"] == 11 and big.ctx["limit"] == 10


def test_json_and_text_formatters_produce_parseable_lines():
    rec = logging.LogRecord("app.main", logging.INFO, __file__, 1, "classified", (), None)
    rec.request_id = "r1"
    rec.ctx = {"checkpoint": "P1", "results": [{"detector": "ok", "score": 0.5}]}
    line = JsonFormatter().format(rec)
    parsed = json.loads(line)
    assert parsed["msg"] == "classified" and parsed["request_id"] == "r1" and parsed["checkpoint"] == "P1" and parsed["level"] == "INFO"
    assert "classified" in TextFormatter().format(rec) and "r1" in TextFormatter().format(rec)


def test_configure_logging_is_idempotent_and_ignores_bad_level():
    configure_logging("nonsense", "text")
    configure_logging("INFO", "json")
    handlers = [h for h in logging.getLogger().handlers if getattr(h, "_semantic_sidecar_handler", False)]
    assert len(handlers) == 1


def test_partial_coverage_is_logged_as_warning(caplog):
    class Partial(Ok):
        name = "partial"

        def run(self, req, norm):
            return Finding(score=0.1, coverage=0.4)

    caplog.set_level(logging.INFO)
    client(Partial()).post("/classify", json={"checkpoint": "P1", "text": "x"})
    rec = next(r for r in caplog.records if r.getMessage() == "partial_coverage")
    assert rec.levelname == "WARNING" and rec.ctx["min_coverage"] == 0.4
    assert next(r for r in caplog.records if r.getMessage() == "classified").ctx["min_coverage"] == 0.4
