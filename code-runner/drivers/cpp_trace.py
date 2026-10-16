"""使用 GDB/MI 控制真实进程，嵌入 Python 仅读取局部变量和堆内存。"""
import json
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time

job = Path(sys.argv[1]).resolve()
request = json.loads((job / "request.json").read_text(encoding="utf-8"))
limits = request["limits"]
events = queue.Queue()
trace, diagnostics = [], []
reason, stderr = None, ""
trace_bytes = 0
token = 0
started = time.monotonic()
checkpoint_time = 0


def source_statements(code):
    # 保留字符串/字符字面量，只移除注释；跨行块注释保留换行以保持一基源码行号。
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)
    def strip_comment(match):
        token = match.group(0)
        return "\n" * token.count("\n") if token.startswith(("//", "/*")) else token
    return tokens.sub(strip_comment, code).splitlines()


statements = source_statements((job / "main.cpp").read_text(encoding="utf-8"))


def closing_only(line):
    statement = statements[line - 1].strip() if 0 < line <= len(statements) else ""
    return not statement or re.fullmatch(r'(?:}\s*;?\s*)+', statement) is not None


gdb_binary = "gdb"
process = subprocess.Popen([gdb_binary, "--quiet", "--nx", "--interpreter=mi2", str(job / "program")], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1, cwd=job)


def reader():
    for line in process.stdout:
        events.put(line.strip())
    events.put("<closed>")


threading.Thread(target=reader, daemon=True).start()


def remaining():
    return max(0.01, limits["seconds"] - (time.monotonic() - started))


def command(text):
    global token
    token += 1
    expected = str(token)
    process.stdin.write(expected + text + "\n")
    process.stdin.flush()
    delayed = []
    while True:
        line = events.get(timeout=remaining())
        if line.startswith(expected + "^"):
            for pending in delayed:
                events.put(pending)
            if line.startswith(expected + "^error"):
                raise RuntimeError(line)
            return line
        if line.startswith("*stopped") or line == "<closed>":
            delayed.append(line)


def quote(value):
    return json.dumps(value, ensure_ascii=False)


def read_output(name):
    path = job / name
    if not path.exists():
        return ""
    raw = path.read_bytes()
    if len(raw) > limits["outputBytes"]:
        global reason
        reason = "output_limit"
    return raw[:limits["outputBytes"]].decode("utf-8", "replace")


try:
    command("-gdb-set pagination off")
    command("-gdb-set confirm off")
    # 不要求修改容器进程的 ASLR，避免扩大运行容器权限。
    command("-gdb-set disable-randomization off")
    command("-interpreter-exec console " + quote("source " + str(Path(__file__).with_name("gdb_snapshot.py"))))
    command("-break-insert main")
    command("-interpreter-exec console " + quote("run < " + str(job / "stdin.txt") + " > " + str(job / "program.stdout") + " 2> " + str(job / "program.stderr")))
    while reason is None:
        if time.monotonic() - started >= limits["seconds"]:
            reason = "execution_timeout"
            break
        event = events.get(timeout=remaining())
        if event == "<closed>":
            break
        if not event.startswith("*stopped"):
            continue
        if 'reason="exited-normally"' in event:
            break
        if 'reason="exited"' in event:
            reason = "runtime_error"
            break
        if 'reason="signal-received"' in event:
            reason = "runtime_error"
            command("-interpreter-exec console " + quote("xtrace-snapshot " + str(job / "snapshot.json")))
            crash = json.loads((job / "snapshot.json").read_text(encoding="utf-8"))
            signal_name = re.search(r'signal-name="([^"]+)"', event)
            diagnostics.append({"line": crash.get("line", 0), "message": "程序收到信号 " + (signal_name.group(1) if signal_name else "unknown")})
            break
        top_file = re.search(r'(?:^|[,{}])file="([^"]+)"', event)
        if not top_file or not top_file.group(1).endswith("main.cpp"):
            # 标准库内部仍能看到下层 main 栈，不能把它误算成重复的学生步骤。
            if any(name in event for name in ("::vector", "::~vector", "::basic_string", "::~basic_string")):
                command("-interpreter-exec console " + quote("xtrace-track-lifetime"))
            command("-exec-finish")
            continue
        current_line = re.search(r'(?:^|[,{}])line="(\d+)"', event)
        if current_line and closing_only(int(current_line.group(1))):
            # 纯闭括号常对应编译器的局部对象析构/函数尾声，不再读取其中已失效的内存。
            command("-exec-step")
            continue
        if len(trace) >= limits["steps"]:
            reason = "step_limit"
            break
        command("-interpreter-exec console " + quote("xtrace-snapshot " + str(job / "snapshot.json")))
        snapshot = json.loads((job / "snapshot.json").read_text(encoding="utf-8"))
        lifetime_ended = snapshot.pop("lifetimeEnded", False)
        if not lifetime_ended and snapshot["frames"] and snapshot["line"] > 0:
            snapshot.update(index=len(trace), event="line", stdout=read_output("program.stdout"))
            size = len(json.dumps(snapshot, ensure_ascii=False).encode("utf-8"))
            if trace_bytes + size > limits["traceBytes"]:
                reason = "trace_limit"
                break
            trace_bytes += size
            trace.append(snapshot)
            if len(trace) == 1 or len(trace) % 25 == 0 or time.monotonic() - checkpoint_time > 0.2:
                temporary = job / "checkpoint.tmp"
                temporary.write_text(json.dumps({"status": "completed", "stdout": read_output("program.stdout"), "stderr": read_output("program.stderr"), "diagnostics": [], "trace": trace, "truncated": False}, ensure_ascii=False), encoding="utf-8")
                temporary.replace(job / "checkpoint.json")
                checkpoint_time = time.monotonic()
        if reason:
            break
        # 进入学生函数时保留递归栈；进入标准库则直接返回到调用处。
        command("-exec-step")
except queue.Empty:
    reason = "execution_timeout"
except BaseException as exc:
    reason = reason or "driver_error"
    stderr = str(exc)[:2000]
finally:
    try:
        if process.poll() is None:
            process.stdin.write("-gdb-exit\n")
            process.stdin.flush()
            process.wait(timeout=1)
    except BaseException:
        process.kill()
    stdout = read_output("program.stdout")
    stderr = (read_output("program.stderr") + stderr)[:limits["outputBytes"]]
    response = {"status": "completed" if reason is None else ("timeout" if reason == "execution_timeout" else "error"), "stdout": stdout, "stderr": stderr, "diagnostics": diagnostics, "trace": trace, "truncated": reason in ("step_limit", "trace_limit", "execution_timeout", "output_limit")}
    if reason:
        response["reason"] = reason
    (job / "result.json").write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
