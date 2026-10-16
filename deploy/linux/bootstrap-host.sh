#!/usr/bin/env bash
# Install the selected host dependencies and a project-private Node runtime.
# This script never changes firewall rules, SSH configuration, or unrelated services.
set -Eeuo pipefail
[[ ${EUID} -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
. /etc/os-release
[[ ${ID} == ubuntu && ${VERSION_ID} == 24.04 ]] || { echo 'Expected Ubuntu 24.04.' >&2; exit 1; }
node_version=${1:-24.19.0}
[[ ${node_version} =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid Node version.' >&2; exit 1; }
case $(uname -m) in x86_64) node_arch=x64 ;; aarch64) node_arch=arm64 ;; *) echo 'Unsupported architecture.' >&2; exit 1 ;; esac

export DEBIAN_FRONTEND=noninteractive
# 已具备的系统依赖不重复安装；本脚本也可用于仅补项目 Node 的阶段。
missing_packages=()
# Headless JRE也需要字体塑形库才能用Graphics2D绘制中文水印；缺库会使SunFontManager初始化失败。
for package in ca-certificates curl xz-utils openssl python3 openjdk-21-jre-headless libharfbuzz0b fontconfig fonts-noto-cjk ffmpeg mysql-server redis-server caddy; do
  if [[ $(dpkg-query -W -f='${Status}' "${package}" 2>/dev/null || true) != 'install ok installed' ]]; then
    missing_packages+=("${package}")
  fi
done
if (( ${#missing_packages[@]} > 0 )); then
  apt-get update
  apt-get install -y --no-install-recommends "${missing_packages[@]}"
fi
if ! id educationagent >/dev/null 2>&1; then
  useradd --system --user-group --home-dir /opt/educationagent --shell /usr/sbin/nologin educationagent
fi
install -d -m 0755 /opt/educationagent /opt/educationagent/releases /opt/educationagent/runtime
install -d -o educationagent -g educationagent -m 0750 /opt/educationagent/shared /opt/educationagent/shared/data /opt/educationagent/shared/logs
install -d -o root -g root -m 0700 /etc/educationagent

node_name="node-v${node_version}-linux-${node_arch}"
node_destination="/opt/educationagent/runtime/${node_name}"
if [[ ! -x ${node_destination}/bin/node ]]; then
  download_dir=$(mktemp -d /opt/educationagent/runtime/.node-download.XXXXXX)
  trap 'rm -rf -- "${download_dir}"' EXIT
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "https://nodejs.org/dist/v${node_version}/${node_name}.tar.xz" -o "${download_dir}/${node_name}.tar.xz"
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "https://nodejs.org/dist/v${node_version}/SHASUMS256.txt" -o "${download_dir}/SHASUMS256.txt"
  checksum_line=$(awk -v artifact="${node_name}.tar.xz" '$2 == artifact { print }' "${download_dir}/SHASUMS256.txt")
  [[ -n ${checksum_line} ]] || { echo 'Official checksum missing.' >&2; exit 1; }
  (cd "${download_dir}" && printf '%s\n' "${checksum_line}" | sha256sum --check --status)
  tar -xJf "${download_dir}/${node_name}.tar.xz" -C /opt/educationagent/runtime --no-same-owner
fi
ln -sfn -- "${node_destination}" /opt/educationagent/runtime/node
/opt/educationagent/runtime/node/bin/node --version
/usr/bin/java -version
echo 'Host dependencies ready. Provision runtime.env, then run configure-local-services.sh.'
