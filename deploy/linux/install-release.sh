#!/usr/bin/env bash
# Promote one prebuilt release. Persistent data stay outside the release directory.
set -Eeuo pipefail
[[ ${EUID} -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
release_id=${1:?Usage: install-release.sh RELEASE_ID}
[[ ${release_id} =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] || { echo 'Invalid release ID.' >&2; exit 1; }
base=/opt/educationagent
release="${base}/releases/${release_id}"
[[ -d ${release} && ! -L ${release} && $(realpath -- "${release}") == "${release}" ]] || { echo 'Release must be a real directory inside releases.' >&2; exit 1; }
for artifact in app.jar desktop-app/dist/index.html desktop-app/dist/educationagent-export/player.js desktop-app/dist/educationagent-export/player.css course-engine/dist/worker.js course-engine/dist/runtime/three.js course-engine/dist/runtime/code-runner.js package.json package-lock.json vendor/educationagent/dsl/dist/index.js vendor/educationagent/generation/dist/index.js; do
  [[ -f ${release}/${artifact} ]] || { echo "Missing release artifact: ${artifact}" >&2; exit 1; }
done
[[ -f /etc/educationagent/runtime.env && -f /etc/systemd/system/educationagent.service ]] || { echo 'Provision host configuration first.' >&2; exit 1; }
node="${base}/runtime/node/bin/node"
npm_cli="${base}/runtime/node/lib/node_modules/npm/bin/npm-cli.js"
# Dependency links must be created on Linux; never upload Windows node_modules.
(cd "${release}" && "${node}" "${npm_cli}" ci --omit=dev --ignore-scripts --no-audit --no-fund)
(cd "${release}" && "${node}" course-engine/dist/worker.js --check)
for persistent in data logs; do
  if [[ -e ${release}/${persistent} && ! -L ${release}/${persistent} ]]; then
    echo "Release contains a real ${persistent} directory; inspect it before activation." >&2
    exit 1
  fi
  ln -sfn -- "${base}/shared/${persistent}" "${release}/${persistent}"
done
# 兼容旧版确定性归档的零mtime；入口必须在每次发布后重新验证。
touch -- "${release}/desktop-app/dist/index.html"
previous=$(readlink -f -- "${base}/current" 2>/dev/null || true)
if [[ -e ${base}/current && ! -L ${base}/current ]]; then
  echo 'current is not a symlink; refusing to replace it.' >&2
  exit 1
fi
ln -s -- "${release}" "${base}/.current-${release_id}-$$"
mv -Tf -- "${base}/.current-${release_id}-$$" "${base}/current"
systemctl restart educationagent.service
ready=false
for attempt in {1..90}; do
  if curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8888/api/classroom/health >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ ${ready} != true ]]; then
  echo 'Application health failed; inspect journalctl -u educationagent. Restoring previous release if available.' >&2
  if [[ -n ${previous} && ${previous} == "${base}/releases/"* && -d ${previous} && ${previous} != "${release}" ]]; then
    ln -s -- "${previous}" "${base}/.rollback-$$"
    mv -Tf -- "${base}/.rollback-$$" "${base}/current"
    systemctl restart educationagent.service
  else
    systemctl stop educationagent.service
  fi
  exit 1
fi
systemctl reload-or-restart caddy.service
echo "Release active: ${release_id}. Verify public HTTPS and classroom operations separately."
