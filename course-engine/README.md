# EducationAgent 课程生成引擎

此包只负责课程大纲、场景、讲解动作的结构化生成。Java 后端负责模型凭据、任务状态、存储与课堂 API；浏览器只使用同仓库的 DSL 和渲染器。

在项目根执行 `npm ci`，然后 `npm run build:classroom-engine`。输出入口为 `course-engine/dist/worker.js`，必须使用 Node 22.19 或更新版本。`npm run test:classroom-engine` 使用模拟模型，无外部 API 请求。

Java 用 ProcessBuilder 启动 `node course-engine/dist/worker.js`。一进程一任务，stdin/stdout 均为 UTF-8 JSONL，stdout 只允许协议消息。关闭 stdin 会结束进程，避免父进程退出后遗留生成任务。

输入：`{"type":"job","id":"job-id","operation":"outline|scene","input":{"courseId":"course-id","topic":"分数","grade":4,"subject":"数学","domain":"school","sourceText":"...","generationContext":"...","outlines":[],"scenes":[{"id":"previous-scene","order":0,"actions":[]}]},"outline":{...}}`。grade为1至12整数，outline/scene.order从0开始；既有scenes只传id/order/actions，不携带canvas/HTML。

模型调用：`{"type":"llm.request","id":"call-id","messages":[{"role":"system","content":"..."},{"role":"user","content":"..."}]}`。

Java 回答：`{"type":"llm.result","id":"call-id","text":"model output"}`，失败时用 `error` 字符串代替 `text`。

成功：`{"type":"result","id":"job-id","value":[]}`（outline）或 `value` 为完整 Scene（scene）；失败：`{"type":"error","id":"job-id","message":"...","code":"..."}`。

首批支持 slide、quiz、interactive。quiz 为 single/multiple，actions 为 speech/spotlight/laser/discussion；discussion 固定同伴 `peer`。互动页只允许自包含 HTML/CSS/JS，沙箱不带 allow-same-origin；脚本不可访问网络、父页面、表单或弹窗。该限制不等于验证生成内容的教学正确性。
