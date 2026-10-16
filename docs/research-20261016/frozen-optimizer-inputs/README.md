# 可移植的冻结优化器输入

本目录保存2026年10月16日真实资源评测中 **24个可比较输入，来自完整30条查询**。输入数值、目标文本、适用性标记、候选顺序、时间预算和成对相似度均与原始记录完全一致；没有重新取数、重新标注或调参。

其中 `BILI-18` 是有效的空可行集：4个匹配候选均超过600秒预算，最优选择为空。这条仍参与24组算法比较。其余6条不可比较结果为 `BILI-08`、`BILI-12`、`BILI-17`、`BILI-28`、`BILI-29`、`BILI-30`，原始失败/隐藏记录与[完整工程评测报告](../工程评测结果.md)保留完整30条分母。本目录的24条不能被描述成原30条全部成功。

## 保留内容与脱敏边界

- `decision.audit.optimizerInput`：生产优化器实际使用的全部固定输入，逐项原样保留。
- `id`、公开`query`、特征状态、原搜索状态及业务显示状态：保持与原记录一致。
- `decision.audit.metadata`：仅保留公开视频的`id`、`title`、`partTitle`和`description`，供现有CLI的词面二元字组基线重放。此处是公开视频文本，不是模型提示词；删除这些字段会改变词面排序，因而不能省略。
- 不包含模型调用记录、提示词、学习者表述、请求ID、内部端点、凭据、向量全文、本机路径或私有来源原句。公开视频简介中原有的公开链接属于历史元数据，不是本项目模型服务端点。

候选的`eligible`是原运行的适配判断，未被改作教师标注；首次命中审计仍将未取得的相关性标签保留为未知。原模型没有重新调用，冻结文本也不证明视频目前仍有效。控制反馈的原句引用已脱敏移除，数学输入和目标文本保持原值。

## 离线重放

在仓库根目录、已具备当前测试编译产物及测试依赖classpath的环境执行：

```powershell
./scripts/research-evaluation/Run-Evaluation.ps1 `
  -Mode replay `
  -Decisions docs/research-20261016/frozen-optimizer-inputs `
  -Output target/research-upgrade-20261016/evaluation/portable-replay-proof `
  -RunPurpose fixed-input-replay `
  -ClasspathFile target/research-upgrade-20261016/evaluation-classpath.txt
```

若在另一台电脑复核，将`ClasspathFile`换为该机器已生成的测试依赖classpath文件；不要复制原机器的绝对依赖路径。编译及依赖准备见[评测程序说明](../../../scripts/research-evaluation/README.md)，与本次纯离线重放分开。重放模式不要求AI密钥，不连接数据库、搜索平台或模型服务，不执行Maven，也不下载视频。

输出包括 `algorithm-comparison.json` 和 `algorithm-comparison.md`。该次CLI显示24条输入、24条可比较，表示这个冻结子集的数量；完整研究分母始终为30。

## 已执行的一致性核对

本目录已用现有CLI实际离线重放，并与原24组结果逐项比较，**24/24通过**：

- 原始优化器输入全部相等，包括每个数值、布尔标记、目标集合和顺序。
- 检索原序、语义排序、词面排序、贪心、最优五种方法的选择和分解分数全部一致。
- 独立Verifier结果一致。
- 三项系数消融和控制反馈目标情景的全部数值及选择一致。
- 检索—语义、检索—最优优先两类首次命中审计结果一致，包括未知标签枚举和无命中成本。

一致性比较仅排除运行耗时/时间戳，以及已脱敏删除的控制反馈来源原句；没有排除分数、标签、目标或选择结果。验证回执保存在本地输出目录的 `parity-verification.json` 与 `parity-verification.md`。

本次验证为0网络请求、0模型调用、0 Maven执行。它证明固定算法输入可移植重放，不构成独立模型泛化评估、教师相关性评审或学生学习效果证据。
