# educationagent

面向乡村学生，尤其关注留守儿童学习与成长的单人学习工作台。沿用项目原有对话、练习、错题和成长知识功能，增加可以听、可以提问、可以动手探索的微课。

## 已实现的学习流程

输入主题或上传 PDF、Word、文字资料，选择小学至高中的年级，先确认可编辑大纲，再生成图文讲解、二维互动和随堂选择题。课堂支持语音讲授、暂停重听、按键录音提问、一位明确标识为 AI 的学习同伴，以及学习位置恢复。

随堂题复用原有题库和错题记录；心理、生命安全、青春期教育沿用原有知识模块。微信通过一次性配对码连接工作台，提供课程链接和课堂提问。

## 技术结构

- Java 21 / Spring Boot：业务 API、模型配置、MySQL 持久化、题库与语音。
- React / TypeScript / Vite：网页课堂；现有 Tauri 桌面外壳继续保留。
- `course-engine`：Node.js 按任务生成课程，通过 JSONL 回调 Java 模型。
- `vendor/educationagent`：本地课程契约、生成器和画布渲染器，无需另行运行 Next.js 或 PostgreSQL。

文本模型统一使用 OpenAI 兼容配置 `AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL`。当前接入模型为 `qwen3.8-flash`。语音与向量检索使用独立配置。

## 运行与配置

需要 JDK 21 或兼容版本、Node.js 22.19 以上、MySQL，以及所需的模型与语音凭据。配置模板见 [deploy/.env.example](deploy/.env.example)，真实密钥通过环境变量或本机忽略文件注入。

Windows 首次构建：

```powershell
./scripts/Build-Classroom.ps1 -InstallDependencies
```

日常开发可分别运行 Java 后端与 `desktop-app` 的 Vite。手机录音需要受信任 HTTPS。具体步骤见 [课堂运行指南](docs/课堂运行指南.md)；服务器配置见 [服务器部署说明](docs/服务器部署.md)。

## 代码与来源

[代码分析与实施计划](docs/乡村学习课堂_代码分析与实施计划.md) 说明原项目与迁入代码的职责边界。[GitHub 发布边界](docs/GitHub发布边界.md) 说明源码快照和私密文件的排除规则。第三方课程代码的来源、版本和 MIT 版权保留在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

运行数据、密钥、学生记录与本机语料均不在源码仓库中。
