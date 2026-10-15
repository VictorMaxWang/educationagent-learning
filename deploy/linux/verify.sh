#!/usr/bin/env bash
# Read-only verification. No passwords, EnvironmentFile contents, or full logs are printed.
set -Eeuo pipefail
systemctl is-active mysql.service redis-server.service educationagent.service caddy.service
echo 'Listening TCP ports (database/cache/application must be loopback only):'
ss -lntp | awk 'NR == 1 || $4 ~ /:(22|80|443|8888|3306|33060|6379|8001)$/ { print }'
echo 'Local classroom health:'
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8888/api/classroom/health
printf '\n'
echo 'Unauthenticated HTTPS status (expected 401, verified public certificate):'
status=$(curl --silent --show-error --max-time 20 --output /dev/null --write-out '%{http_code}' https://smartchildcareagent.cn/)
printf '%s\n' "${status}"
[[ ${status} == 401 ]] || { echo 'Unexpected public authentication status.' >&2; exit 1; }
