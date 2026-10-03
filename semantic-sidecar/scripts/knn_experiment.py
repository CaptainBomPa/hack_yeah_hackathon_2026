"""Eksperyment: czy kNN na embeddingach (indeks ataków i niewinnych) pomaga, zwłaszcza na trudnych negatywach?

    python -m scripts.knn_experiment [--json wynik.json]

Protokół bez wycieku danych:
  * indeks: neuralchemy/train (+ opcjonalnie evaluation/index_corpus, osobny korpus trudnych negatywów),
  * wybór progu i dopasowanie stackera: neuralchemy VALIDATION,
  * ocena: neuralchemy TEST oraz nasze ręczne przypadki (evaluation/cases, inny rozkład).
Progi z validation są stosowane na test i na nasze przypadki: mierzymy realny FPR po przeniesieniu progu.
Margines `protectai` bierzemy z cache scripts/calibrate.py (evaluation/data/calib/).
"""

import argparse
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
CAL = ROOT / "evaluation" / "data" / "calib"
MODELS = {"minilm": ROOT / "models" / "embeddings_minilm", "bge": ROOT / "models" / "embeddings_bge_small"}
N_VAL = 941  # pierwsze 941 przypadków w neuralchemy.jsonl to validation, reszta to test (kolejność z fetch_public)


def topk_mean(S: np.ndarray, k: int) -> np.ndarray:
    k = min(k, S.shape[1])
    return np.partition(S, -k, axis=1)[:, -k:].mean(axis=1)


def knn_scores(Q, E, y, k):
    """diff: średnie podobieństwo do k najbliższych ataków minus do k najbliższych niewinnych. vote: ważony podobieństwem udział ataków wśród k najbliższych."""
    att, ben = E[y == 1], E[y == 0]
    diff = topk_mean(Q @ att.T, k) - topk_mean(Q @ ben.T, k)
    S = Q @ E.T
    idx = np.argpartition(S, -k, axis=1)[:, -k:]
    w = np.take_along_axis(S, idx, axis=1).clip(min=1e-6)
    vote = (w * y[idx]).sum(axis=1) / w.sum(axis=1)
    return {"diff": diff, "vote": vote}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()

    from datasets import load_dataset
    from sentence_transformers import SentenceTransformer
    from sklearn.linear_model import LogisticRegression

    from app.config import NormalizationConfig
    from app.contract import Checkpoint
    from app.normalize import normalize
    from evaluation.cases import load_cases
    from evaluation.metrics import auroc, threshold_at_fpr, wilson

    norm = lambda t: normalize(t, Checkpoint.P1, NormalizationConfig()).normalized
    cps = {Checkpoint.P1, Checkpoint.P2, Checkpoint.P5}
    ds = load_dataset("neuralchemy/Prompt-injection-dataset", "core", split="train")
    train_t, train_y = [norm(r["text"]) for r in ds], np.array([int(r["label"]) for r in ds])
    corp = load_cases([ROOT / "evaluation" / "index_corpus"])
    corp_t, corp_y = [norm(c.text) for c in corp], np.array([int(c.is_attack) for c in corp])
    vt = [c for c in load_cases([ROOT / "evaluation" / "data" / "neuralchemy.jsonl"]) if c.checkpoint in cps]
    own = [c for c in load_cases([ROOT / "evaluation" / "cases"]) if c.checkpoint in cps]
    pm_vt = np.array(json.loads((CAL / "injection_classifier_protectai_eval_neuralchemy.json").read_text())["margins"])
    pm_own = np.array(json.loads((CAL / "injection_classifier_protectai_eval_nasze.json").read_text())["margins"])
    assert len(vt) == len(pm_vt) and len(own) == len(pm_own), "cache marginesów nie pasuje do przypadków"
    sets = {
        "val": (vt[:N_VAL], pm_vt[:N_VAL]), "test": (vt[N_VAL:], pm_vt[N_VAL:]), "own": (own, pm_own),
    }
    Y = {k: np.array([int(c.is_attack) for c in v[0]]) for k, v in sets.items()}
    HARD = {k: np.array([c.kind == "hard_negative" for c in v[0]]) for k, v in sets.items()}
    print(f"indeks: train {len(train_t)} (ataków {train_y.sum()}), korpus {len(corp_t)} (ataków {corp_y.sum()}) | "
          f"val {len(Y['val'])}, test {len(Y['test'])}, own {len(Y['own'])} (trudne negatywy: val {HARD['val'].sum()}, test {HARD['test'].sum()}, own {HARD['own'].sum()})")

    def op(score, split, thr):  # recall, FPR, FPR na trudnych negatywach przy progu
        y, h = Y[split], HARD[split]
        pos, neg = score[y == 1], score[y == 0]
        r = (pos >= thr).mean(); f = (neg >= thr).mean(); fh = (score[h] >= thr).mean() if h.any() else float("nan")
        return r, f, fh

    def auc(score, split):
        y = Y[split]; return auroc(list(score[y == 1]), list(score[y == 0]))

    results = []
    for mname, mpath in MODELS.items():
        model = SentenceTransformer(str(mpath), device="cpu")
        enc = lambda texts: model.encode(texts, batch_size=64, normalize_embeddings=True, show_progress_bar=False)
        E_train, E_corp = enc(train_t), enc(corp_t)
        Q = {k: enc([norm(c.text) for c in v[0]]) for k, v in sets.items()}
        # najbliższe podobieństwo trudnych negatywów z `own` do korpusu (miara, jak bardzo styl korpusu przypomina nasz zbiór oceny)
        own_hard = Q["own"][HARD["own"]]
        print(f"\n### embeddingi: {mname}  (najbliższe podobieństwo naszych trudnych negatywów do korpusu: średnio {(own_hard @ E_corp.T).max(axis=1).mean():.3f}, "
              f"do train: {(own_hard @ E_train.T).max(axis=1).mean():.3f})")
        for variant in ("train", "train+korpus"):
            E = E_train if variant == "train" else np.vstack([E_train, E_corp])
            y = train_y if variant == "train" else np.concatenate([train_y, corp_y])
            for k in (5, 10, 20):
                feats = {s: knn_scores(Q[s], E, y, k) for s in Q}
                for kind in ("diff", "vote"):
                    sc = {s: feats[s][kind] for s in Q}
                    thr = threshold_at_fpr(list(sc["val"][Y["val"] == 0]), 0.01)
                    # stacker: [margines protectai, wynik kNN] dopasowany na validation
                    Xv = np.c_[sets["val"][1], sc["val"]]
                    lr = LogisticRegression(penalty=None, max_iter=2000).fit(Xv, Y["val"])
                    st = {s: lr.decision_function(np.c_[sets[s][1], sc[s]]) for s in Q}
                    thr_st = threshold_at_fpr(list(st["val"][Y["val"] == 0]), 0.01)
                    row = {
                        "emb": mname, "index": variant, "k": k, "score": kind,
                        "knn_auc": {s: auc(sc[s], s) for s in Q},
                        "knn_op": {s: op(sc[s], s, thr) for s in ("test", "own")},
                        "stack_auc": {s: auc(st[s], s) for s in Q}, "stack_coef": lr.coef_[0].tolist(),
                        "stack_op": {s: op(st[s], s, thr_st) for s in ("test", "own")},
                    }
                    results.append(row)

    # bazowy protectai sam: próg z validation na marginesie
    base = {s: sets[s][1] for s in sets}
    thr_b = threshold_at_fpr(list(base["val"][Y["val"] == 0]), 0.01)
    print("\n=== BAZA: protectai sam (próg z validation dla FPR 1%) ===")
    print(f"AUROC val/test/own: {auc(base['val'],'val'):.3f} / {auc(base['test'],'test'):.3f} / {auc(base['own'],'own'):.3f}")
    for s in ("test", "own"):
        r, f, fh = op(base[s], s, thr_b); print(f"  {s:5} recall {r*100:5.1f}%  FPR {f*100:5.1f}%  FPR trudne {fh*100:5.1f}%")

    print("\n=== kNN i STACK (AUROC val/test/own; operating point z progu validation) ===")
    print(f"{'emb':7}{'indeks':14}{'k':>3} {'wynik':5} | {'kNN AUROC':>20} | {'STACK AUROC (val=in-sample)':>28} | {'STACK test: rec/FPR/trudne':>27} | {'STACK own: rec/FPR/trudne':>27}")
    for r in sorted(results, key=lambda r: -r["stack_auc"]["val"]):
        a, b = r["knn_auc"], r["stack_auc"]; so = r["stack_op"]
        fmt = lambda t: f"{t[0]*100:4.0f}/{t[1]*100:4.1f}/{t[2]*100:4.0f}"
        print(f"{r['emb']:7}{r['index']:14}{r['k']:>3} {r['score']:5} | {a['val']:.3f} {a['test']:.3f} {a['own']:.3f} | {b['val']:.3f} {b['test']:.3f} {b['own']:.3f}       | {fmt(so['test']):>27} | {fmt(so['own']):>27}")
    if args.json:
        args.json.write_text(json.dumps({"baseline_thr": float(thr_b), "rows": results}, indent=1, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
