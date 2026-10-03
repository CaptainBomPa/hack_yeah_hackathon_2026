from pathlib import Path

from fastapi import FastAPI, HTTPException

from app.config import Config, load_config
from app.contract import ClassifyRequest, ClassifyResponse, NormalizationInfo
from app.detectors.base import Detector
from app.normalize import normalize
from app.registry import FACTORIES
from app.runner import run_detectors


def create_app(
    config: Config | None = None,
    available: dict[str, Detector] | None = None,
    config_path: Path | None = None,
) -> FastAPI:
    """`available` (nazwa -> instancja) służy testom. Bez niego detektory powstają z fabryk i `params` w konfiguracji."""
    cfg = config or load_config(config_path)
    if available is not None:
        pool = available
    else:
        pool = {n: FACTORIES[n](dc.params) for n, dc in cfg.detectors.items() if n in FACTORIES}

    unknown = [name for name in cfg.detectors if name not in pool]
    if unknown:
        raise ValueError(f"Nieznane detektory w konfiguracji: {unknown}")
    enabled = [pool[name] for name, dc in cfg.detectors.items() if dc.enabled]

    app = FastAPI(title="Semantic sidecar")

    @app.get("/health")
    def health() -> dict:
        return {"status": "ok", "detectors": [d.name for d in enabled]}

    @app.post("/classify", response_model=ClassifyResponse)
    def classify(req: ClassifyRequest) -> ClassifyResponse:
        if len(req.text) > cfg.limits.max_input_chars:
            raise HTTPException(status_code=413, detail="Tekst przekracza limit")
        norm = normalize(req.text, req.checkpoint, cfg.normalization)
        resp = run_detectors(enabled, req, norm)
        resp.normalization = NormalizationInfo(
            changed=norm.changed,
            variants=[v.name + (f":{v.detail}" if v.detail else "") for v in norm.variants()],
            signals={k: v for k, v in vars(norm.signals).items() if v not in (0, False, [])},
        )
        return resp

    return app


app = create_app()
