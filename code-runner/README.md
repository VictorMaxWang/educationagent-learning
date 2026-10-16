# 晓陶代码执行与过程追踪

本目录是业务 JVM 之外的执行代理和容器镜像。四种语言的轨迹均来自真实运行时的调试事件；代码编辑后必须重新执行，不存在预制轨迹回退。

## 私有接口

代理只监听 `127.0.0.1:8899`，所有接口要求 `Authorization: Bearer <CODE_RUNNER_TOKEN>`。token 至少 24 字符，不进入镜像或学生环境。

- `GET /health`：`{status:"ok",busy:false,languages:["javascript","python","cpp","java"],schemaVersion:1}`。
- `POST /run`：`{id,language,code,stdin}`，同步返回完成结果。`id` 只接受 1—80 位字母、数字、下划线或连字符，代码最多 20000 字符，输入最多 4000 字符。
- `DELETE /jobs/{id}`：取消当前匹配任务，已结束返回 404。Java 业务层负责浏览器所有权、有限排队、课堂生成优先及幂等请求；代理不接受第二个并发执行（409）。

执行结果：

```json
{"status":"completed","stdout":"6\n","stderr":"","diagnostics":[],"trace":[{"index":0,"line":2,"event":"line","frames":[{"name":"main","locals":{"sum":0,"values":{"$ref":"ref-1"}}}],"heap":{"ref-1":{"type":"Array","items":[1,2,3],"length":3,"truncated":false}},"stdout":""}],"truncated":false}
```

`status` 是 `completed/error/timeout/cancelled`。错误或截断可带 `reason`。`line` 为编辑器一基行号；`frames` 从当前函数向外排列。局部变量为 JSON 基础值、`{$ref:id}` 或 `{$unavailable:"原因"}`；每个快照的 `heap` 自包含，数组使用 `items`、对象使用 `properties`、指针包装使用 `value`。回退仅重放已有快照，不撤销真实程序。`stdout` 是截至该步骤的已刷新输出；C/C++ 输出缓冲可能在正常结束时才出现在最终 `stdout`。

## 构建与安装

```sh
export DOCKER_HOST=unix:///run/xiaotao-code/docker.sock
docker build --network=host -t xiaotao-code-runner:1 code-runner
```

固定镜像标签见 `sources.json`；镜像内 `/opt/runner/versions.txt` 保存构建时的运行时、编译器和调试器实际版本。部署流程应同时记录基础镜像 digest，不把同标签当作内容散列。`deploy/linux/code-runner-install.sh` 仅安装镜像与代理，要求已就绪的专用 Docker，不启动或重配全局 Docker。代理环境仅存于 `/etc/xiaotao-code/runner.env`，专用目录 `root:educationagent-code 0750`、文件 `0640`；安装不改动现有 `/etc/educationagent`。`code-runner.service` 是专用 Docker unit 的服务模板，站点发布流程负责安全写入同一 token 和启动顺序。

代理继承宿主的 `DOCKER_HOST`，但不将其传入学生容器。每次容器均为非 root、无网络、只读根文件系统，只有 `/job` 与 `/tmp` 是限额 tmpfs；无业务挂载、凭据、设备或 Docker socket。CPU 1 核、内存与 swap 合计 768MiB、128 PIDs、编译 20 秒、追踪 15 秒、1000 步、64KiB 输出、4MiB 轨迹。任务只通过代理生成的容器名清理。停止代理前应先由业务层停止接单并取消活跃任务。

安全边界是每任务专用容器。broker 用户对专用 Docker socket 有访问权，因此它本身属于可信服务，不对公网开放；学生调试器无需额外 SYS_PTRACE capability，也不关闭 seccomp。GDB 只跟踪它自己启动的同 UID 子进程。

## 限制与验收

首版仅单文件、单线程基础教学程序。Java 不使用 package 声明；支持任意单个 public class 名称。外部依赖、多文件工程、交互式终端和后台线程不属于当前接口。对象仅按字段读取，不执行 `repr`、`toString`、getter 或属性描述器。

```sh
# 真实容器四语言验收，token 从环境取，不出现在命令行。
python3 code-runner/tests/smoke.py --url http://127.0.0.1:8899
# 本机 Node/Python driver 验收（不用 Docker，不用于运行不可信源码）。
python3 code-runner/tests/smoke.py --local --languages javascript python
python3 -m unittest discover -s code-runner/tests -p 'test_*.py'
```

验收脚本覆盖面积、循环、排序、递归四组真实练习，代码和输入变化、数组/对象引用、调用栈、语法/编译错误、运行错误及资源上限。生产部署还应由业务层验收浏览器身份隔离、取消和队列优先级。
