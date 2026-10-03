import time

from app.contract import (
    ClassifyRequest,
    ClassifyResponse,
    DetectorResult,
    Status,
)
from app.detectors.base import Detector
from app.normalize import Normalized


def run_detectors(detectors: list[Detector], req: ClassifyRequest, norm: Normalized) -> ClassifyResponse:
    """Uruchamia detektory pasujące do punktu kontroli.

    Na razie sekwencyjnie i bez timeoutu. Równoległość i timeouty to osobny krok.
    Błąd detektora daje status `error`, a nie wynik 0 (brak sygnału to inny stan niż "bezpiecznie").
    """
    results: list[DetectorResult] = []
    for det in detectors:
        if req.checkpoint not in det.checkpoints:
            continue
        start = time.perf_counter()
        try:
            finding = det.run(req, norm)
            status = Status.OK
        except Exception:
            finding = None
            status = Status.ERROR
        latency_ms = (time.perf_counter() - start) * 1000
        results.append(
            DetectorResult(
                detector=det.name,
                version=det.version,
                status=status,
                score=finding.score if finding else None,
                label=finding.label if finding else None,
                evidence=finding.evidence if finding else None,
                latency_ms=round(latency_ms, 3),
            )
        )
    return ClassifyResponse(checkpoint=req.checkpoint, results=results)
