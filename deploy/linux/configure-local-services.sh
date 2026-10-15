#!/usr/bin/env bash
# Reads only the documented credentials and emits no credential values.
set -Eeuo pipefail
[[ ${EUID} -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
python3 "${script_dir}/runtime-config.py" provision
systemctl restart mysql.service
python3 "${script_dir}/runtime-config.py" database
systemctl restart redis-server.service
install -o root -g root -m 0644 "${script_dir}/educationagent.service" /etc/systemd/system/educationagent.service
install -o root -g root -m 0644 "${script_dir}/Caddyfile" /etc/caddy/Caddyfile
systemctl daemon-reload
python3 "${script_dir}/runtime-config.py" validate-caddy
systemctl enable mysql.service redis-server.service educationagent.service caddy.service
# The gateway is started only by install-release.sh once a release has passed local health checks.
echo 'Local services and unit files configured. Activate a verified release next.'
