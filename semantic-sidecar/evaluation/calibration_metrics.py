"""Metryki jakości kalibracji: im bliżej 0, tym lepiej (Brier, log-loss, ECE)."""

import math


def brier(probs: list[float], labels: list[int]) -> float:
    return sum((p - y) ** 2 for p, y in zip(probs, labels)) / len(probs)


def log_loss(probs: list[float], labels: list[int], eps: float = 1e-12) -> float:
    total = 0.0
    for p, y in zip(probs, labels):
        p = min(max(p, eps), 1 - eps)
        total -= math.log(p) if y else math.log(1 - p)
    return total / len(probs)


def reliability(probs: list[float], labels: list[int], bins: int = 10) -> list[dict]:
    """Dla każdego przedziału przewidywanego prawdopodobieństwa: ile przypadków i jaki odsetek to faktycznie ataki."""
    rows = []
    for i in range(bins):
        lo, hi = i / bins, (i + 1) / bins
        idx = [j for j, p in enumerate(probs) if (lo <= p < hi) or (i == bins - 1 and p == 1.0)]
        if idx:
            rows.append({
                "bin": f"{lo:.1f}-{hi:.1f}", "n": len(idx),
                "mean_pred": sum(probs[j] for j in idx) / len(idx),
                "observed": sum(labels[j] for j in idx) / len(idx),
            })
    return rows


def ece(probs: list[float], labels: list[int], bins: int = 10) -> float:
    """Expected Calibration Error: średnia ważona różnica między przewidywaniem a obserwowaną częstością."""
    n = len(probs)
    return sum(r["n"] / n * abs(r["mean_pred"] - r["observed"]) for r in reliability(probs, labels, bins))
