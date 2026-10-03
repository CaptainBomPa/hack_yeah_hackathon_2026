"""Runner ewaluacji: wysyła przypadki do sidecara i liczy metryki per detektor.

Przykłady (z katalogu semantic-sidecar):
    python -m evaluation.run --inprocess
    python -m evaluation.run --url http://localhost:8100 --json report.json
"""

import argparse
import json
import random
import sys
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from evaluation.cases import Case, load_cases
from evaluation.metrics import Observation, build_report

ROOT = Path(__file__).resolve().parent
DEFAULT_PATHS = [ROOT / "cases", ROOT / "data"]


def _payload(case: Case) -> dict:
    return {"checkpoint": case.checkpoint.value, "text": case.text, "context": case.context.model_dump()}


def sample_per_source(cases: list[Case], limit: int | None, seed: int) -> list[Case]:
    """Ogranicza liczbę przypadków z jednego źródła (losowo, powtarzalnie), żeby publiczne zbiory nie zdominowały ręcznych."""
    if limit is None:
        return cases
    rng = random.Random(seed)
    by_src: dict[str, list[Case]] = {}
    for c in cases:
        by_src.setdefault(c.source, []).append(c)
    out: list[Case] = []
    for src, items in by_src.items():
        out.extend(items if src == "own" or len(items) <= limit else rng.sample(items, limit))
    return out


def run_cases(cases: list[Case], send, workers: int = 1) -> list[Observation]:
    def one(case: Case) -> Observation:
        start = time.perf_counter()
        try:
            resp = send(_payload(case))
            return Observation(case, resp, (time.perf_counter() - start) * 1000)
        except Exception as exc:  # błąd transportu nie przerywa całego przebiegu
            return Observation(case, None, (time.perf_counter() - start) * 1000, error=str(exc))

    if workers <= 1:
        return [one(c) for c in cases]
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(one, cases))


def _fmt(v, pct: bool = False) -> str:
    if v is None:
        return "-"
    return f"{v * 100:.1f}%" if pct else f"{v:.3f}"


def print_report(report: dict, cases: list[Case]) -> None:
    kinds = Counter(c.kind for c in cases)
    print(f"\nPrzypadki: {report['n_cases']} ({dict(kinds)}), błędy transportu: {report['transport_errors']}")
    e2e = report["end_to_end_ms"]
    print(f"Czas end-to-end: p50={_fmt(e2e['p50'])} ms, p95={_fmt(e2e['p95'])} ms")
    if not report["detectors"]:
        print("\nBrak wyników detektorów: żaden detektor nie jest włączony w config/semantic.yaml.")
        return
    for name, d in report["detectors"].items():
        rc, fc = d["recall_ci"], d["fpr_ci"]
        print(f"\n== {name} ==  próg {d['threshold']}  (ataki {d['n_attack']}, negatywy {d['n_negative']}, w tym trudne {d['n_hard_negative']}, awarie {d['failures']})")
        print(f"  recall {_fmt(d['recall'], True)}" + (f"  [95% CI {rc[0]*100:.0f}-{rc[1]*100:.0f}%]" if rc else ""))
        print(f"  FPR    {_fmt(d['fpr'], True)}" + (f"  [95% CI {fc[0]*100:.0f}-{fc[1]*100:.0f}%]" if fc else "") + f"   FPR na trudnych negatywach {_fmt(d['fpr_hard_negative'], True)}")
        print(f"  precyzja {_fmt(d['precision'], True)}   AUROC {_fmt(d['auroc'])}")
        print(f"  przy FPR<={d['target_fpr']*100:.1f}%: próg {_fmt(d['threshold_at_target_fpr'])}, recall {_fmt(d['recall_at_target_fpr'], True)} (osiągnięty FPR {_fmt(d['achieved_fpr_at_target'], True)})")
        print(f"  latencja detektora p50={_fmt(d['latency_ms']['p50'])} ms, p95={_fmt(d['latency_ms']['p95'])} ms")
        print("  recall per rodzina: " + ", ".join(f"{f} {v['recall']*100:.0f}% (n={v['n']})" for f, v in d["recall_by_family"].items()))
        if d["missed_attacks"]:
            print("  chybione ataki: " + ", ".join(d["missed_attacks"]))
        if d["false_positives"]:
            print("  fałszywe alarmy: " + ", ".join(d["false_positives"]))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:8100", help="adres sidecara")
    ap.add_argument("--inprocess", action="store_true", help="uruchom aplikację w procesie, bez serwera")
    ap.add_argument("--cases", type=Path, nargs="*", default=DEFAULT_PATHS, help="pliki lub katalogi z przypadkami")
    ap.add_argument("--checkpoint", help="tylko ten punkt kontroli, np. P1")
    ap.add_argument("--max-per-source", type=int, help="limit przypadków z jednego publicznego źródła")
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--target-fpr", type=float, default=0.01)
    ap.add_argument("--workers", type=int, default=4, help="równoległość w trybie HTTP")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--json", type=Path, help="zapisz raport do pliku")
    args = ap.parse_args(argv)

    paths = [p for p in args.cases if p.exists()]
    cases = load_cases(paths)
    if args.checkpoint:
        cases = [c for c in cases if c.checkpoint.value == args.checkpoint]
    cases = sample_per_source(cases, args.max_per_source, args.seed)
    if not cases:
        print("Brak przypadków do uruchomienia.", file=sys.stderr)
        return 2

    if args.inprocess:
        from fastapi.testclient import TestClient

        from app.main import app

        client = TestClient(app)
        send = lambda payload: _post(client, payload)
        workers = 1
    else:
        import httpx

        client = httpx.Client(base_url=args.url, timeout=30)
        send = lambda payload: _post(client, payload)
        workers = args.workers

    report = build_report(run_cases(cases, send, workers), args.threshold, args.target_fpr)
    print_report(report, cases)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nRaport zapisany: {args.json}")
    return 0


def _post(client, payload: dict) -> dict:
    r = client.post("/classify", json=payload)
    r.raise_for_status()
    return r.json()


if __name__ == "__main__":
    raise SystemExit(main())
