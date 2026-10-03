#!/usr/bin/env bash
# Przykładowe requesty curl do POST /v1/chat/completions (Basic auth).
# Użycie (Git Bash / WSL):  CL_LOGIN=admin CL_PASSWORD=haslo ./chat-completions.sh [ok|pii|bad-model|no-auth]
# W WSL (NAT) "localhost" to Linux, a backend chodzi na Windowsie - bierzemy IP hosta z resolv.conf.
DEFAULT_HOST="localhost"
if grep -qi microsoft /proc/version 2>/dev/null && ! curl -s -m 1 -o /dev/null "http://localhost:8000/actuator/health"; then
  DEFAULT_HOST="$(awk '/nameserver/ {print $2; exit}' /etc/resolv.conf)"
fi
HOST="${CL_HOST:-http://$DEFAULT_HOST:8000}"
LOGIN="${CL_LOGIN:-agent-sdk}"
PASSWORD="${CL_PASSWORD:-agent-sdk}"
URL="$HOST/v1/chat/completions"

case "${1:-ok}" in
  ok)
    curl -i -u "$LOGIN:$PASSWORD" -H "Content-Type: application/json" "$URL" -d '{
      "model": "qwen2.5:1.5b-instruct-q4_K_M",
      "messages": [{ "role": "user", "content": "Cze\u015b\u0107, napisz jedno zdanie o Krakowie." }]
    }'
    ;;
  pii)
    curl -i -u "$LOGIN:$PASSWORD" -H "Content-Type: application/json" "$URL" -d '{
      "model": "qwen2.5:1.5b-instruct-q4_K_M",
      "messages": [{ "role": "user", "content": "M\u00f3j PESEL to 44051401359, a email jan.kowalski@example.com. Powt\u00f3rz je." }]
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
