#!/usr/bin/env bash
# Przykładowe requesty curl do POST /v1/chat/completions (Basic auth).
# Użycie (Git Bash / WSL):  CL_LOGIN=admin CL_PASSWORD=haslo ./chat-completions.sh [ok|pii|bad-model|no-auth]
HOST="${CL_HOST:-http://localhost:8000}"
LOGIN="${CL_LOGIN:-admin}"
PASSWORD="${CL_PASSWORD:-zmien-mnie}"
URL="$HOST/v1/chat/completions"

case "${1:-ok}" in
  ok)
    curl -i -u "$LOGIN:$PASSWORD" -H "Content-Type: application/json" "$URL" -d '{
      "model": "qwen2.5:1.5b-instruct-q4_K_M",
      "messages": [{ "role": "user", "content": "Cześć, napisz jedno zdanie o Krakowie." }]
    }'
    ;;
  pii)
    curl -i -u "$LOGIN:$PASSWORD" -H "Content-Type: application/json" "$URL" -d '{
      "model": "qwen2.5:1.5b-instruct-q4_K_M",
      "messages": [{ "role": "user", "content": "Mój PESEL to 44051401359, a email jan.kowalski@example.com. Powtórz je." }]
    }'
    ;;
  bad-model)
    curl -i -u "$LOGIN:$PASSWORD" -H "Content-Type: application/json" "$URL" -d '{
      "model": "gpt-unknown",
      "messages": [{ "role": "user", "content": "test" }]
    }'
    ;;
  no-auth)
    curl -i -H "Content-Type: application/json" "$URL" -d '{
      "model": "qwen2.5:0.5b",
      "messages": [{ "role": "user", "content": "test" }]
    }'
    ;;
  *)
    echo "Użycie: $0 [ok|pii|bad-model|no-auth]" >&2
    exit 1
    ;;
esac
echo
