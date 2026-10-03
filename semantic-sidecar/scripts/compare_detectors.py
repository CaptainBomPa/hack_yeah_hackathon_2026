"""Porównanie dwóch detektorów HF na zbiorach CZYSTYCH dla obu (poza treningiem każdego z nich).

    python -m scripts.compare_detectors [--reuse]

Porównujemy rankingi (marginesy logitów), więc wynik nie zależy od kalibracji ani wybranego progu. Metryki: AUROC oraz recall przy
stałym FPR na puli trudnych negatywów. Przedziały ufności z bootstrapu (próbkowanie z zwracaniem pozytywów i negatywów); różnice między
modelami z bootstrapu sparowanego (te same próbki). Marginesy są cache'owane w evaluation/data/compare/.

Zbiory (czyste: żaden model nie był na nich trenowany, wg kart modeli):
  trudne negatywy:  NotInject, OR-Bench-hard (próbka), nasze trudne negatywy
  zwykłe negatywy:  nasze niewinne, niewinne z deepset
  ataki bezpośrednie: nasze ataki P1, deepset, Lakera/gandalf (próbka), Simsonsun jailbreaki (próbka)
  ataki pośrednie (P2): BIPIA, PIArena (próbki zrównoważone)
Wykluczone: neuralchemy, in-the-wild (Horizon trenował), jackhhao, xstest (protectai trenował), llmail/slabs/agentic (Horizon trenował).
"""

import argparse
import json
import time
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "evaluation" / "data"
CACHE = DATA / "compare"
RNG = np.random.default_rng(0)

MODELS = {
    "protectai": (ROOT / "config" / "semantic.protectai.yaml", "injection_classifier_protectai"),
    "horizon": (ROOT / "config" / "semantic.models.yaml", "injection_classifier_horizon"),
}


def sample(rows: list, n: int) -> list:
    if len(rows) <= n:
        return rows
    idx = RNG.choice(len(rows), n, replace=False)
    return [rows[i] for i in sorted(idx)]


def build_sets() -> dict[str, list[tuple[str, str]]]:
    """nazwa zbioru -> [(tekst, grupa)] gdzie grupa to 'hard_neg' | 'benign' | 'attack_direct' | 'attack_indirect'."""
    import pandas as pd

    from evaluation.cases import load_cases

    sets: dict[str, list[tuple[str, str]]] = {}
    own = load_cases([ROOT / "evaluation" / "cases"])
    own = [c for c in own if c.checkpoint.value in ("P1", "P2", "P5")]
    sets["own:hard_neg"] = [(c.text, "hard_neg") for c in own if c.kind == "hard_negative"]
    sets["own:benign"] = [(c.text, "benign") for c in own if c.kind == "benign"]
    sets["own:attacks"] = [(c.text, "attack_direct") for c in own if c.kind == "attack"]
    sets["notinject"] = [(c.text, "hard_neg") for c in load_cases([DATA / "notinject.jsonl"])]
    ds = load_cases([DATA / "deepset.jsonl"])
    sets["deepset:benign"] = [(c.text, "benign") for c in ds if not c.is_attack]
    sets["deepset:attacks"] = [(c.text, "attack_direct") for c in ds if c.is_attack]
    sets["lakera"] = sample([(c.text, "attack_direct") for c in load_cases([DATA / "lakera.jsonl"])], 400)
    hz = DATA / "hz_eval" / "data"
    sets["orbench_hard"] = sample([(t, "hard_neg") for t in pd.read_parquet(hz / "orbench_hard.parquet").text], 600)
    sets["simsonsun"] = sample([(t, "attack_direct") for t in pd.read_parquet(hz / "simsonsun_jailbreaks.parquet").text], 400)
    for name in ("bipia", "piarena"):
        d = pd.read_parquet(hz / f"{name}.parquet")
        pos = [(t, "attack_indirect") for t in d[d.label == 1].text]
        neg = [(t, "benign_doc") for t in d[d.label == 0].text]
        k = min(len(neg), 300)
        sets[f"{name}:attacked"] = sample(pos, k)
        sets[f"{name}:clean"] = sample(neg, k)
    return sets


def margins(model: str, sets: dict, reuse: bool) -> dict[str, tuple[list[float], list[float]]]:
    """nazwa zbioru -> (marginesy, czasy_ms). Tekst normalizowany jak przez gateway (jak scripts/calibrate.py)."""
    from app.config import NormalizationConfig
    from app.contract import Checkpoint
    from app.detectors.hf_classifier import HFClassifierDetector
    from app.normalize import normalize

    cfg_path, det_name = MODELS[model]
    entry = yaml.safe_load(cfg_path.read_text())["detectors"][det_name]
    params = {k: v for k, v in entry["params"].items() if k not in ("calibration", "target_prior")}
    det = HFClassifierDetector(name=det_name, **params)
    norm = lambda t: normalize(t, Checkpoint.P1, NormalizationConfig()).normalized
    CACHE.mkdir(parents=True, exist_ok=True)
    out = {}
    for name, items in sets.items():
        f = CACHE / f"{model}_{name.replace(':', '_')}.json"
        texts = [t for t, _ in items]
        if reuse and f.exists():
            saved = json.loads(f.read_text())
            if saved["n"] == len(texts) and saved.get("digest") == hash_texts(texts):
                out[name] = (saved["margins"], saved["ms"])
                continue
        ms, times = [], []
        for t in texts:
            nt = norm(t)
            start = time.perf_counter()
            ms.append(det.margin(nt)[0])
            times.append((time.perf_counter() - start) * 1000)
        f.write_text(json.dumps({"n": len(texts), "digest": hash_texts(texts), "margins": ms, "ms": times}))
        out[name] = (ms, times)
        print(f"   {model}: {name} ({len(texts)})", flush=True)
    return out


def hash_texts(texts: list[str]) -> str:
    import hashlib

    return hashlib.sha256("\x00".join(texts).encode()).hexdigest()[:16]


def auroc(pos: np.ndarray, neg: np.ndarray) -> float:
    from scipy.stats import rankdata

    ranks = rankdata(np.concatenate([pos, neg]))  # średnie rangi dla remisów
    return float((ranks[: len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def recall_at_fpr(pos: np.ndarray, neg: np.ndarray, fpr: float) -> float:
    thr = np.quantile(neg, 1 - fpr, method="higher")
    # próg ścisły: FPR nie przekracza zadanego
    thr = np.nextafter(thr, np.inf) if (neg >= thr).mean() > fpr else thr
    return float((pos >= thr).mean())


def boot(fn, pos: dict[str, np.ndarray], neg: np.ndarray, n: int = 1000):
    """fn(pos_all, neg) bootstrapowane; zwraca (estymata, dolna, górna). pos to pula pozytywów (połączona)."""
    pos_all = np.concatenate(list(pos.values()))
    est = fn(pos_all, neg)
    vals = []
    for _ in range(n):
        p = pos_all[RNG.integers(0, len(pos_all), len(pos_all))]
        q = neg[RNG.integers(0, len(neg), len(neg))]
        vals.append(fn(p, q))
    return est, float(np.percentile(vals, 2.5)), float(np.percentile(vals, 97.5))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reuse", action="store_true")
    ap.add_argument("--json", type=Path, default=ROOT / "evaluation" / "data" / "compare" / "report.json")
    args = ap.parse_args()

    sets = build_sets()
    print("Zbiory:", {k: len(v) for k, v in sets.items()})
    data = {m: margins(m, sets, args.reuse) for m in MODELS}

    def pool(model: str, groups: set[str]) -> dict[str, np.ndarray]:
        return {n: np.array(data[model][n][0]) for n, v in sets.items() if v and v[0][1] in groups}

    report: dict = {"sets": {k: len(v) for k, v in sets.items()}, "models": {}}
    hard = {m: np.concatenate(list(pool(m, {"hard_neg"}).values())) for m in MODELS}
    easy = {m: np.concatenate(list(pool(m, {"benign"}).values())) for m in MODELS}
    scen = {
        "ataki bezpośrednie vs TRUDNE negatywy": ("attack_direct", hard),
        "ataki bezpośrednie vs ZWYKŁE negatywy": ("attack_direct", easy),
    }
    print()
    for sname, (grp, negs) in scen.items():
        print(f"== {sname}  (pozytywy: {sum(len(v) for v in pool('protectai', {grp}).values())}, negatywy: {len(negs['protectai'])})")
        for m in MODELS:
            pos = pool(m, {grp})
            a = boot(auroc, pos, negs[m])
            r1 = boot(lambda p, q: recall_at_fpr(p, q, 0.01), pos, negs[m])
            r5 = boot(lambda p, q: recall_at_fpr(p, q, 0.05), pos, negs[m])
            print(f"   {m:10} AUROC {a[0]:.3f} [{a[1]:.3f}-{a[2]:.3f}]   recall@FPR1% {r1[0]*100:5.1f}% [{r1[1]*100:.0f}-{r1[2]*100:.0f}]   recall@FPR5% {r5[0]*100:5.1f}% [{r5[1]*100:.0f}-{r5[2]*100:.0f}]")
            report["models"].setdefault(m, {})[sname] = {"auroc": a, "recall_fpr1": r1, "recall_fpr5": r5}
        # sparowana różnica AUROC i recall@1%
        pa = {m: np.concatenate(list(pool(m, {grp}).values())) for m in MODELS}
        diffs_a, diffs_r = [], []
        for _ in range(1000):
            ip = RNG.integers(0, len(pa["protectai"]), len(pa["protectai"]))
            iq = RNG.integers(0, len(negs["protectai"]), len(negs["protectai"]))
            diffs_a.append(auroc(pa["horizon"][ip], negs["horizon"][iq]) - auroc(pa["protectai"][ip], negs["protectai"][iq]))
            diffs_r.append(recall_at_fpr(pa["horizon"][ip], negs["horizon"][iq], 0.01) - recall_at_fpr(pa["protectai"][ip], negs["protectai"][iq], 0.01))
        for lab, d in (("AUROC", diffs_a), ("recall@FPR1%", diffs_r)):
            lo, hi = np.percentile(d, [2.5, 97.5])
            verdict = "Horizon lepszy" if lo > 0 else ("protectai lepszy" if hi < 0 else "różnica nieistotna")
            print(f"   różnica Horizon - protectai, {lab}: {np.mean(d):+.3f} [{lo:+.3f}, {hi:+.3f}]  -> {verdict}")
        print()

    # per zbiór: recall przy progu z FPR 1% na trudnych negatywach, FPR na każdym zbiorze negatywów
    print("== per zbiór, próg ustalony tak, by FPR=1% na puli TRUDNYCH negatywów")
    thr = {m: np.quantile(hard[m], 0.99, method="higher") for m in MODELS}
    print("   (ataki: recall; negatywy: odsetek fałszywych alarmów)")
    print(f"   {'zbiór':22} {'n':>5}  " + "  ".join(f"{m:>10}" for m in MODELS))
    for n, v in sets.items():
        grp = v[0][1]
        row = []
        for m in MODELS:
            x = np.array(data[m][n][0])
            row.append(f"{(x > thr[m]).mean()*100:9.1f}%")
        kind = "recall" if grp.startswith("attack") else "FPR"
        print(f"   {n:22} {len(v):5}  " + "  ".join(row) + f"   ({kind})")
        report.setdefault("per_set", {})[n] = {m: float((np.array(data[m][n][0]) > thr[m]).mean()) for m in MODELS}

    # indirect: AUROC attacked vs clean osobno dla bipia i piarena
    print("\n== ataki pośrednie (P2): AUROC (zaatakowany kontekst vs czysty ten sam typ)")
    for name in ("bipia", "piarena"):
        for m in MODELS:
            pos = np.array(data[m][f"{name}:attacked"][0]); neg = np.array(data[m][f"{name}:clean"][0])
            a = boot(auroc, {name: pos}, neg)
            print(f"   {name:8} {m:10} AUROC {a[0]:.3f} [{a[1]:.3f}-{a[2]:.3f}]  recall@FPR1% {recall_at_fpr(pos, neg, 0.01)*100:.0f}%")

    print("\n== latencja detektora (ms, p50 / p95), wszystkie zbiory razem")
    for m in MODELS:
        allms = np.concatenate([np.array(data[m][n][1]) for n in sets])
        short = np.concatenate([np.array(data[m][n][1]) for n in sets if "bipia" not in n and "piarena" not in n and n not in ("simsonsun",)])
        print(f"   {m:10} wszystkie: {np.percentile(allms,50):6.1f} / {np.percentile(allms,95):6.1f}   krótkie teksty: {np.percentile(short,50):6.1f} / {np.percentile(short,95):6.1f}")
        report["models"][m]["latency_ms"] = {"p50": float(np.percentile(allms, 50)), "p95": float(np.percentile(allms, 95))}

    args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False, default=float), encoding="utf-8")
    print(f"\nRaport: {args.json.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
