import importlib.util
import json
import os
import subprocess
import sys
import time
from pathlib import Path
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

os.environ.setdefault("CODE_RUNNER_TOKEN", "unit-fixture-" * 4)
spec = importlib.util.spec_from_file_location("code_broker", Path(__file__).resolve().parents[1] / "broker.py")
broker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(broker)


class BrokerBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = broker.ThreadingHTTPServer(("127.0.0.1", 0), broker.Handler)
        cls.url = "http://127.0.0.1:" + str(cls.server.server_port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def request(self, path, body=None, auth=True, method=None):
        headers = {"Authorization": "Bearer " + broker.TOKEN} if auth else {}
        raw = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=raw, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        return response.code, json.load(response)

    def test_all_interfaces_require_bearer(self):
        for method, path in [("GET", "/health"), ("POST", "/run"), ("DELETE", "/jobs/test")]:
            code, response = self.request(path, auth=False, method=method)
            self.assertEqual(401, code)
            self.assertEqual("unauthorized", response["reason"])

    def test_invalid_source_never_starts_container(self):
        with patch.object(broker, "execute") as execute:
            for body in [dict(id="../unsafe", language="python", code="print(1)"), dict(id="good", language="bash", code="echo hi"), dict(id="good", language="python", code="x" * 20001)]:
                self.assertEqual(400, self.request("/run", body)[0])
            execute.assert_not_called()

    def test_private_metadata_is_not_passed_to_student(self):
        response = dict(status="completed", stdout="1\n", stderr="", trace=[], diagnostics=[], truncated=False)
        with patch.object(broker, "execute", return_value=response) as execute:
            code, _ = self.request("/run", dict(id="good", language="python", code="print(1)", stdin="", apiKey="must-not-pass", owner="private"))
            self.assertEqual(200, code)
            self.assertEqual({"id", "language", "code", "stdin"}, set(execute.call_args.args[0]))

    def test_busy_cannot_start_second_container(self):
        broker.LOCK.acquire()
        try:
            self.assertEqual(409, self.request("/run", dict(id="good", language="python", code="print(1)"))[0])
        finally:
            broker.LOCK.release()

    def test_cancel_cannot_remove_other_job(self):
        with broker.STATE_LOCK:
            broker.ACTIVE = {"id": "owned", "container": "xiaotao-code-fixture", "cancel": threading.Event()}
        try:
            with patch.object(broker, "docker_stop") as cleanup:
                self.assertEqual(404, self.request("/jobs/other", method="DELETE")[0])
                cleanup.assert_not_called()
                self.assertEqual(200, self.request("/jobs/owned", method="DELETE")[0])
                cleanup.assert_called_once_with("xiaotao-code-fixture")
        finally:
            with broker.STATE_LOCK:
                broker.ACTIVE = None

    def test_transport_caps_output_while_process_is_running(self):
        # 只生成内存中的重复字节，验证接收上限先于进程结束触发，不运行学生代码。
        process = subprocess.Popen([sys.executable, "-c", "import sys,time; sys.stdin.buffer.read(); sys.stdout.buffer.write(b'x'*1048576); sys.stdout.flush(); time.sleep(10)"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stopped = []
        def stop():
            stopped.append(True)
            process.kill()
        start = time.monotonic()
        with patch.object(broker, "RESPONSE_LIMIT", 4096):
            out, err, reason = broker.communicate_bounded(process, b"fixture", 5, stop)
        self.assertEqual("response_limit", reason)
        self.assertEqual(4096, len(out))
        self.assertTrue(stopped)
        self.assertLess(time.monotonic() - start, 4)


if __name__ == "__main__":
    unittest.main()
