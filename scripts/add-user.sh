#!/usr/bin/env bash
# Dodaje konto lokalne gatewaya z rolą. Hasło pytane interaktywnie (nie w argumentach).
#
# Użycie (z katalogu głównego repo, po `./gradlew bootJar` w backend/):
#   scripts/add-user.sh <login> <rola>        rola: chat | admin | agent (patrz backend/config/policy.yaml)
#
# Baza: profil domyślnie `prod` (PostgreSQL z docker-compose). Zmienne SPRING_DATASOURCE_* jak w backendzie.
# Profil `local` (H2 in-memory) nie zachowuje kont po zakończeniu, więc nie nadaje się do tego skryptu.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "użycie: $0 <login> <rola: chat|admin|agent>" >&2
  exit 2
fi
login="$1"
role="$2"

root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$(ls "$root"/backend/build/libs/*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -n 1 || true)"
if [[ -z "$jar" ]]; then
  echo "brak jara: uruchom najpierw ./gradlew bootJar w backend/" >&2
  exit 1
fi

read -rsp "Hasło dla $login (min. 8 znaków): " password
echo

# Polityka jest ładowana z katalogu backendu (CONTROL_LAYER_POLICY_FILE, domyślnie config/policy.yaml),
# więc uruchamiamy z backend/, żeby rola została sprawdzona względem tej samej polityki co w gatewayu.
cd "$root/backend"
CL_NEW_PASSWORD="$password" SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}" \
  java -jar "$jar" \
  --server.port=0 \
  --control-layer.cli.add-user="$login" \
  --control-layer.cli.role="$role"
