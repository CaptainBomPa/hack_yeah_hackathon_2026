import logging
import re
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import Config, load_config
from app.contract import ClassifyRequest, ClassifyResponse
from app.detectors.base import Detector
from app.evidence import EvidenceSanitizer
from app.logging_setup import configure_logging, request_id_var
from app.registry import FACTORIES
from app.runner import SemanticRunner

log = logging.getLogger("app.main")
_REQUEST_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")


def create_app(
    config: Config | None = None,
    available: dict[str, Detector] | None = None,
    config_path: Path | None = None,
    sanitizer: EvidenceSanitizer | None = None,
) -> FastAPI:
    """`available` (nazwa -> instancja) służy testom. Bez niego detektory powstają z fabryk i `params` w konfiguracji."""
    configure_logging()
    cfg = config or load_config(config_path)
    if available is not None:
        pool = available
    else:
        pool = {}
        for n, dc in cfg.detectors.items():
            kind = dc.kind or n
            if kind not in FACTORIES:
                raise ValueError(f"Nieznany rodzaj detektora {kind!r} dla wpisu {n!r}")
            if dc.enabled:
                pool[n] = FACTORIES[kind](n, dc.params)

    unknown = [name for name, dc in cfg.detectors.items() if dc.enabled and name not in pool]
    if unknown:
        raise ValueError(f"Nieznane detektory w konfiguracji: {unknown}")
    enabled = [pool[name] for name, dc in cfg.detectors.items() if dc.enabled]
    needs_signals = [d.name for d in enabled if d.requires_normalization_signals]
    if cfg.input.pre_normalized and needs_signals:
        raise ValueError(
            f"Detektory {needs_signals} wymagają normalizacji w sidecarze, a input.pre_normalized=true. "
            "Wyłącz je albo ustaw input.pre_normalized=false (tryb samodzielny)."
        )
    warmup_ms: dict[str, float] = {}
    for d in enabled:
        started = time.perf_counter()
        d.warmup()  # wyjątek przerywa start
        warmup_ms[d.name] = round((time.perf_counter() - started) * 1000, 1)
    sanitizer = sanitizer or EvidenceSanitizer.from_config(cfg.evidence)
    runner = SemanticRunner(enabled, cfg, sanitizer)

    log.info(
        "sidecar_started",
        extra={"ctx": {
            "detectors": [{"name": d.name, "version": d.version, "checkpoints": sorted(c.value for c in d.checkpoints),
                           "warmup_ms": warmup_ms[d.name]} for d in enabled],
            "pre_normalized": cfg.input.pre_normalized,
            "max_input_chars": cfg.limits.max_input_chars,
            "deadlines_ms": cfg.deadlines.model_dump(),
            "preview_enabled": cfg.evidence.include_preview,
        }},
    )

    app = FastAPI(title="Semantic sidecar")

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        """Identyfikator żądania: z nagłówka X-Request-ID gatewaya (po walidacji) albo nowy. Wraca w odpowiedzi i w każdym logu."""
        incoming = request.headers.get("x-request-id", "")
        rid = incoming if _REQUEST_ID.match(incoming) else uuid.uuid4().hex[:16]
        token = request_id_var.set(rid)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception as exc:  # nie logujemy treści wyjątku: może zawierać fragment wejścia
            log.error("unhandled_error", extra={"ctx": {"path": request.url.path, "error_type": type(exc).__name__}})
            response = JSONResponse(status_code=500, content={"detail": "internal_error", "request_id": rid})
        response.headers["X-Request-ID"] = rid
        if response.status_code >= 400:
            log.warning("http_error", extra={"ctx": {"path": request.url.path, "status": response.status_code,
                                                      "total_ms": round((time.perf_counter() - started) * 1000, 1)}})
        request_id_var.reset(token)
        return response

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_: Request, exc: RequestValidationError):
        # Domyślna odpowiedź FastAPI odsyła wejście w polu `input`. Tu zwracamy tylko miejsce i rodzaj błędu.
        problems = [{"loc": list(e["loc"]), "type": e["type"]} for e in exc.errors()]
        log.warning("invalid_request", extra={"ctx": {"problems": problems}})
        return JSONResponse(status_code=422, content={"detail": problems, "request_id": request_id_var.get()})

    @app.get("/health")
    def health() -> dict:
        return {
            "status": "ok",
            "detectors": [d.name for d in enabled],
            "pre_normalized": cfg.input.pre_normalized,
            "details": [
                {"name": d.name, "version": d.version, "checkpoints": sorted(c.value for c in d.checkpoints),
                 "warmup_ms": warmup_ms[d.name]}
                for d in enabled
            ],
        }

    @app.post("/classify", response_model=ClassifyResponse)
    def classify(req: ClassifyRequest) -> ClassifyResponse:
        if len(req.text) > cfg.limits.max_input_chars:
            log.warning("input_too_large", extra={"ctx": {"checkpoint": req.checkpoint.value, "text_chars": len(req.text),
                                                           "limit": cfg.limits.max_input_chars}})
            raise HTTPException(status_code=413, detail="Tekst przekracza limit")
        started = time.perf_counter()
        resp = runner.classify(req)
        total_ms = round((time.perf_counter() - started) * 1000, 1)
        scored = [r for r in resp.results if r.score is not None]
        min_cov = min((r.coverage for r in resp.results if r.coverage is not None), default=None)
        top = max(scored, key=lambda r: r.score) if scored else None
        # Tylko metadane: długość i skrót HMAC zamiast treści.
        log.info(
            "classified",
            extra={"ctx": {
                "checkpoint": resp.checkpoint.value,
                "text_chars": len(req.text),
                "text_digest": sanitizer.digest(req.text),
                "session_id": (req.context.session_id or None) and req.context.session_id[:64],
                "source_trust": req.context.source_trust,
                "results": [{"detector": r.detector, "status": r.status.value, "reason": r.reason,
                             "score": None if r.score is None else round(r.score, 4), "ms": r.latency_ms} for r in resp.results],
                "top_detector": top.detector if top else None,
                "top_score": round(top.score, 4) if top else None,
                "min_coverage": None if min_cov is None else round(min_cov, 3),
                "covered": resp.covered,
                "complete": resp.complete,
                "missing": [{"check": m.check, "reason": m.reason} for m in resp.missing_checks],
                "total_ms": total_ms,
            }},
        )
        if min_cov is not None and min_cov < 1.0:
            # Tylko część tekstu została oceniona (długi tekst bogaty w tokeny albo wyczerpany budżet czasu).
            log.warning("partial_coverage", extra={"ctx": {"checkpoint": resp.checkpoint.value, "text_chars": len(req.text),
                                                           "min_coverage": round(min_cov, 3)}})
        if total_ms > cfg.deadlines.request_deadline_ms:
            log.warning("slow_request", extra={"ctx": {"total_ms": total_ms, "deadline_ms": cfg.deadlines.request_deadline_ms}})
        return resp

    return app


app = create_app()
