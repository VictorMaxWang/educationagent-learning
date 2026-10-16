#!/usr/bin/env python3
"""用真实源码检验行为，不能用固定动画或仅检查响应字段通过验收。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PROGRAMS = {
    "javascript": {
        "area": 'const fs = require("node:fs");\nconst [width,height] = fs.readFileSync(0,"utf8").trim().split(/\\s+/).map(Number);\nconst area=width*height;\nconsole.log(area);',
        "loop": 'let sum=0;\nfor(let i=1;i<=5;i++){\n sum+=i;\n}\nconsole.log(sum);',
        "sort": 'const values=[3,1,2];\nfor(let i=0;i<values.length;i++){\n for(let j=0;j<values.length-1;j++){\n  if(values[j]>values[j+1]){\n   const t=values[j]; values[j]=values[j+1]; values[j+1]=t;\n  }\n }\n}\nconsole.log(values.join(" "));',
        "recursion": 'function total(n){\n if(n===0) return 0;\n return n+total(n-1);\n}\nconst answer=total(4);\nconsole.log(answer);',
        "syntax": 'let = ;', "runtime": 'const x = null;\nconsole.log(x.value);',
        "limit": 'let i=0;\nwhile(true){\n i++;\n}',
    },
    "python": {
        "area": 'width,height=map(int,input().split())\narea=width*height\nprint(area)',
        "loop": 'total=0\nfor i in range(1,6):\n total+=i\nprint(total)',
        "sort": 'values=[3,1,2]\nfor i in range(len(values)):\n for j in range(len(values)-1):\n  if values[j]>values[j+1]:\n   values[j],values[j+1]=values[j+1],values[j]\nprint(*values)',
        "recursion": 'def total(n):\n if n==0:\n  return 0\n return n+total(n-1)\nanswer=total(4)\nprint(answer)',
        "syntax": 'if :', "runtime": 'x=None\nprint(x.value)',
        "limit": 'i=0\nwhile True:\n i+=1',
    },
    "cpp": {
        "area": '#include <iostream>\nint main(){\n int width,height;\n std::cin>>width>>height;\n int area=width*height;\n std::cout<<area<<"\\n";\n}',
        "loop": '#include <iostream>\nint main(){\n int sum=0;\n for(int i=1;i<=5;i++){\n  sum+=i;\n }\n std::cout<<sum<<"\\n";\n}',
        "sort": '#include <iostream>\nint main(){\n int values[]={3,1,2};\n for(int i=0;i<3;i++){\n  for(int j=0;j<2;j++){\n   if(values[j]>values[j+1]){\n    int t=values[j]; values[j]=values[j+1]; values[j+1]=t;\n   }\n  }\n }\n std::cout<<values[0]<<" "<<values[1]<<" "<<values[2]<<"\\n";\n}',
        "recursion": '#include <iostream>\nint total(int n){\n if(n==0) return 0;\n return n+total(n-1);\n}\nint main(){\n int answer=total(4);\n std::cout<<answer<<"\\n";\n}',
        "syntax": 'int main( {', "runtime": '#include <cstdlib>\nint main(){\n std::abort();\n}',
        "limit": 'int main(){\n int i=0;\n while(true){\n  i++;\n }\n}',
    },
    "java": {
        "area": 'import java.util.Scanner;\npublic class Main {\n public static void main(String[] args){\n  Scanner input=new Scanner(System.in);\n  int width=input.nextInt(), height=input.nextInt();\n  int area=width*height;\n  System.out.println(area);\n }\n}',
        "loop": 'public class Main {\n public static void main(String[] args){\n  int sum=0;\n  for(int i=1;i<=5;i++){\n   sum+=i;\n  }\n  System.out.println(sum);\n }\n}',
        "sort": 'public class Main {\n public static void main(String[] args){\n  int[] values={3,1,2};\n  for(int i=0;i<values.length;i++){\n   for(int j=0;j<values.length-1;j++){\n    if(values[j]>values[j+1]){\n     int t=values[j]; values[j]=values[j+1]; values[j+1]=t;\n    }\n   }\n  }\n  System.out.println(values[0]+" "+values[1]+" "+values[2]);\n }\n}',
        "recursion": 'public class Main {\n static int total(int n){\n  if(n==0) return 0;\n  return n+total(n-1);\n }\n public static void main(String[] args){\n  int answer=total(4);\n  System.out.println(answer);\n }\n}',
        "syntax": 'public class Main {', "runtime": 'public class Main {\n public static void main(String[] args){\n  throw new RuntimeException("trace-test");\n }\n}',
        "limit": 'public class Main {\n public static void main(String[] args){\n  int i=0;\n  while(true){\n   i++;\n  }\n }\n}',
    },
}


def invoke(args, language, code, stdin=""):
    request = {"id": "smoke-" + uuid.uuid4().hex, "language": language, "code": code, "stdin": stdin}
    if args.local:
        with tempfile.TemporaryDirectory(prefix="xiaotao-trace-") as directory:
            env = dict(os.environ, CODE_JOB_DIR=directory)
            process = subprocess.run([sys.executable, str(ROOT / "runner.py")], input=json.dumps(request), text=True, capture_output=True, timeout=48, env=env)
            if process.returncode:
                raise AssertionError(process.stderr)
            return json.loads(process.stdout)
    token = os.environ.get("CODE_RUNNER_TOKEN", "")
    request = urllib.request.Request(args.url.rstrip("/") + "/run", data=json.dumps(request).encode(), headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=50) as response:
        return json.load(response)


def verify_success(result, expected, name):
    assert result["status"] == "completed", (name, result.get("reason"), result["stderr"])
    assert result["stdout"].strip() == expected, (name, result["stdout"])
    trace = result["trace"]
    assert trace and all(item["line"] > 0 and item["frames"] for item in trace), name
    assert [item["index"] for item in trace] == list(range(len(trace))), name
    assert any(frame["locals"] for item in trace for frame in item["frames"]), name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8899")
    parser.add_argument("--local", action="store_true")
    parser.add_argument("--languages", nargs="+", default=list(PROGRAMS))
    parser.add_argument("--quick", action="store_true", help="只测 16 组成功路径，不做额外失败和资源限制用例")
    parser.add_argument("--report")
    args = parser.parse_args()
    report = []
    for language in args.languages:
        programs = PROGRAMS[language]
        for exercise, expected in [("area", "24"), ("loop", "15"), ("sort", "1 2 3"), ("recursion", "10")]:
            start = time.monotonic()
            result = invoke(args, language, programs[exercise], "6 4\n" if exercise == "area" else "")
            verify_success(result, expected, language + "/" + exercise)
            if exercise == "sort":
                assert any(obj.get("items") == [1, 2, 3] for step in result["trace"] for obj in step.get("heap", {}).values()), (language, "sorted array not observed")
            if exercise == "recursion":
                assert max(len(step["frames"]) for step in result["trace"]) >= 4, (language, "recursive stack not observed")
            report.append({"language": language, "exercise": exercise, "status": "PASS", "steps": len(result["trace"]), "seconds": round(time.monotonic() - start, 3)})
            print(json.dumps(report[-1]), flush=True)
        changed = invoke(args, language, programs["area"], "7 5\n")
        verify_success(changed, "35", language + "/changed-input")
        if not args.quick:
            for case in ("syntax", "runtime", "limit"):
                result = invoke(args, language, programs[case])
                assert result["status"] in ("error", "timeout"), (language, case, result)
                if case == "limit":
                    assert result["truncated"] and result["trace"], (language, result)
                report.append({"language": language, "exercise": case, "status": "PASS", "reason": result.get("reason"), "steps": len(result["trace"])})
                print(json.dumps(report[-1]), flush=True)
            if language == "javascript":
                result = invoke(args, language, "console.log('before exit');\nprocess.exit(2);")
                assert result["status"] == "error" and result.get("reason") == "runtime_error", result
                report.append({"language": language, "exercise": "nonzero-exit", "status": "PASS"})
                print(json.dumps(report[-1]), flush=True)
    if args.report:
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
