"""Pobiera publiczne zbiory z Hugging Face i zapisuje je w formacie przypadków (JSONL) do evaluation/data/.

NIE jest uruchamiane automatycznie. Wymaga: pip install -e ".[eval]". Zbiory (licencje sprawdzone w API HF 2026-10-03):

  deepset/prompt-injections                   apache-2.0   ok. 19 MB   (zawiera też teksty niemieckie)
  Lakera/gandalf_ignore_instructions          mit          ok. 0,2 MB  (same ataki)
  TrustAIRLab/in-the-wild-jailbreak-prompts   mit          ok. 18 MB   (konfiguracje 2023_12_25)
  jackhhao/jailbreak-classification           apache-2.0   ok. 46 MB   (CSV, kolumny zweryfikowane przy pobraniu)
  neuralchemy/Prompt-injection-dataset        apache-2.0   ok. 2 MB    (konfiguracja "core", tylko validation+test)

Pominięte celowo: walledai/JailbreakHub (te same dane co in-the-wild, duplikaty), xTRam1/safe-guard-prompt-injection
(brak licencji w metadanych).

    python -m evaluation.fetch_public --dry-run
    python -m evaluation.fetch_public --only deepset Lakera
"""

import argparse
import csv
import json
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"


def _row(source: str, idx: int, text: str, attack: bool, family: str) -> dict:
    from evaluation.tagging import is_noisy

    return {
        "id": f"{source}-{idx:06d}", "checkpoint": "P1", "text": text, "source": source,
        "kind": "attack" if attack else "benign", "family": family if attack else "general",
        "tags": ["noisy"] if is_noisy(text) else ["clean"],
    }


def _require(columns, needed: list[str], name: str) -> None:
    missing = [c for c in needed if c not in columns]
    if missing:
        raise SystemExit(f"{name}: brak oczekiwanych kolumn {missing}. Są: {list(columns)}")


def neuralchemy() -> list[dict]:
    """Tylko validation i test z konfiguracji `core` (oryginalne próbki, bez augmentacji). Train zostaje na douczanie.

    Kategorie `indirect_injection` i `rag_poisoning` (ataki w dokumentach i mailach) trafiają do P2 z zaufaniem "document".
    `edge_case` (niewinne, ale przypominające atak) to trudne negatywy. UWAGA: ok. 29% ataków ma szum (homoglify, leet,
    losowa interpunkcja), a niewinne prawie nigdy (1%), więc zbiór premiuje wykrywanie szumu. Zob. evaluation/README.md.
    """
    from datasets import load_dataset

    rows, i = [], 0
    for split in ("validation", "test"):
        ds = load_dataset("neuralchemy/Prompt-injection-dataset", "core", split=split)
        _require(ds.column_names, ["text", "label", "category"], "neuralchemy")
        for r in ds:
            attack = bool(r["label"])
            row = _row("neuralchemy", i, r["text"], attack, r["category"])
            if attack and r["category"] in ("indirect_injection", "rag_poisoning"):
                row["checkpoint"] = "P2"
                row["context"] = {"source_trust": "document"}
            if not attack:
                row["kind"] = "hard_negative" if r["category"] == "edge_case" else "benign"
                row["family"] = r["category"] if r["category"] != "benign" else "general"
            rows.append(row); i += 1
    return rows


def deepset() -> list[dict]:
    from datasets import load_dataset

    rows, i = [], 0
    for split in ("train", "test"):
        ds = load_dataset("deepset/prompt-injections", split=split)
        _require(ds.column_names, ["text", "label"], "deepset")
        for r in ds:
            rows.append(_row("deepset", i, r["text"], bool(r["label"]), "injection")); i += 1
    return rows


def lakera() -> list[dict]:
    from datasets import load_dataset

    rows, i = [], 0
    for split in ("train", "validation", "test"):
        ds = load_dataset("Lakera/gandalf_ignore_instructions", split=split)
        _require(ds.column_names, ["text"], "lakera")
        for r in ds:
            rows.append(_row("lakera", i, r["text"], True, "override_gandalf")); i += 1
    return rows


def in_the_wild() -> list[dict]:
    from datasets import load_dataset

    rows, i = [], 0
    for config in ("jailbreak_2023_12_25", "regular_2023_12_25"):
        ds = load_dataset("TrustAIRLab/in-the-wild-jailbreak-prompts", config, split="train")
        _require(ds.column_names, ["prompt"], "in-the-wild")
        for r in ds:
            attack = config.startswith("jailbreak") and bool(r.get("jailbreak", True))
            rows.append(_row("inthewild", i, r["prompt"], attack, "jailbreak_in_the_wild")); i += 1
    return rows


def jackhhao() -> list[dict]:
    from huggingface_hub import hf_hub_download

    path = hf_hub_download("jackhhao/jailbreak-classification", "default/jailbreak_dataset_full.csv", repo_type="dataset")
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        _require(reader.fieldnames or [], ["prompt", "type"], "jackhhao")
        for i, r in enumerate(reader):
            rows.append(_row("jackhhao", i, r["prompt"], r["type"].strip().lower() == "jailbreak", "jailbreak_prompt"))
    return rows


SOURCES = {"deepset": deepset, "lakera": lakera, "inthewild": in_the_wild, "jackhhao": jackhhao, "neuralchemy": neuralchemy}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="*", choices=list(SOURCES), help="pobierz tylko wybrane źródła")
    ap.add_argument("--dry-run", action="store_true", help="tylko wypisz plan, nic nie pobieraj")
    args = ap.parse_args()
    names = args.only or list(SOURCES)
    if args.dry_run:
        print("Zostałyby pobrane:", ", ".join(names), "->", DATA)
        return
    DATA.mkdir(exist_ok=True)
    for name in names:
        rows = SOURCES[name]()
        out = DATA / f"{name}.jsonl"
        out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
        attacks = sum(1 for r in rows if r["kind"] == "attack")
        print(f"{name}: {len(rows)} przypadków ({attacks} ataków) -> {out}")


if __name__ == "__main__":
    main()
