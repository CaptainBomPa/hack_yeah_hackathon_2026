#!/usr/bin/env bash
# Stawia cały stack lokalnie (db, ollama, sidecar semantyczny, backend, frontend).
#
#   scripts/demo-up.sh            # buduje i startuje
#   scripts/demo-up.sh --no-build
#
# Wymaga działającego Dockera. Pierwszy start pobiera obrazy i modele (Ollama ok. 1,4 GB, sidecar ok. 750 MB).
# Konta (HTTP Basic) zakłada sam backend przy starcie z backend/config/users.yaml (SeedUsers), więc nic nie trzeba tworzyć.
set -euo pipefail
cd "$(dirname "$0")/.."

build_flag="--build"
[[ "${1:-}" == "--no-build" ]] && build_flag=""

echo ">> start stacku (db, ollama, sidecar, backend, frontend)"
docker compose up -d $build_flag

echo ">> czekam na backend (/actuator/health)"
for _ in $(seq 1 90); do
  if curl -sf localhost:8000/actuator/health >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf localhost:8000/actuator/health >/dev/null || { echo "backend nie wstał, zobacz: docker compose logs backend"; exit 1; }

cat <<TXT

Gotowe.
  Backend:   http://localhost:8000   (HTTP Basic; konta w backend/config/users.yaml)
  Sidecar:   http://localhost:8001/health
  Frontend:  http://localhost:3000
  Ollama:    modele pobiera usługa ollama-init, sprawdź: docker compose exec ollama ollama list
Przykłady zapytań: docs/local-stack.md
TXT
