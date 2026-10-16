"""无 GDB 环境也验证关键边界：失效/未声明对象绝不能进入内存序列化。"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


class FakeCommand:
    def __init__(self, *args):
        pass


class FakeBlock(list):
    function = True


class FakeFrame:
    def __init__(self, current_line, declaration_line, name="main", value=None):
        self.current_line = current_line
        self.declaration_line = declaration_line
        self.function_name = name
        self.value = value
        self.read_count = 0

    def find_sal(self):
        return types.SimpleNamespace(line=self.current_line, symtab=types.SimpleNamespace(filename="/job/main.cpp"))

    def block(self):
        return FakeBlock([types.SimpleNamespace(is_argument=False, is_variable=True, name="numbers", line=self.declaration_line)])

    def name(self):
        return self.function_name

    def read_var(self, symbol):
        self.read_count += 1
        if symbol == "this":
            return 4096
        if self.value is None:
            raise AssertionError("未声明变量不应触发内存读取")
        return self.value

    def older(self):
        return None


class CppLifetimeTest(unittest.TestCase):
    def setUp(self):
        self.gdb = types.ModuleType("gdb")
        self.gdb.Command = FakeCommand
        self.gdb.COMMAND_USER = 0
        self.gdb.newest_frame = lambda: self.frame
        source = Path(__file__).resolve().parents[1] / "drivers/gdb_snapshot.py"
        spec = importlib.util.spec_from_file_location("gdb_snapshot_fixture", source)
        self.module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"gdb": self.gdb}):
            spec.loader.exec_module(self.module)

    def snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            self.module.Snapshot().invoke(str(path), False)
            return json.loads(path.read_text(encoding="utf-8"))

    def test_future_same_line_and_unknown_declarations_are_not_read(self):
        for current, declared in [(6, 9), (9, 9), (6, 0)]:
            self.frame = FakeFrame(current, declared)
            data = self.snapshot()
            self.assertEqual(0, self.frame.read_count)
            self.assertIn("$unavailable", data["frames"][0]["locals"]["numbers"])

    def test_destroyed_object_is_not_serialized(self):
        self.frame = FakeFrame(26, 9, value=types.SimpleNamespace(address=4096))
        self.module.DESTROYED.add("0x1000")
        data = self.snapshot()
        self.assertEqual({}, data["heap"])
        self.assertTrue(data["lifetimeEnded"])
        self.assertEqual("对象生命周期已结束", data["frames"][0]["locals"]["numbers"]["$unavailable"])

    def test_real_constructor_clears_tombstone_on_reused_address(self):
        self.frame = FakeFrame(1, 0, name="std::vector<int, std::allocator<int> >::~vector")
        self.module.TrackLifetime().invoke("", False)
        self.assertIn("0x1000", self.module.DESTROYED)
        self.frame.function_name = "std::vector<int, std::allocator<int> >::vector"
        self.module.TrackLifetime().invoke("", False)
        self.assertNotIn("0x1000", self.module.DESTROYED)


if __name__ == "__main__":
    unittest.main()
