# EducationAgent 教学引擎第三方来源

本目录的 `dsl`、`generation`、`renderer` 基于 [THU-MAIC/OpenMAIC](https://github.com/THU-MAIC/OpenMAIC) 的三个同名 SDK 包进行本地适配。

- 上游版本：提交 `ecb940c136cc5b4ea56dc81b2505e64eff1dea29`（2026-10-14）。
- 上游包版本：dsl 0.11.3、generation 0.3.20、renderer 0.1.14。
- 许可证：MIT；各包 `LICENSE` 保留上游完整版权与许可声明。
- 本地修改：包 scope 统一为 `@educationagent`；独立 npm workspace 构建；禁用不需要的字体 CDN 导出及旧 Tailwind peer；增加本地 KaTeX 样式入口。
- 测试适配：移除一项检查上游 Next 应用专用转发文件存在性的测试，该应用未迁移；SDK 行为测试保留。
- 原始参考仓库未被修改。生成适配、受限互动 HTML、Java JSONL 桥由 `course-engine` 提供，不依赖上游 Next.js 服务、数据库或源码目录。

相关 NPM 运行依赖沿用各自许可证；本项目未迁移上游品牌、界面或第三方图片素材。后续再次迁移应保留这里的来源与每个包的 LICENSE。
