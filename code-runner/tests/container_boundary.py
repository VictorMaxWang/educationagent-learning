#!/usr/bin/env python3
"""在专用代理上验收真实容器边界、取消快照和单并发，不改变宿主设置。"""
import argparse
import json
import os
from pathlib import Path
import threading
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8899")
    parser.add_argument("--report")
    args = parser.parse_args()
    report = []
    token = os.environ["CODE_RUNNER_TOKEN"]

    def request(method, path, body=None):
        raw = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(args.url + path, data=raw, method=method, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            response = urllib.request.urlopen(request, timeout=50)
        except urllib.error.HTTPError as error:
            response = error
        return response.code, json.load(response)

    code = '''import json,os,socket
from pathlib import Path
checks={"uid":os.getuid(),"dockerSocket":Path("/var/run/docker.sock").exists(),"credentialNames":[k for k in os.environ if any(t in k.upper() for t in ["TOKEN","SECRET","API_KEY","PASSWORD"]) ]}
try:
 Path("/opt/runner/forbidden-write").write_text("test")
 checks["readOnly"]=False
except OSError:
 checks["readOnly"]=True
try:
 connection=socket.create_connection(("1.1.1.1",80),timeout=0.2)
 connection.close()
 checks["networkBlocked"]=False
except OSError:
 checks["networkBlocked"]=True
for name in ["memory.max","cpu.max","pids.max"]:
 path=Path("/sys/fs/cgroup")/name
 checks[name]=path.read_text().strip() if path.exists() else "unavailable"
print(json.dumps(checks))
'''
    status, result = request("POST", "/run", dict(id="boundary-" + uuid.uuid4().hex, language="python", code=code, stdin=""))
    assert status == 200 and result["status"] == "completed", result
    checks = json.loads(result["stdout"])
    assert checks["uid"] == 65532 and not checks["dockerSocket"] and not checks["credentialNames"], checks
    assert checks["readOnly"] and checks["networkBlocked"], checks
    assert checks["memory.max"] in ("805306368", "unavailable"), checks
    assert checks["pids.max"] in ("128", "unavailable"), checks
    if checks["cpu.max"] != "unavailable":
        quota, period = map(int, checks["cpu.max"].split())
        assert quota / period == 1, checks
    report.append(dict(name="container-boundary", status="PASS", checks=checks))

    task_id = "cancel-" + uuid.uuid4().hex
    response = {}
    def run():
        response["value"] = request("POST", "/run", dict(id=task_id, language="python", code="import time\nx=0\nwhile True:\n x+=1\n time.sleep(0.05)\n", stdin=""))
    thread = threading.Thread(target=run)
    thread.start()
    time.sleep(2)
    # 第二个执行不能越过代理单并发；未知 id 也不能取消正在执行的容器。
    assert request("POST", "/run", dict(id="second", language="python", code="print(1)", stdin=""))[0] == 409
    assert request("DELETE", "/jobs/someone-else")[0] == 404
    assert request("DELETE", "/jobs/" + task_id)[0] == 200
    thread.join(timeout=8)
    assert not thread.is_alive(), "cancel did not return"
    http_status, cancelled = response["value"]
    assert http_status == 200 and cancelled["status"] == "cancelled" and cancelled["trace"], cancelled
    report.append(dict(name="cancel-preserves-trace-and-single-concurrency", status="PASS", steps=len(cancelled["trace"])))
    assert request("GET", "/health")[1]["busy"] is False
    if args.report:
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
