"""Testy statyczne konfiguracji Dockera. Nie budują obrazu (to wymaga demona), ale pilnują spójności plików,
która łatwo się psuje: zależności bez przypiętej wersji, rozjazd portów, brak wolumenu."""

import re
import tomllib
from pathlib import Path

import yaml

from scripts.fetch_models import present

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ROOT.parent / "docker-compose.yml"
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")


def _canon(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _names(lines) -> set[str]:
    out = set()
    for line in lines:
        line = line.split("#")[0].strip()
        if line:
            out.add(_canon(re.split(r"[<>=!~\[ ;]", line, maxsplit=1)[0]))
    return out


def test_every_runtime_dependency_is_pinned_in_constraints():
    p = tomllib.loads((ROOT / "pyproject.toml").read_text())["project"]
    runtime = _names(p["dependencies"] + p["optional-dependencies"]["serve"]) | {"torch"}
    pinned = _names((ROOT / "constraints.txt").read_text().splitlines())
    assert runtime <= pinned, f"zależności bez przypiętej wersji w constraints.txt: {sorted(runtime - pinned)}"
    for line in (ROOT / "constraints.txt").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            assert "==" in line, f"constraints.txt ma mieć dokładne wersje: {line}"


def test_dockerfile_uses_cpu_torch_index_and_copies_only_what_it_needs():
    assert "download.pytorch.org/whl/cpu" in DOCKERFILE, "bez indeksu CPU obraz ciągnie ok. 2,5 GB bibliotek CUDA"
    copies = re.findall(r"^COPY (?!--from)(\S+)", DOCKERFILE, flags=re.M)
    assert set(copies) >= {"app", "scripts", "config", "constraints.txt"}
    assert not {"models", ".venv", "evaluation", "tests"} & set(copies)


def test_dockerignore_keeps_heavy_directories_out_of_the_build_context():
    ignore = (ROOT / ".dockerignore").read_text().split()
    assert {".venv", "models", "evaluation"} <= set(ignore)


def test_dockerfile_runs_as_non_root_on_the_port_the_healthcheck_uses():
    assert re.search(r"^USER app$", DOCKERFILE, flags=re.M)
    port = re.search(r"^EXPOSE (\d+)", DOCKERFILE, flags=re.M).group(1)
    assert f"--port\", \"{port}\"" in DOCKERFILE
    compose = yaml.safe_load(COMPOSE.read_text())
    svc = compose["services"]["semantic-sidecar"]
    assert f"{port}:{port}" in svc["ports"]
    assert f"localhost:{port}/health" in " ".join(svc["healthcheck"]["test"])


def test_compose_runs_model_download_before_the_sidecar_and_shares_the_models_volume():
    compose = yaml.safe_load(COMPOSE.read_text())
    s = compose["services"]
    assert s["semantic-sidecar"]["depends_on"]["semantic-sidecar-init"]["condition"] == "service_completed_successfully"
    assert s["semantic-sidecar-init"]["restart"] == "no"
    assert "--if-missing" in s["semantic-sidecar-init"]["command"], "kolejne starty muszą działać offline"
    for name in ("semantic-sidecar", "semantic-sidecar-init"):
        assert "sidecar_models:/app/models" in s[name]["volumes"]
    assert "sidecar_models" in compose["volumes"]
    assert "./semantic-sidecar/config:/app/config:ro" in s["semantic-sidecar"]["volumes"]


def test_models_requested_by_init_exist_in_models_config_and_are_enabled_in_sidecar_config():
    compose = yaml.safe_load(COMPOSE.read_text())
    cmd = compose["services"]["semantic-sidecar-init"]["command"]
    wanted = cmd[cmd.index("--only") + 1:]
    models = yaml.safe_load((ROOT / "config" / "models.yaml").read_text())["models"]
    detectors = yaml.safe_load((ROOT / "config" / "semantic.models.yaml").read_text())["detectors"]
    def model_dirs(params: dict) -> set[str]:
        dirs = {Path(params["model_dir"]).name} if "model_dir" in params else set()
        for member in params.get("members", []):  # zespół: modele są w `members`
            dirs |= model_dirs(member)
        return dirs

    used = set().union(*(model_dirs(d["params"]) for d in detectors.values() if d.get("enabled", True)))
    assert used, "konfiguracja nie używa żadnego modelu"
    assert set(wanted) <= set(models)
    assert used <= set(wanted), f"sidecar używa modeli, których init nie pobiera: {sorted(used - set(wanted))}"


def test_sidecar_config_is_the_models_profile():
    assert "SEMANTIC_CONFIG=config/semantic.models.yaml" in DOCKERFILE


# --- present(): działanie bez sieci

def _manifest(tmp_path):
    d = tmp_path / "m1"
    d.mkdir()
    (d / "a.bin").write_bytes(b"12345")
    return {"m1": {"files": {"a.bin": {"bytes": 5}}}}


def test_present_true_when_all_files_exist_with_matching_size(tmp_path):
    assert present("m1", _manifest(tmp_path), tmp_path)


def test_present_false_for_missing_unknown_or_truncated_files(tmp_path):
    man = _manifest(tmp_path)
    assert not present("nieznany", man, tmp_path)
    assert not present("m1", {"m1": {"files": {}}}, tmp_path)
    (tmp_path / "m1" / "a.bin").write_bytes(b"123")  # urwany plik po przerwanym pobieraniu
    assert not present("m1", man, tmp_path)
    (tmp_path / "m1" / "a.bin").unlink()
    assert not present("m1", man, tmp_path)
