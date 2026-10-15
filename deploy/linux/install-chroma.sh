#!/usr/bin/env bash
# 可选向量库：只使用本项目专用 Python 3.12 venv，不修改系统 Python 或现有 Chroma 服务。
set -Eeuo pipefail
[[ ${EUID} -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
runtime=/opt/educationagent/runtime/chroma
data_dir=/opt/educationagent/shared/data/chroma
unit=educationagent-chroma.service
unit_target="/etc/systemd/system/${unit}"
marker='educationagent chromadb 1.0.20 Python 3.12'
[[ -x /usr/bin/python3.12 ]] || { echo 'Python 3.12 is required.' >&2; exit 1; }
[[ $(/usr/bin/python3.12 -c 'import sys; print("%d.%d" % sys.version_info[:2])') == 3.12 ]] || exit 1
id educationagent >/dev/null 2>&1 || { echo 'Run bootstrap-host.sh first.' >&2; exit 1; }
[[ -d /opt/educationagent/runtime && -d /opt/educationagent/shared/data ]] || { echo 'Project directories are missing.' >&2; exit 1; }

# 同名服务、目录或端口已有其他所有者时直接停止，不能借安装向量库接管其他组件。
existing_fragment=$(systemctl show "${unit}" --property=FragmentPath --value 2>/dev/null || true)
if [[ -n ${existing_fragment} ]]; then
  [[ ${existing_fragment} == "${unit_target}" && -f ${unit_target} && ! -L ${unit_target} ]] || { echo 'An unrelated service already owns this unit name.' >&2; exit 1; }
  cmp --silent "${script_dir}/${unit}" "${unit_target}" || { echo 'Existing Chroma unit differs; review it before changing it.' >&2; exit 1; }
elif [[ -e ${unit_target} || -L ${unit_target} ]]; then
  echo 'Unregistered unit file exists; inspect it first.' >&2
  exit 1
fi
if [[ -e ${runtime} || -L ${runtime} ]]; then
  [[ -d ${runtime} && ! -L ${runtime} && -f ${runtime}/.educationagent-chroma ]] || { echo 'Existing runtime is not owned by this installer.' >&2; exit 1; }
  [[ $(cat "${runtime}/.educationagent-chroma") == "${marker}" ]] || { echo 'Existing runtime marker differs; review it first.' >&2; exit 1; }
fi
listeners=$(ss -H -lntp '( sport = :8001 )')
if [[ -n ${listeners} ]]; then
  main_pid=$(systemctl show "${unit}" --property=MainPID --value 2>/dev/null || true)
  [[ -n ${existing_fragment} && ${main_pid} =~ ^[1-9][0-9]*$ && ${listeners} == *"pid=${main_pid},"* ]] || { echo 'Port 8001 belongs to another process; no changes made.' >&2; exit 1; }
fi
if [[ -e ${data_dir} || -L ${data_dir} ]]; then
  [[ -d ${data_dir} && ! -L ${data_dir} && $(stat -c '%U' "${data_dir}") == educationagent ]] || { echo 'Existing data directory has unexpected ownership.' >&2; exit 1; }
else
  install -d -o educationagent -g educationagent -m 0750 "${data_dir}"
fi

if [[ ! -e ${runtime} ]]; then
  /usr/bin/python3.12 -m venv "${runtime}"
  printf '%s\n' "${marker}" >"${runtime}/.educationagent-chroma"
  chmod 0644 "${runtime}/.educationagent-chroma"
fi
installed=$(${runtime}/bin/python -c 'from importlib.metadata import version, PackageNotFoundError
try: print(version("chromadb"))
except PackageNotFoundError: print("")')
if [[ -n ${installed} && ${installed} != 1.0.20 ]]; then
  echo 'Existing Chroma version differs; refusing an implicit upgrade/downgrade.' >&2
  exit 1
fi
if [[ -z ${installed} ]]; then
  # 安装 Python 包不触发模型推理；只要求 Chroma 本身使用官方可用 wheel。
  ANONYMIZED_TELEMETRY=False "${runtime}/bin/python" -m pip install --disable-pip-version-check --no-cache-dir --only-binary=chromadb 'chromadb==1.0.20'
fi
"${runtime}/bin/python" -c 'from importlib.metadata import version; assert version("chromadb") == "1.0.20"'
cli_help=$(ANONYMIZED_TELEMETRY=False "${runtime}/bin/chroma" run --help)
for flag in --host --port --path; do
  [[ ${cli_help} == *"${flag}"* ]] || { echo 'Installed CLI does not match the reviewed interface.' >&2; exit 1; }
done
install -o root -g root -m 0644 "${script_dir}/${unit}" "${unit_target}"
systemctl daemon-reload
systemctl enable --now "${unit}"

# 唯一业务探测是只读 v2 heartbeat，不创建集合、不写测试向量、不调用默认嵌入函数。
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8001/api/v2/heartbeat |
  /usr/bin/python3.12 -c 'import json,sys; value=json.load(sys.stdin); assert isinstance(value,dict) and isinstance(value.get("nanosecond heartbeat"),int); print("Chroma v2 heartbeat verified")'
systemctl is-active "${unit}"
echo 'Inspect the unit journal for BPF/IP firewall support before enabling Java vector integration.'
echo 'Application model, memory flags, and runtime.env have not been changed.'
