#!/usr/bin/env python3
"""仅监听回环的执行代理。业务层持有队列/所有权，本代理每次仅启动一个任务容器。"""
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

TOKEN = os.environ.get("CODE_RUNNER_TOKEN", "")
IMAGE = os.environ.get("CODE_RUNNER_IMAGE", "xiaotao-code-runner:1")
PORT = int(os.environ.get("CODE_RUNNER_PORT", "8899"))
LOCK = threading.Lock()
STATE_LOCK = threading.Lock()
ACTIVE = None
REQUEST_LIMIT = 131072
RESPONSE_LIMIT = 4600000


def error(reason, message, *, status="error"):
    return {"status": status, "stdout": "", "stderr": message, "diagnostics": [], "trace": [], "truncated": status == "timeout", "reason": reason}


def docker_cleanup(name):
    # 名称由本代理生成，绝不按通用进程名或用户传入 id 清理其他容器。
    try:
        subprocess.run(["docker", "rm", "--force", name], capture_output=True, timeout=8)
    except (OSError, subprocess.TimeoutExpired):
        pass


def docker_stop(name):
    # run/create 与取消可能交错。仅检查这个任务的容器，出现后只发一次温和停止。
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        try:
            inspected = subprocess.run(["docker", "inspect", "--format={{.State.Running}}", name], capture_output=True, timeout=2)
            if inspected.returncode == 0 and inspected.stdout.strip() == b"true":
                subprocess.run(["docker", "stop", "--signal=SIGTERM", "--time=3", name], capture_output=True, timeout=5)
                return
        except (OSError, subprocess.TimeoutExpired):
            return
        time.sleep(0.1)


def communicate_bounded(process, payload, timeout, stop):
    """容器 stdout 也不可信：接收时就限额，不能等 communicate 全量缓冲后再检查。"""
    overflow = threading.Event()
    buffers = [bytearray(), bytearray()]

    def reader(stream, target, limit):
        try:
            while True:
                chunk = stream.read(65536)
                if not chunk:
                    break
                available = max(0, limit - len(target))
                target.extend(chunk[:available])
                if len(chunk) > available:
                    overflow.set()
        except (OSError, ValueError):
            pass

    def writer():
        try:
            process.stdin.write(payload)
            process.stdin.close()
        except (BrokenPipeError, OSError):
            pass

    readers = [threading.Thread(target=reader, args=(process.stdout, buffers[0], RESPONSE_LIMIT), daemon=True),
               threading.Thread(target=reader, args=(process.stderr, buffers[1], 65536), daemon=True)]
    for thread in readers:
        thread.start()
    threading.Thread(target=writer, daemon=True).start()
    deadline, reason = time.monotonic() + timeout, None
    while process.poll() is None:
        if overflow.is_set():
            reason = "response_limit"
            break
        if time.monotonic() >= deadline:
            reason = "container_timeout"
            break
        overflow.wait(0.03)
    if reason:
        stop()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
    for thread in readers:
        thread.join(timeout=1)
    for stream in (process.stdout, process.stderr):
        stream.close()
    if overflow.is_set():
        reason = "response_limit"
    return bytes(buffers[0]), bytes(buffers[1]), reason


def execute(request, task):
    name = task["container"]
    command = ["docker", "run", "--rm", "--interactive", "--pull=never", "--name", name,
               "--network=none", "--read-only", "--user=65532:65532", "--cap-drop=ALL",
               "--security-opt=no-new-privileges", "--cpus=1", "--memory=768m", "--memory-swap=768m", "--pids-limit=128",
               "--ulimit=nofile=128:128", "--ulimit=nproc=128:128", "--ulimit=fsize=8388608:8388608",
               "--tmpfs=/job:rw,exec,nosuid,nodev,size=192m,mode=1777", "--tmpfs=/tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777",
               "--env=PYTHONDONTWRITEBYTECODE=1", "--env=PYTHONUNBUFFERED=1", "--env=HOME=/tmp", "--env=LC_ALL=C.UTF-8", IMAGE]
    try:
        if task["cancel"].is_set():
            return error("cancelled", "任务已停止。", status="cancelled")
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        task["process"] = process
        if task["cancel"].is_set():
            docker_stop(name)
        out, err, transport_reason = communicate_bounded(process, json.dumps(request, ensure_ascii=False).encode("utf-8"), 43, lambda: docker_stop(name))
        if transport_reason == "response_limit":
            return error("response_limit", "执行结果超过大小限制。")
        if transport_reason == "container_timeout":
            try:
                data = json.loads(out)
                data.update(status="timeout", reason="container_timeout", truncated=True)
                return data
            except ValueError:
                return error("container_timeout", "编译或执行超过总时限。", status="timeout")
        if len(out) > RESPONSE_LIMIT:
            return error("response_limit", "执行结果超过大小限制。")
        if process.returncode:
            if task["cancel"].is_set():
                return error("cancelled", "任务已停止。", status="cancelled")
            # Docker 诊断中没有业务环境；控制长度，不回显代理配置和命令行。
            return error("container_error", err.decode("utf-8", "replace")[-2000:] or "执行容器启动失败。")
        data = json.loads(out)
        if data.get("status") not in ("completed", "error", "timeout", "cancelled") or not isinstance(data.get("trace"), list):
            return error("invalid_runner_response", "执行器返回格式不正确。")
        if task["cancel"].is_set():
            data.update(status="cancelled", reason="cancelled", truncated=True)
        return data
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        return error("broker_error", str(exc)[:1000])
    finally:
        docker_cleanup(name)


class Handler(BaseHTTPRequestHandler):
    server_version = "XiaotaoCodeRunner/1"

    def log_message(self, format, *args):
        # 不记录源码、标准输入、请求头和鉴权 token。
        pass

    def respond(self, code, body):
        raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def authorized(self):
        header = self.headers.get("Authorization", "")
        if not TOKEN or not hmac.compare_digest(header.encode("utf-8"), ("Bearer " + TOKEN).encode("utf-8")):
            self.respond(401, error("unauthorized", "执行器鉴权失败。"))
            return False
        return True

    def do_GET(self):
        if self.path != "/health":
            return self.respond(404, error("not_found", "接口不存在。"))
        if not self.authorized():
            return
        with STATE_LOCK:
            busy = ACTIVE is not None
        self.respond(200, {"status": "ok", "busy": busy, "languages": ["javascript", "python", "cpp", "java"], "schemaVersion": 1})

    def do_DELETE(self):
        if not self.authorized():
            return
        match = re.fullmatch(r"/jobs/([A-Za-z0-9_-]{1,80})", self.path)
        if not match:
            return self.respond(404, error("not_found", "任务不存在。"))
        with STATE_LOCK:
            task = ACTIVE if ACTIVE and ACTIVE["id"] == match.group(1) else None
            if task:
                task["cancel"].set()
        if task:
            docker_stop(task["container"])
            return self.respond(200, {"status": "cancelled", "id": task["id"]})
        self.respond(404, error("not_found", "任务已结束或不存在。"))

    def do_POST(self):
        global ACTIVE
        if self.path != "/run":
            return self.respond(404, error("not_found", "接口不存在。"))
        if not self.authorized():
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= REQUEST_LIMIT:
                return self.respond(413, error("input_limit", "请求超过大小限制。"))
            request = json.loads(self.rfile.read(length))
            if not isinstance(request, dict) or not isinstance(request.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", request["id"]):
                return self.respond(400, error("invalid_request", "任务 id 格式不正确。"))
            if request.get("language") not in ("javascript", "python", "cpp", "java"):
                return self.respond(400, error("invalid_language", "不支持该语言。"))
            code, stdin = request.get("code"), request.get("stdin", "")
            if not isinstance(code, str) or not code.strip() or len(code) > 20000 or not isinstance(stdin, str) or len(stdin) > 4000:
                return self.respond(400, error("input_limit", "代码最多 20000 字符，标准输入最多 4000 字符。"))
        except (ValueError, UnicodeDecodeError):
            return self.respond(400, error("invalid_json", "请求 JSON 不正确。"))
        if not LOCK.acquire(blocking=False):
            return self.respond(409, error("busy", "执行器忙，请等待当前任务结束。"))
        task = {"id": request["id"], "container": "xiaotao-code-" + uuid.uuid4().hex, "cancel": threading.Event(), "process": None}
        try:
            with STATE_LOCK:
                ACTIVE = task
            # 白名单透传字段，不把业务层附加信息/环境带进学生容器。
            response = execute({"id": request["id"], "language": request["language"], "code": code, "stdin": stdin}, task)
            self.respond(200, response)
        finally:
            with STATE_LOCK:
                ACTIVE = None
            LOCK.release()


if __name__ == "__main__":
    if len(TOKEN) < 24:
        raise SystemExit("CODE_RUNNER_TOKEN 至少需要 24 字符，拒绝无鉴权启动。")
    if os.environ.get("DOCKER_HOST") != "unix:///run/xiaotao-code/docker.sock":
        raise SystemExit("执行代理只允许连接项目专用 Docker socket。")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever(poll_interval=0.5)
