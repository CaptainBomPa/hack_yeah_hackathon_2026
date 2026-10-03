"""Uruchamianie detektorów z deadline'ami i jawną obsługą brakujących kontroli.

Zasada: brak wyniku nigdy nie wygląda jak "wynik 0". Awaria normalizacji, timeout, wyjątek albo nieuruchomiony
detektor dostają status != ok i trafiają do `missing_checks`, a gateway decyduje, co z tym zrobić.

Ograniczenie: Python nie potrafi przerwać działającego wątku. Po timeout wątek dobiega końca w tle, a odpowiedź
wraca na czas. Skończona pula (`max_workers`) sprawia, że przy zablokowanych wątkach kolejne żądania kończą się
statusem timeout, a nie zawieszeniem. Twardy deadline po stronie gatewaya jest ostateczną ochroną.
"""

import logging
import time
from concurrent.futures import Future, ThreadPoolExecutor, wait
from concurrent.futures import TimeoutError as FutureTimeout

from app.config import Config
from app.contract import (
    ClassifyRequest,
    ClassifyResponse,
    DetectorResult,
    Finding,
    MissingCheck,
    NormalizationInfo,
    Status,
)
from app.detectors.base import Detector
from app.evidence import EvidenceSanitizer
from app.normalize import Normalized, Signals, normalize

log = logging.getLogger(__name__)


def _timed(det: Detector, req: ClassifyRequest, norm: Normalized) -> tuple[Finding, float]:
    start = time.perf_counter()
    finding = det.run(req, norm)
    return finding, (time.perf_counter() - start) * 1000


def _ms(since: float) -> float:
    return round((time.perf_counter() - since) * 1000, 3)


class SemanticRunner:
    def __init__(self, detectors: list[Detector], cfg: Config, sanitizer: EvidenceSanitizer):
        self.detectors = detectors
        self.cfg = cfg
        self.sanitizer = sanitizer
        self.pool = ThreadPoolExecutor(max_workers=cfg.deadlines.max_workers, thread_name_prefix="semantic")

    def classify(self, req: ClassifyRequest) -> ClassifyResponse:
        d = self.cfg.deadlines
        started = time.perf_counter()
        deadline = started + d.request_deadline_ms / 1000

        if self.cfg.input.pre_normalized:
            # Ustalenie z zespołem: tekst przychodzi znormalizowany. Nie normalizujemy i nie ma czego "gubić".
            norm, norm_failure = Normalized(req.text, req.text, None, [], Signals()), None
        else:
            norm, norm_failure = self._normalize(req, deadline)
        results: list[DetectorResult] = []
        missing: list[MissingCheck] = []
        if norm_failure:
            missing.append(MissingCheck(check="normalization", status=norm_failure[0], reason=norm_failure[1]))

        fallback = Normalized(req.text, req.text, None, [], Signals(error=True))
        futures: dict[Future, Detector] = {}
        for det in self.detectors:
            if req.checkpoint not in det.checkpoints:
                continue
            if norm_failure and det.requires_normalization:
                results.append(self._failed(det, Status.ERROR, norm_failure[1], 0.0))
                continue
            futures[self.pool.submit(_timed, det, req, norm or fallback)] = det

        budget_left = max(0.0, deadline - time.perf_counter())
        bounded_by_deadline = budget_left < d.detector_timeout_ms / 1000
        wait_started = time.perf_counter()
        done, _ = wait(futures, timeout=min(d.detector_timeout_ms / 1000, budget_left))
        for fut, det in futures.items():
            if fut in done:
                results.append(self._finished(det, fut))
            elif fut.cancel():  # nie zdążył wystartować (pula zajęta)
                results.append(self._failed(det, Status.SKIPPED, "deadline_exceeded", _ms(wait_started)))
            else:
                reason = "deadline_exceeded" if bounded_by_deadline else "timeout"
                results.append(self._failed(det, Status.TIMEOUT, reason, _ms(wait_started)))

        for r in results:
            if r.status != Status.OK:
                missing.append(MissingCheck(check=r.detector, status=r.status, reason=r.reason or "unknown"))

        return ClassifyResponse(
            checkpoint=req.checkpoint,
            results=results,
            complete=not missing,
            missing_checks=missing,
            normalization=self._info(norm, norm_failure),
        )

    def _normalize(self, req: ClassifyRequest, deadline: float):
        """Zwraca (wynik lub None, (status, powód) lub None). Normalizacja też podlega deadline'owi."""
        d = self.cfg.deadlines
        fut = self.pool.submit(normalize, req.text, req.checkpoint, self.cfg.normalization)
        timeout = min(d.normalization_timeout_ms / 1000, max(0.0, deadline - time.perf_counter()))
        try:
            norm = fut.result(timeout=timeout)
        except FutureTimeout:
            fut.cancel()
            return None, (Status.TIMEOUT, "normalization_timeout")
        except Exception:
            return None, (Status.ERROR, "normalization_failed")
        if norm.signals.error:
            return None, (Status.ERROR, "normalization_failed")
        return norm, None

    def _finished(self, det: Detector, fut: Future) -> DetectorResult:
        try:
            finding, latency = fut.result()
        except Exception as exc:
            # Nie logujemy ani nie zwracamy treści wyjątku: może zawierać fragment wejścia (czyli sekret).
            log.warning("detector=%s zgłosił wyjątek typu %s", det.name, type(exc).__name__)
            return self._failed(det, Status.ERROR, "exception", 0.0)
        return DetectorResult(
            detector=det.name,
            version=det.version,
            status=Status.OK,
            score=finding.score,
            raw_score=finding.raw_score,
            coverage=finding.coverage,
            label=finding.label,
            evidence=self.sanitizer.sanitize(finding.evidence),
            latency_ms=round(latency, 3),
        )

    @staticmethod
    def _failed(det: Detector, status: Status, reason: str, latency_ms: float) -> DetectorResult:
        return DetectorResult(
            detector=det.name, version=det.version, status=status, reason=reason, latency_ms=latency_ms
        )

    def _info(self, norm: Normalized | None, failure) -> NormalizationInfo:
        if self.cfg.input.pre_normalized:
            return NormalizationInfo(changed=False, variants=["original"], signals={"pre_normalized": True})
        if norm is None:
            return NormalizationInfo(changed=False, variants=[], signals={"failed": failure[1] if failure else "unknown"})
        return NormalizationInfo(
            changed=norm.changed,
            variants=[v.name + (f":{v.detail}" if v.detail else "") for v in norm.variants()],
            signals={k: v for k, v in vars(norm.signals).items() if v not in (0, False, [])},
        )
