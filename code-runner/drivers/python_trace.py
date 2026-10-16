"""使用 CPython trace 事件记录学生文件，不把调试器自身的实现暴露为课堂步骤。"""
import io
import json
from pathlib import Path
import sys
import time
import types

job = Path(sys.argv[1]).resolve()
source = job / "main.py"
request = json.loads((job / "request.json").read_text(encoding="utf-8"))
limits = request["limits"]
trace = []
trace_bytes = 0
started = time.monotonic()
reason = None
diagnostics = []
error_text = ""
output_bytes = 0
checkpoint_time = 0


class LimitReached(BaseException):
    pass


class BoundedOutput(io.StringIO):
    def __init__(self):
        super().__init__()
        self.byte_count = 0

    def write(self, text):
        global reason, output_bytes
        byte_length = len(text.encode("utf-8", "replace"))
        if output_bytes + byte_length > limits["outputBytes"]:
            reason = "output_limit"
            raise LimitReached()
        self.byte_count += byte_length
        output_bytes += byte_length
        return super().write(text)


out = BoundedOutput()
err = BoundedOutput()


def snapshot_value(value, heap, seen, depth=0):
    if value is None or isinstance(value, (bool, int, float, str)):
        if isinstance(value, str):
            return value[:500]
        if isinstance(value, float) and (value != value or abs(value) == float("inf")):
            return {"$unavailable": str(value)}
        return value
    if isinstance(value, (types.ModuleType, types.FunctionType, type)):
        return {"$unavailable": type(value).__name__}
    identity = str(id(value))
    if identity in seen:
        return {"$ref": identity}
    seen.add(identity)
    if depth >= 3 or len(heap) >= 128:
        heap[identity] = {"$unavailable": "最大展开深度" if depth >= 3 else "最大对象数量", "type": type(value).__name__}
    elif isinstance(value, (list, tuple, set, frozenset)):
        items = list(value)
        heap[identity] = {"type": type(value).__name__, "items": [snapshot_value(v, heap, seen, depth + 1) for v in items[:50]], "length": len(items), "truncated": len(items) > 50}
    elif isinstance(value, dict):
        items = list(value.items())
        heap[identity] = {"type": "dict", "properties": {str(k)[:80]: snapshot_value(v, heap, seen, depth + 1) for k, v in items[:40]}, "truncated": len(items) > 40}
    else:
        # 不调用学生对象 repr/property，避免一次查看变量执行额外业务代码。
        try:
            fields = object.__getattribute__(value, "__dict__")
            heap[identity] = {"type": type(value).__name__, "properties": {str(k): snapshot_value(v, heap, seen, depth + 1) for k, v in list(fields.items())[:30]}}
        except BaseException:
            heap[identity] = {"type": type(value).__name__, "$unavailable": "无法读取成员"}
    return {"$ref": identity}


def on_trace(frame, event, arg):
    global reason, trace_bytes, checkpoint_time
    if frame.f_code.co_filename != str(source):
        return on_trace
    # 模块入口的 call 事件可能位于虚拟的第 0 行，编辑器只展示真实源码行。
    if frame.f_lineno <= 0:
        return on_trace
    if event not in ("call", "line", "return", "exception"):
        return on_trace
    if time.monotonic() - started > limits["seconds"]:
        reason = "execution_timeout"
        raise LimitReached()
    if len(trace) >= limits["steps"]:
        reason = "step_limit"
        raise LimitReached()
    heap, seen, frames = {}, set(), []
    current = frame
    while current and len(frames) < 20:
        if current.f_code.co_filename == str(source):
            locals_map = {}
            for key, value in list(current.f_locals.items())[:80]:
                if key.startswith("__") or isinstance(value, (types.ModuleType, types.FunctionType, type)):
                    continue
                try:
                    locals_map[key] = snapshot_value(value, heap, seen)
                except BaseException:
                    locals_map[key] = {"$unavailable": "无法读取"}
            frames.append({"name": current.f_code.co_name, "locals": locals_map})
        current = current.f_back
    item = {"index": len(trace), "line": frame.f_lineno, "event": event, "frames": frames, "heap": heap, "stdout": out.getvalue()}
    item_bytes = len(json.dumps(item, ensure_ascii=False).encode("utf-8"))
    if trace_bytes + item_bytes > limits["traceBytes"]:
        reason = "trace_limit"
        raise LimitReached()
    trace_bytes += item_bytes
    trace.append(item)
    if len(trace) == 1 or len(trace) % 25 == 0 or time.monotonic() - checkpoint_time > 0.2:
        temporary = job / "checkpoint.tmp"
        temporary.write_text(json.dumps({"status": "completed", "stdout": out.getvalue(), "stderr": err.getvalue(), "diagnostics": [], "trace": trace, "truncated": False}, ensure_ascii=False), encoding="utf-8")
        temporary.replace(job / "checkpoint.json")
        checkpoint_time = time.monotonic()
    return on_trace


old_in, old_out, old_err = sys.stdin, sys.stdout, sys.stderr
try:
    sys.stdin, sys.stdout, sys.stderr = io.StringIO(request.get("stdin", "")), out, err
    compiled = compile(source.read_text(encoding="utf-8"), str(source), "exec")
    scope = {"__name__": "__main__", "__file__": str(source)}
    sys.settrace(on_trace)
    exec(compiled, scope, scope)
except LimitReached:
    pass
except BaseException as exc:
    reason = "runtime_error"
    line = getattr(exc, "lineno", 0) or 0
    tb = exc.__traceback__
    while tb:
        if tb.tb_frame.f_code.co_filename == str(source):
            line = tb.tb_lineno
        tb = tb.tb_next
    error_text = f"{type(exc).__name__}: {exc}"
    diagnostics.append({"line": line, "message": error_text[:2000]})
finally:
    sys.settrace(None)
    sys.stdin, sys.stdout, sys.stderr = old_in, old_out, old_err
status = "completed" if reason is None else ("timeout" if reason == "execution_timeout" else "error")
response = {"status": status, "stdout": out.getvalue(), "stderr": (err.getvalue() + error_text)[:65536], "diagnostics": diagnostics, "trace": trace, "truncated": reason in ("step_limit", "trace_limit", "output_limit", "execution_timeout")}
if reason:
    response["reason"] = reason
(job / "result.json").write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
