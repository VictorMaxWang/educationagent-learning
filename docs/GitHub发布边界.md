# EducationAgent 的 GitHub 发布边界

当前项目采用独立源码快照发布。原工作区中的未提交修改、原 `origin` 和旧 Git 历史保留；发布脚本不创建远程仓库、不提交、不推送。

2026-10-15 的只读检查确认，原历史曾包含开发密钥配置、向量数据库和依赖目录。仅增加 `.gitignore` 无法移除已经被追踪的文件和历史，因此不能直接把原仓库全部历史推到新的 GitHub 仓库。

## 目标与保留事项

发布目标是独立的私有仓库 `VictorMaxWang/educationagent-learning`。已有 `EducationAgent` 是参考代码的 fork；`childcare-smart` 和 `smartchildcare-agent` 属于另一套托育系统，均不覆盖。

新的业务命名、npm scope 和界面名称使用 `educationagent`。迁移代码的来源、原作者版权与 MIT 许可必须保留：见 `vendor/educationagent/THIRD_PARTY_NOTICES.md` 和三个包各自的 `LICENSE`。包改名不改变许可证义务，也不代表这些代码全部为本项目原创。

## 复制范围

| 范围 | 处理 |
| --- | --- |
| `src` | Java 源码、测试、数据库结构、路由种子及必要资源；包含已清敏、仅引用环境变量/私密配置的 `application*.properties` |
| `desktop-app` | React/Vite 页面、测试、桌面壳源码和图标；排除依赖、构建结果、本地环境变量 |
| `course-engine` | 教学生成器源码、协议及测试 |
| `vendor/educationagent` | 本地适配包、源素材、测试和许可证；构建结果重新生成 |
| `scripts`、`docs`、`deploy` | 构建、运行、部署脚本及技术文档；排除私密环境文件、证书、日志和压缩包 |
| 根构建文件、`.mvn` | Maven/npm 清单及锁文件、wrapper、入口脚本；可选的 README、根许可证和部署入口 |
| 根 `data`、`chroma`、`output`、`logs`、`dev` | 全部不复制，包括数据库、语料、聊天/学生记录、导出文件和编译 class |
| `config/local-secrets.properties`、根个人文档、图片、备份、`.codex` 等工具配置 | 不复制 |

没有为 `data` 设置例外，现有法律或学校语料不随源码发布。新部署的数据库结构由项目初始化/迁移流程创建；真实学校资源、学生数据和模型密钥另行通过服务器配置提供。

## 生成与检查

在项目目录使用 PowerShell 7：

```powershell
# 默认仅检查白名单、必要文件、敏感配置和命名，不创建快照。
pwsh -NoLogo -NoProfile -NonInteractive -File scripts/Prepare-GitHubSnapshot.ps1

# 配置脱敏完成后，由主控执行复制。
pwsh -NoLogo -NoProfile -NonInteractive -File scripts/Prepare-GitHubSnapshot.ps1 -Apply
```

输出固定为 `target/github-publish`。脚本在复制前、临时目录和最终目录分别检查文本，并仅报告问题的文件、行号和属性名，不打印秘密值。检查包括常见 API key / 私钥格式、可识别的配置明文，以及本地被排除配置中敏感值的跨文件泄漏比对。自动扫描无法证明不存在所有形式的敏感信息，提交前仍需检查最终文件清单与 `git diff --cached --stat`。

脚本保留快照自身的 `.git`，不会读取或移动原工作区的 `.git`。重复执行只更新已有清单管理的文件；快照内出现未管理文件会拒绝覆盖。请在其他构建目录验证快照，避免将安装依赖或编译结果混入纯发布目录。任何缺少必需源码、秘密检查失败或复制错误都会返回失败，不得继续提交或推送。

发布清单 `.educationagent-publish-manifest.json` 记录本次文件列表和检查状态，不保存本地密钥、机器绝对路径或原仓库历史。

## 发布前验收

1. 主控完成模型和环境变量配置后重新运行检查，再生成快照；`application-dev.properties` 只包含清敏配置，`config/local-secrets.properties` 和真实 `.env` 不得出现在快照。
2. 核对三个适配包的源码、资源、许可证，以及 Maven/npm 锁文件均在清单中。前端必须先构建共享包，再安装/构建 `desktop-app`。
3. 在独立验证目录执行 `npm ci`、`npm run build:classroom-engine`、前端 `npm ci` 和 `npm run build`，以及 Maven 打包与针对性测试。运行依赖配置按部署指南设置。
4. 仅在快照目录新建 Git 历史。核对 GitHub 身份及新私有仓库名后提交推送，保留原工作区 `origin` 不变；推送后核验远程提交与可见性。

仓库发布、服务器部署、真实 API 调用和数据库连接是独立验收项。源码快照检查通过不能代替运行时验收。
