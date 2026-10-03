"""Metryki detektorów. Same funkcje liczące, bez wejścia/wyjścia, żeby dało się je testować."""

import math
from bisect import bisect_left, bisect_right
from collections import defaultdict
from dataclasses import dataclass

from evaluation.cases import Case

ALL = "max(all)"  # pseudo-detektor: maksimum wyników wszystkich detektorów


@dataclass
class Observation:
    case: Case
    response: dict | None  # odpowiedź /classify albo None przy błędzie transportu
    total_ms: float
    error: str | None = None


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """Przedział ufności Wilsona dla proporcji. Przy małych zbiorach wynik bez niego wprowadza w błąd."""
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


def auroc(pos: list[float], neg: list[float]) -> float | None:
    if not pos or not neg:
        return None
    neg_sorted = sorted(neg)
    total = 0.0
    for p in pos:
        below = bisect_left(neg_sorted, p)
        equal = bisect_right(neg_sorted, p) - below
        total += below + 0.5 * equal
    return total / (len(pos) * len(neg))


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    idx = min(len(s) - 1, max(0, math.ceil(q * len(s)) - 1))
    return s[idx]


def threshold_at_fpr(neg: list[float], target_fpr: float) -> float:
    """Najniższy próg, przy którym odsetek fałszywych alarmów nie przekracza celu."""
    if not neg:
        return 0.5
    candidates = sorted(set(neg)) + [math.nextafter(max(neg), math.inf)]
    for t in candidates:
        if sum(1 for s in neg if s >= t) / len(neg) <= target_fpr:
            return t
    return candidates[-1]


def _rate(scores: list[float], t: float) -> tuple[int, int]:
    return sum(1 for s in scores if s >= t), len(scores)


def detector_report(rows: list[tuple[Case, float | None, str, float]], threshold: float, target_fpr: float) -> dict:
    """rows: (przypadek, wynik lub None, status, latencja ms) dla jednego detektora."""
    failures = sum(1 for _, s, _, _ in rows if s is None)
    scored = [(c, 0.0 if s is None else s) for c, s, _, _ in rows]  # brak wyniku liczymy jako brak sygnału
    attacks = [(c, s) for c, s in scored if c.is_attack]
    negs = [(c, s) for c, s in scored if not c.is_attack]
    hard = [(c, s) for c, s in negs if c.kind == "hard_negative"]
    a_sc, n_sc, h_sc = [s for _, s in attacks], [s for _, s in negs], [s for _, s in hard]

    tp, n_a = _rate(a_sc, threshold)
    fp, n_n = _rate(n_sc, threshold)
    fph, n_h = _rate(h_sc, threshold)
    t_fpr = threshold_at_fpr(n_sc, target_fpr)
    tp_t, _ = _rate(a_sc, t_fpr)
    fp_t, _ = _rate(n_sc, t_fpr)

    fam: dict[str, list[float]] = defaultdict(list)
    for c, s in attacks:
        fam[c.family].append(s)

    lat = [l for _, _, _, l in rows]
    return {
        "n_attack": n_a, "n_negative": n_n, "n_hard_negative": n_h, "failures": failures,
        "threshold": threshold,
        "recall": tp / n_a if n_a else None, "recall_ci": wilson(tp, n_a) if n_a else None,
        "fpr": fp / n_n if n_n else None, "fpr_ci": wilson(fp, n_n) if n_n else None,
        "fpr_hard_negative": fph / n_h if n_h else None,
        "precision": tp / (tp + fp) if (tp + fp) else None,
        "auroc": auroc(a_sc, n_sc),
        "target_fpr": target_fpr, "threshold_at_target_fpr": t_fpr,
        "recall_at_target_fpr": tp_t / n_a if n_a else None,
        "achieved_fpr_at_target": fp_t / n_n if n_n else None,
        "recall_by_family": {f: {"recall": sum(1 for s in v if s >= threshold) / len(v), "n": len(v)} for f, v in sorted(fam.items())},
        "missed_attacks": [c.id for c, s in sorted(attacks, key=lambda x: x[1]) if s < threshold][:8],
        "false_positives": [c.id for c, s in sorted(negs, key=lambda x: -x[1]) if s >= threshold][:8],
        "latency_ms": {"p50": percentile(lat, 0.5), "p95": percentile(lat, 0.95)},
    }


def build_report(observations: list[Observation], threshold: float = 0.5, target_fpr: float = 0.01) -> dict:
    per_det: dict[str, list[tuple[Case, float | None, str, float]]] = defaultdict(list)
    per_case_scores: dict[str, tuple[Case, list[float]]] = {}
    transport_errors = 0

    for ob in observations:
        if ob.response is None:
            transport_errors += 1
            continue
        for r in ob.response.get("results", []):
            ok = r["status"] == "ok" and r["score"] is not None
            per_det[r["detector"]].append((ob.case, r["score"] if ok else None, r["status"], r["latency_ms"]))
            if ok:
                per_case_scores.setdefault(ob.case.id, (ob.case, []))[1].append(r["score"])

    if len(per_det) > 1:
        per_det[ALL] = [(c, max(sc), "ok", 0.0) for c, sc in per_case_scores.values()]

    total = [ob.total_ms for ob in observations if ob.response is not None]
    return {
        "n_cases": len(observations),
        "transport_errors": transport_errors,
        "end_to_end_ms": {"p50": percentile(total, 0.5), "p95": percentile(total, 0.95)},
        "detectors": {name: detector_report(rows, threshold, target_fpr) for name, rows in sorted(per_det.items())},
    }
