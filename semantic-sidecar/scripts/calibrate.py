"""Kalibracja wyniku detektora HF (skalowanie Platta na marginesie logitów).

    python -m scripts.calibrate --detector injection_classifier_protectai
    python -m scripts.calibrate --detector horizon_small --config config/semantic.horizon.yaml --fit heldout

Dopasowanie (`--fit neuralchemy`, domyślnie): neuralchemy/train. Ocena na osobnych danych: neuralchemy validation+test oraz
nasze ręczne przypadki (inny rozkład). Tryb `--fit heldout` jest dla modeli trenowanych na neuralchemy (np. Horizon): dopasowanie na
deepset (część treningowa, id < 546) i NotInject, ocena na deepset (część testowa) i naszych ręcznych przypadkach. Wynik: config/calibration/<detektor>.json i tabela "przed/po". Margines każdego tekstu jest
cache'owany w evaluation/data/calib/ (`--reuse` pomija ponowne liczenie modelu).
Skalowanie Platta jest monotoniczne, więc AUROC się nie zmienia. Zmienia się skala, rozdzielczość progu i kalibracja.
"""

import argparse
import json
import math
from datetime import date
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "evaluation" / "data" / "calib"
P2_ONLY = {"indirect_injection", "rag_poisoning"}


def margins_for(det, texts: list[str], cache: Path, reuse: bool) -> list[float]:
    if reuse and cache.exists():
        saved = json.loads(cache.read_text())
        if saved["n"] == len(texts):
            return saved["margins"]
    out = []
    for i, t in enumerate(texts):
        out.append(det.margin(t)[0])
        if (i + 1) % 500 == 0:
            print(f"   {i + 1}/{len(texts)}", flush=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps({"n": len(texts), "margins": out}))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--detector", required=True)
    ap.add_argument("--config", type=Path, default=ROOT / "config" / "semantic.models.yaml")
    ap.add_argument("--reuse", action="store_true")
    ap.add_argument("--fit", choices=["neuralchemy", "heldout"], default="neuralchemy")
    args = ap.parse_args()

    import numpy as np
    from datasets import load_dataset
    from sklearn.linear_model import LogisticRegression

    from app.calibration import PlattCalibration, sigmoid
    from app.config import NormalizationConfig
    from app.contract import Checkpoint
    from app.detectors.hf_classifier import HFClassifierDetector
    from app.normalize import normalize
    from evaluation.calibration_metrics import brier, ece, log_loss, reliability
    from evaluation.cases import load_cases
    from evaluation.metrics import auroc, threshold_at_fpr

    entry = yaml.safe_load(args.config.read_text())["detectors"][args.detector]
    params = {k: v for k, v in entry["params"].items() if k not in ("calibration", "target_prior")}
    det = HFClassifierDetector(name=args.detector, **params)
    norm = lambda t: normalize(t, Checkpoint.P1, NormalizationConfig()).normalized
    uses_p2 = Checkpoint.P2 in det.checkpoints

    own = [c for c in load_cases([ROOT / "evaluation" / "cases"]) if c.checkpoint in det.checkpoints]
    if args.fit == "neuralchemy":
        fit_source = "neuralchemy/Prompt-injection-dataset core/train"
        ds = load_dataset("neuralchemy/Prompt-injection-dataset", "core", split="train")
        rows = [r for r in ds if uses_p2 or r["category"] not in P2_ONLY]
        fit_texts, fit_y = [norm(r["text"]) for r in rows], [int(r["label"]) for r in rows]
        eval_sets = {
            "neuralchemy val+test": [c for c in load_cases([ROOT / "evaluation" / "data" / "neuralchemy.jsonl"]) if c.checkpoint in det.checkpoints],
            "nasze ręczne (inny rozkład)": own,
        }
    else:
        data = ROOT / "evaluation" / "data"
        fit_source = "deepset (id < 546) + NotInject (poza treningiem modelu)"
        fit_cases = [c for c in load_cases([data / "deepset.jsonl"]) if int(c.id.rsplit("-", 1)[1]) < 546]
        fit_cases += load_cases([data / "notinject.jsonl"])
        fit_texts, fit_y = [norm(c.text) for c in fit_cases], [int(c.is_attack) for c in fit_cases]
        eval_sets = {
            "deepset test (id >= 546)": [c for c in load_cases([data / "deepset.jsonl"]) if int(c.id.rsplit("-", 1)[1]) >= 546],
            "nasze ręczne (inny rozkład)": own,
        }

    print(f"== dopasowanie: {fit_source}, detektor {args.detector} (rewizja {det.version})")
    fit_x = margins_for(det, fit_texts, CACHE / f"{args.detector}_fit.json", args.reuse)
    prior = sum(fit_y) / len(fit_y)
    lr = LogisticRegression(penalty=None, solver="lbfgs", max_iter=2000).fit(np.array(fit_x).reshape(-1, 1), fit_y)
    cal = PlattCalibration(a=float(lr.coef_[0][0]), b=float(lr.intercept_[0]), train_prior=prior)
    print(f"   n={len(fit_y)} (ataków {sum(fit_y)}, udział {prior:.3f})  Platt: a={cal.a:.4f}, b={cal.b:.4f}")

    report: dict = {}
    for name, cases in eval_sets.items():
        slug = name.split()[0]
        print(f"== ocena: {name} (n={len(cases)})")
        m = margins_for(det, [norm(c.text) for c in cases], CACHE / f"{args.detector}_eval_{slug}.json", args.reuse)
        y = [int(c.is_attack) for c in cases]
        before = [sigmoid(x) for x in m]
        after = [cal.apply(x) for x in m]
        neg = lambda s: [p for p, t in zip(s, y) if t == 0]
        pos = lambda s: [p for p, t in zip(s, y) if t == 1]
        res = {}
        for label, s in (("przed (sigmoid marginesu)", before), ("po kalibracji", after)):
            t = threshold_at_fpr(neg(s), 0.01)
            res[label] = {
                "brier": brier(s, y), "log_loss": log_loss(s, y), "ece": ece(s, y), "auroc": auroc(pos(s), neg(s)),
                "threshold_fpr1": t, "recall_at_fpr1": sum(1 for p in pos(s) if p >= t) / max(1, len(pos(s))),
                "distinct_scores": len(set(round(p, 12) for p in s)),
            }
        res["reliability_after"] = reliability(after, y)
        report[name] = {"n": len(y), "prior": sum(y) / len(y), **res}
        for label in ("przed (sigmoid marginesu)", "po kalibracji"):
            r = res[label]
            print(f"   {label:28} Brier {r['brier']:.4f}  log-loss {r['log_loss']:.4f}  ECE {r['ece']:.4f}  AUROC {r['auroc']:.4f}  "
                  f"recall@FPR1% {r['recall_at_fpr1']*100:.1f}% (próg {r['threshold_fpr1']:.6f})  różnych wyników {r['distinct_scores']}")

    out = ROOT / "config" / "calibration" / f"{args.detector}.json"
    out.write_text(json.dumps({
        "method": "platt", "a": cal.a, "b": cal.b,
        "fit": {"source": fit_source, "n": len(fit_y), "n_positive": sum(fit_y),
                "prior": prior, "model_revision": det.version, "date": date.today().isoformat()},
        "evaluation": report,
        "note": "Skalibrowany wynik = P(atak | margines) przy udziale ataków ze zbioru dopasowania. Zmień częstość bazową parametrem target_prior.",
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nZapisano {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
