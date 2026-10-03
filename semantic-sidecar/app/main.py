import time
from pathlib import Path

from fastapi import FastAPI, HTTPException

from app.config import Config, load_config
from app.contract import ClassifyRequest, ClassifyResponse
from app.detectors.base import Detector
from app.evidence import EvidenceSanitizer
from app.registry import FACTORIES
from app.runner import SemanticRunner


def create_app(
    config: Config | None = None,
    available: dict[str, Detector] | None = None,
    config_path: Path | None = None,
    sanitizer: EvidenceSanitizer | None = None,
) -> FastAPI:
    """`available` (nazwa -> instancja) służy testom. Bez niego detektory powstają z fabryk i `params` w konfiguracji."""
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
    runner = SemanticRunner(enabled, cfg, sanitizer or EvidenceSanitizer.from_config(cfg.evidence))

    app = FastAPI(title="Semantic sidecar")

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
            raise HTTPException(status_code=413, detail="Tekst przekracza limit")
        return runner.classify(req)

    return app


app = create_app()
