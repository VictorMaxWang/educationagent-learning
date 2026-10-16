"""真实 JDI 冒烟验收：四种练习、数组/递归/对象、输入、错误和限制。"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time


SOURCE = Path(__file__).resolve().parent
DRIVER = SOURCE
LIMITS = dict(steps=1000, outputBytes=65536, traceBytes=4194304, seconds=15)


def execute(code, expected=None, stdin="", limits=None, class_name="Main"):
    with tempfile.TemporaryDirectory(prefix="java-trace-") as task:
        job = Path(task)
        (job / "classes").mkdir()
        (job / f"{class_name}.java").write_text(code, encoding="utf-8")
        (job / "request.json").write_text(json.dumps(dict(code=code, stdin=stdin, limits=limits or LIMITS)), encoding="utf-8")
        compiled = subprocess.run(["javac", "-g", "-encoding", "UTF-8", "-d", str(job / "classes"), str(job / f"{class_name}.java")], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
        if compiled.returncode:
            return dict(compile_error=True, stderr=compiled.stderr)
        started = time.monotonic()
        run = subprocess.run(["java", "-Xmx192m", "--add-modules", "jdk.jdi", "-cp", str(DRIVER), "TraceMain", str(job), class_name], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
        assert run.returncode == 0, run.stderr
        result = json.loads((job / "result.json").read_text(encoding="utf-8"))
        if (job / "checkpoint.json").exists():
            result["checkpoint"] = json.loads((job / "checkpoint.json").read_text(encoding="utf-8"))
        result["elapsedSeconds"] = round(time.monotonic() - started, 3)
        if expected is not None:
            assert result["status"] == "completed", result
            assert result["stdout"].strip() == expected, result["stdout"]
            assert result["trace"] and all(step["line"] > 0 for step in result["trace"]), result
        return result


def main():
    report = []

    def record(name, data):
        report.append(dict(name=name, status="PASS", executionStatus=data.get("status", "compile_error"), steps=len(data.get("trace", [])), reason=data.get("reason"), elapsedSeconds=data.get("elapsedSeconds")))

    area = execute("""public class Main {
  public static void main(String[] args) {
    int length = 8;
    int width = 5;
    int area = length * width;
    System.out.println(area);
  }
}
""", "40")
    assert any(step["frames"][0]["locals"].get("area") == 40 for step in area["trace"])
    record("面积计算和局部变量", area)

    loop = execute("""public class Main {
  public static void main(String[] args) {
    int total = 0;
    for (int i = 1; i <= 5; i++) {
      total += i;
    }
    System.out.println(total);
  }
}
""", "15")
    values = [step["frames"][0]["locals"].get("total") for step in loop["trace"]]
    assert {0, 1, 3, 6, 10, 15}.issubset(values), values
    record("循环累计真实中间值", loop)

    sorting = execute("""import java.util.Arrays;
public class Main {
  public static void main(String[] args) {
    int[] numbers = {5, 2, 4, 1};
    for (int i = 0; i < numbers.length - 1; i++) {
      for (int j = 0; j < numbers.length - i - 1; j++) {
        if (numbers[j] > numbers[j + 1]) {
          int temporary = numbers[j];
          numbers[j] = numbers[j + 1];
          numbers[j + 1] = temporary;
        }
      }
    }
    System.out.println(Arrays.toString(numbers));
  }
}
""", "[1, 2, 4, 5]")
    arrays = [item["items"] for step in sorting["trace"] for item in step["heap"].values() if item.get("type") == "int[]"]
    assert [5, 2, 4, 1] in arrays and [1, 2, 4, 5] in arrays
    record("数组排序与引用堆", sorting)

    recursion = execute("""public class Main {
  static int sum(int number) {
    if (number <= 1) return number;
    return number + sum(number - 1);
  }
  public static void main(String[] args) {
    int result = sum(5);
    System.out.println(result);
  }
}
""", "15")
    assert max(len(step["frames"]) for step in recursion["trace"]) >= 6
    record("递归求和与调用栈", recursion)

    inputs = execute("""import java.util.Scanner;
public class Garden {
  public static void main(String[] args) {
    Scanner input = new Scanner(System.in);
    int length = input.nextInt();
    int width = input.nextInt();
    System.out.println("面积：" + length * width);
  }
}
""", "面积：42", stdin="6 7\n", class_name="Garden")
    record("标准输入中文输出及自定义主类", inputs)

    objects = execute("""public class Main {
  static class Point {
    int x = 3;
    Point next;
    public String toString() { throw new RuntimeException("不要调用"); }
  }
  public static void main(String[] args) {
    Point point = new Point();
    point.next = point;
    System.out.println(point.x);
  }
}
""", "3")
    assert any(item.get("properties", {}).get("x") == 3 for step in objects["trace"] for item in step["heap"].values())
    assert any(item.get("properties", {}).get("next") == {"$ref": identity} for step in objects["trace"] for identity, item in step["heap"].items())
    record("循环对象引用且不调用用户toString", objects)

    failure = execute("""public class Main {
  public static void main(String[] args) {
    int zero = 0;
    int result = 3 / zero;
    System.out.println(result);
  }
}
""")
    assert failure["reason"] == "runtime_error" and failure["diagnostics"][0]["line"] == 4, failure
    assert "ArithmeticException" in failure["stderr"] and any(step["event"] == "exception" for step in failure["trace"])
    record("未捕获运行错误定位", failure)

    syntax = execute("public class Main { public static void main(String[] args) { int x = ; } }")
    assert syntax.get("compile_error") and "Main.java:1:" in syntax["stderr"], syntax
    record("编译错误保留源码行号", syntax)

    limit = execute("""public class Main {
  public static void main(String[] args) {
    int n = 0;
    while (true) {
      n++;
    }
  }
}
""", limits=dict(LIMITS, steps=12))
    assert limit["reason"] == "step_limit" and limit["truncated"] and len(limit["trace"]) == 12, limit
    record("死循环步骤上限保留轨迹", limit)

    timeout = execute("""public class Main {
  public static void main(String[] args) throws Exception {
    int entered = 1;
    Thread.sleep(8000);
  }
}
""", limits=dict(LIMITS, seconds=4))
    assert timeout["status"] == "timeout" and timeout["reason"] == "execution_timeout" and timeout["trace"], timeout
    assert timeout.get("checkpoint", {}).get("trace"), timeout
    record("阻塞程序限时终止保留轨迹", timeout)

    output = execute("""public class Main {
  public static void main(String[] args) {
    String text = "x".repeat(600);
    System.out.println(text);
    System.err.println(text);
  }
}
""", limits=dict(LIMITS, outputBytes=1024))
    assert output["reason"] == "output_limit" and len(output["stdout"].encode()) + len(output["stderr"].encode()) <= 1024, output
    record("stdout与stderr合并输出字节上限", output)

    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    receipt = dict(status="PASS", tests=len(report), driver="JDI", java=subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0], results=report)
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    # 测试构建使用临时目录；源码目录和发布包中不混入本机 JDK 生成的 class 文件。
    with tempfile.TemporaryDirectory(prefix="java-trace-driver-") as temporary_build:
        DRIVER = Path(temporary_build)
        compiled = subprocess.run(["javac", "--release", "21", "--add-modules", "jdk.jdi", "-d", str(DRIVER), str(SOURCE / "TraceMain.java")], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
        if compiled.returncode:
            raise RuntimeError(compiled.stderr)
        main()
