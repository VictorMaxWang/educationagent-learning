#!/usr/bin/env python3
"""容器内的唯一入口：编译与执行分阶段限时，统一四种语言的返回契约。"""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
JOB = Path(os.environ.get("CODE_JOB_DIR", "/job"))
OUTPUT_LIMIT = 65536


class Cancelled(BaseException):
    pass


def handle_cancel(_signal, _frame):
    raise Cancelled()


def read_checkpoint(status, reason):
    path = JOB / "checkpoint.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = result()
    data.update(status=status, reason=reason, truncated=True)
    return data


def result(status="error", stdout="", stderr="", diagnostics=None, trace=None, truncated=False, reason=None):
    data = dict(status=status, stdout=stdout[:OUTPUT_LIMIT], stderr=stderr[:OUTPUT_LIMIT],
                diagnostics=diagnostics or [], trace=trace or [], truncated=truncated)
    if reason:
        data["reason"] = reason
    return data


def clamp_output(data):
    # 各语言最后一道统一字节边界，避免多字节中文或 stdout/stderr 合并后越限。
    out = str(data.get("stdout", "")).encode("utf-8", "replace")
    err = str(data.get("stderr", "")).encode("utf-8", "replace")
    data["stdout"] = out[:OUTPUT_LIMIT].decode("utf-8", "ignore")
    left = OUTPUT_LIMIT - len(data["stdout"].encode("utf-8"))
    data["stderr"] = err[:left].decode("utf-8", "ignore")
    if len(out) + len(err) > OUTPUT_LIMIT:
        data["truncated"] = True
        if data.get("status") == "completed":
            data.update(status="error", reason="output_limit")
    return data


def run_command(command, timeout, *, input_text=None):
    # 独立进程组方便宿主清理；容器退出时剩余子进程也会由 Docker 一并回收。
    try:
        proc = subprocess.run(command, input=input_text, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout, cwd=JOB)
        return proc.returncode, proc.stdout[-OUTPUT_LIMIT:], proc.stderr[-OUTPUT_LIMIT:]
    except subprocess.TimeoutExpired as exc:
        return 124, (exc.stdout or b"").decode("utf-8", "replace")[-OUTPUT_LIMIT:], (exc.stderr or b"").decode("utf-8", "replace")[-OUTPUT_LIMIT:]


def main():
    JOB.mkdir(parents=True, exist_ok=True)
    request = json.load(sys.stdin)
    language, code, stdin = request.get("language"), request.get("code", ""), request.get("stdin", "")
    if language not in ("javascript", "python", "cpp", "java") or not isinstance(code, str) or not isinstance(stdin, str):
        return result(reason="invalid_request", stderr="不支持的语言或输入格式。")
    if not code.strip() or len(code) > 20000 or len(stdin) > 4000:
        return result(reason="input_limit", stderr="代码须为 1—20000 字符，标准输入最多 4000 字符。")
    request["limits"] = {"steps": 1000, "outputBytes": OUTPUT_LIMIT, "traceBytes": 4194304, "seconds": 15}
    (JOB / "request.json").write_text(json.dumps(request, ensure_ascii=False), encoding="utf-8")
    (JOB / "stdin.txt").write_text(stdin, encoding="utf-8")
    if language == "javascript":
        (JOB / "main.cjs").write_text(code, encoding="utf-8")
        command = ["node", str(ROOT / "drivers/javascript.mjs"), str(JOB)]
    elif language == "python":
        (JOB / "main.py").write_text(code, encoding="utf-8")
        command = [sys.executable, "-I", str(ROOT / "drivers/python_trace.py"), str(JOB)]
    elif language == "cpp":
        (JOB / "main.cpp").write_text(code, encoding="utf-8")
        exitcode, out, err = run_command(["g++", "-std=c++17", "-g", "-O0", "-fno-omit-frame-pointer", "-o", str(JOB / "program"), str(JOB / "main.cpp")], 20)
        if exitcode:
            diagnostics = [{"line": int(m.group(1)), "message": m.group(2)} for m in re.finditer(r"main\.cpp:(\d+):\d+:\s*(?:fatal )?error:\s*([^\n]+)", err)]
            return result("timeout" if exitcode == 124 else "error", out, err, diagnostics, reason="compile_timeout" if exitcode == 124 else "compile_error")
        command = [sys.executable, "-I", str(ROOT / "drivers/cpp_trace.py"), str(JOB)]
    else:
        # 单文件允许自定义 public class 名，类名只用于文件/主类选择，不能进入 shell。
        class_match = re.search(r"\bpublic\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)", code)
        class_name = class_match.group(1) if class_match else "Main"
        source = JOB / (class_name + ".java")
        source.write_text(code, encoding="utf-8")
        (JOB / "classes").mkdir(exist_ok=True)
        exitcode, out, err = run_command(["javac", "-J-Xmx192m", "-g", "-encoding", "UTF-8", "-d", str(JOB / "classes"), str(source)], 20)
        if exitcode:
            diagnostics = [{"line": int(m.group(1)), "message": m.group(2)} for m in re.finditer(r"\.java:(\d+):\s*(?:error:)?\s*([^\n]+)", err)]
            return result("timeout" if exitcode == 124 else "error", out, err, diagnostics, reason="compile_timeout" if exitcode == 124 else "compile_error")
        driver_classes = os.environ.get("CODE_JAVA_DRIVER_DIR", str(ROOT / "drivers/java"))
        command = ["java", "-Xmx192m", "-XX:ActiveProcessorCount=1", "--add-modules", "jdk.jdi", "-cp", driver_classes, "TraceMain", str(JOB), class_name]
    exitcode, out, err = run_command(command, 18)
    output = JOB / "result.json"
    if output.exists():
        data = json.loads(output.read_text(encoding="utf-8"))
        if output.stat().st_size > 4500000:
            return result(reason="trace_limit", stderr="执行轨迹超过限制。", truncated=True)
        return data
    if exitcode == 124:
        return read_checkpoint("timeout", "execution_timeout")
    return result("timeout" if exitcode == 124 else "error", out, err, reason="execution_timeout" if exitcode == 124 else "driver_failed", truncated=exitcode == 124)


if __name__ == "__main__":
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, handle_cancel)
    try:
        response = main()
    except Cancelled:
        # 容器 stop 先向 PID 1 发 SIGTERM；父进程在子调试器清理后交回最后快照。
        response = read_checkpoint("cancelled", "cancelled")
    except BaseException as exc:
        response = result(stderr=str(exc)[:2000], reason="runner_error")
    print(json.dumps(clamp_output(response), ensure_ascii=False, separators=(",", ":")))
