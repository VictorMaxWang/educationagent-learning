# 技术架构与算法数据流图

本目录将本轮实现的职责、调用方向及证据边界整理为两张互补图。FigureSpec JSON 是可复现的布局源，SVG 是可编辑矢量图，PNG 是实际渲染预览。

| 图 | 可编辑输出 | 布局源 | 预览 |
|---|---|---|---|
| 分层技术架构 | [architecture.svg](architecture.svg) | [architecture.json](specs/architecture.json) | [architecture.png](architecture.png) |
| 推荐算法与学习反馈数据流 | [recommendation-flow.svg](recommendation-flow.svg) | [recommendation-flow.json](specs/recommendation-flow.json) | [recommendation-flow.png](recommendation-flow.png) |

## 语义约定

- 架构图实线表示业务调用与支撑依赖，结果通过对应接口返回；并非部署节点或网络边界图。
- 数据流图展示推荐和可选反思的处理先后；分层提示按需使用，自检不是继续课堂的前置条件。
- 视频候选仅在适用性、资源身份、时长和组合约束均通过后展示；示意图未逐条绘制所有异常回路，任一步失败均隐藏无可靠结果的区域。
- M001、M002 为方法适配，M003 为设计适配。这里的差额只属于当前有限评分模型，首次命中模块属于离线工程审计。
- 原模型训练、原专利算法完整复现与真实教学效果证明均不属于这两张图的主张。

## 复现

使用当前已安装的 `figure-spec` 技能原始渲染器；没有修改技能或安装依赖。在项目根目录执行：

```powershell
$figureRenderer = 'C:/Users/12804/.codex/skills/user-managed/figure-spec/scripts/figure_renderer.py'
foreach ($figureName in @('architecture', 'recommendation-flow')) {
  py -3.13 $figureRenderer validate "docs/research-20261016/figures/specs/$figureName.json"
  if ($LASTEXITCODE -ne 0) { throw "Invalid FigureSpec: $figureName" }
  py -3.13 $figureRenderer render "docs/research-20261016/figures/specs/$figureName.json" --output "docs/research-20261016/figures/$figureName.svg"
  if ($LASTEXITCODE -ne 0) { throw "Render failed: $figureName" }
}
```

PNG 使用当前已存在的 Codex bundled Node `sharp` 从 SVG 以 144 dpi 直接渲染，字体优先 `Microsoft YaHei`。尺寸分别为 2880×2180 和 3200×2080。未调用网站浏览器或另起浏览器驱动。

当前没有可用的 SVG→矢量 PDF 转换器，因此未提供 PDF，也没有以栅格 PDF 冒充矢量稿；SVG 保留完整文字与形状。

## 核验记录

两份 JSON 均通过原渲染器 schema 校验；两个 PNG 均已实际打开核看，中文、连接方向、字号、留白和边界清晰。第二张图移除了跨越小节标题的长连线后重新渲染并核看。该检查只验证图示呈现，不替代主任务中的接口、算法和线上运行测试。
