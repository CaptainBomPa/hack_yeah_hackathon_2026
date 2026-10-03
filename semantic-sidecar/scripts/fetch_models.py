"""Pobiera modele z config/models.yaml do semantic-sidecar/models/ (katalog w .gitignore).

    python -m scripts.fetch_models --dry-run     # tylko plan i rozmiary z API, nic nie pobiera
    python -m scripts.fetch_models               # pobiera, wpisuje piny rewizji do config/models.yaml
    python -m scripts.fetch_models --only injection_classifier

Powtarzalność: rewizja (hash commita) jest pinowana w config/models.yaml, a po pobraniu powstaje models/MANIFEST.json
z rozmiarami i sumami SHA-256 plików. Skrypt przerywa, jeśli rzeczywisty rozmiar odbiega mocno od `expected_mb`.
"""

import argparse
import hashlib
import re
import json
from fnmatch import fnmatch
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CFG = ROOT / "config" / "models.yaml"
OUT = ROOT / "models"


def _matches(path: str, patterns: list[str]) -> bool:
    return any(fnmatch(path, p) for p in patterns)


def plan(api, repo: str, revision: str | None, files: list[str], ignore: list[str]):
    info = api.model_info(repo, revision=revision, files_metadata=True)
    chosen = [s for s in info.siblings if _matches(s.rfilename, files) and not _matches(s.rfilename, ignore)]
    return info.sha, chosen


def present(name: str, manifest: dict, out_dir: Path) -> bool:
    """Czy model jest już na dysku według manifestu (każdy plik istnieje i ma zapisany rozmiar)? Bez sieci."""
    entry = manifest.get(name)
    if not entry or not entry.get("files"):
        return False
    return all(
        (out_dir / name / rel).is_file() and (out_dir / name / rel).stat().st_size == meta["bytes"]
        for rel, meta in entry["files"].items()
    )


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--if-missing", action="store_true", help="pomiń modele, które już są na dysku (według manifestu). Działa bez sieci")
    args = ap.parse_args()

    from huggingface_hub import HfApi, snapshot_download

    api = HfApi()
    cfg = yaml.safe_load(CFG.read_text(encoding="utf-8"))
    names = args.only or list(cfg["models"])
    manifest: dict = {}
    existing = json.loads((OUT / "MANIFEST.json").read_text(encoding="utf-8")) if (OUT / "MANIFEST.json").exists() else {}
    total = 0.0
    for name in names:
        m = cfg["models"][name]
        if args.if_missing and present(name, existing, OUT):
            print(f"{name:22} już na dysku, pomijam (rewizja {existing[name]['revision'][:10]})")
            continue
        sha, chosen = plan(api, m["repo"], m.get("revision"), m["files"], m.get("ignore", []))
        mb = sum((s.size or 0) for s in chosen) / 1e6
        total += mb
        print(f"{name:22} {m['repo']}\n   rewizja {sha[:10]}  plików {len(chosen)}  rozmiar {mb:.0f} MB (oczekiwano ok. {m['expected_mb']} MB)  licencja {m['license']}")
        for s in chosen:
            if (s.size or 0) > 5e6:
                print(f"      duży plik: {s.rfilename} {s.size/1e6:.0f} MB")
        if mb > m["expected_mb"] * 1.3:
            print(f"   BŁĄD: rozmiar {mb:.0f} MB znacznie przekracza oczekiwany. Przerywam.")
            return 1
        if args.dry_run:
            continue
        target = OUT / name
        snapshot_download(m["repo"], revision=sha, local_dir=target, allow_patterns=m["files"], ignore_patterns=m.get("ignore", []))
        files = {}
        for s in chosen:
            p = target / s.rfilename
            files[s.rfilename] = {"bytes": p.stat().st_size, "sha256": sha256(p)}
        manifest[name] = {"repo": m["repo"], "revision": sha, "license": m["license"], "files": files}
        m["revision"] = sha
    print(f"\nRazem do pobrania: {total:.0f} MB")
    if not args.dry_run and manifest:
        OUT.mkdir(exist_ok=True)
        path = OUT / "MANIFEST.json"
        merged = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        merged.update(manifest)  # nie gub wpisów modeli pobranych wcześniej
        path.write_text(json.dumps(merged, indent=2), encoding="utf-8")
        text = CFG.read_text(encoding="utf-8")
        for name in names:  # wpisz piny, zachowując komentarze w pliku
            m = cfg["models"][name]
            block_start = text.index(f"  {name}:")
            nxt = re.search(r"\n  \w+:\n", text[block_start + 1:])
            end = block_start + 1 + nxt.start() if nxt else len(text)
            rev_pos = text.find("revision: null", block_start, end)  # tylko w bloku tego modelu
            if rev_pos != -1:
                text = text[:rev_pos] + f"revision: {m['revision']}" + text[rev_pos + len("revision: null"):]
        if text != CFG.read_text(encoding="utf-8"):  # config bywa zamontowany tylko do odczytu (docker-compose)
            CFG.write_text(text, encoding="utf-8")
        print(f"Zapisano {OUT/'MANIFEST.json'} (piny w {CFG.name} zaktualizowane, jeśli były puste)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
