#!/usr/bin/env bash
set -euo pipefail
# 专用安装入口：调用者先完成网站备份；不修改原服务或其环境文件。
SOURCE="${1:?用法: code-runner-install.sh 完整code-runner目录}"
ROOT="/opt/educationagent-code-runner"
EXPECTED_DOCKER_HOST="unix:///run/xiaotao-code/docker.sock"
if test -n "${DOCKER_HOST:-}" && test "$DOCKER_HOST" != "$EXPECTED_DOCKER_HOST"; then
  printf '%s\n' '拒绝操作其他 Docker daemon；此入口只用于项目专用 socket。' >&2
  exit 2
fi
export DOCKER_HOST="$EXPECTED_DOCKER_HOST"
test "$(id -u)" = "0"
test -f "$SOURCE/broker.py"
test -f "$SOURCE/Dockerfile"
command -v docker >/dev/null
docker info >/dev/null
if ! id educationagent-code >/dev/null 2>&1; then
  useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin educationagent-code
fi
install -d -m 0755 "$ROOT"
install -m 0644 "$SOURCE/broker.py" "$ROOT/broker.py"
# 专用 daemon 不创建 bridge，构建期安装固定依赖使用宿主已有网络；学生容器始终 --network=none。
docker build --pull=false --network=host --tag xiaotao-code-runner:1 "$SOURCE"
install -d -m 0750 -o root -g educationagent-code /etc/xiaotao-code
if ! test -f /etc/xiaotao-code/runner.env; then
  # 私有 token 仅进入两个受保护环境文件，永不输出终端或镜像。
  umask 0077
  printf 'CODE_RUNNER_TOKEN=%s\nCODE_RUNNER_IMAGE=xiaotao-code-runner:1\nCODE_RUNNER_PORT=8899\nDOCKER_HOST=unix:///run/xiaotao-code/docker.sock\n' "$(python3 -c 'import secrets; print(secrets.token_urlsafe(40))')" > /etc/xiaotao-code/runner.env
fi
chown root:educationagent-code /etc/xiaotao-code/runner.env
chmod 0640 /etc/xiaotao-code/runner.env
printf '%s\n' '镜像和代理已安装。请由发布流程安装 code-runner.service，并将同一 token 安全写入网站的 CODE_RUNNER_TOKEN 配置后启用服务。'
