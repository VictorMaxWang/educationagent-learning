# 工程评测包

此目录提供72条教学任务分类夹具、40条资料核对夹具、30条真实Bilibili公开查询快照采集，以及实际生产分类器、反馈语义链、推荐编排与算法的可重复评测入口。

标签由开发过程按已批准契约编写，**未经过团队人工审核**。它们可以核对工程预期，不能称为教师准确率或真实学生学习效果。夹具的短资料为明确自编句段，不伪装为论文原文。

## 运行

先由主任务统一完成 Maven test-compile，并生成测试依赖classpath；避免两个Maven实例同时写target/classes。

```powershell
py -3.13 scripts/research-evaluation/build_fixtures.py
py -3.13 scripts/research-evaluation/collect_bilibili.py --output target/research-upgrade-20261016/evaluation/bilibili --delay 1.5
./scripts/research-evaluation/Run-Evaluation.ps1 -Mode offline-fixtures
./scripts/research-evaluation/Run-Evaluation.ps1 -Mode classify-live -Parallel 2 -Output target/research-upgrade-20261016/evaluation/live
./scripts/research-evaluation/Run-Evaluation.ps1 -Mode feedback-live -Parallel 2 -Output target/research-upgrade-20261016/evaluation/live
./scripts/research-evaluation/Run-Evaluation.ps1 -Mode features-live -Output target/research-upgrade-20261016/evaluation/features
./scripts/research-evaluation/Run-Evaluation.ps1 -Mode replay -Decisions target/research-upgrade-20261016/evaluation/features -Output target/research-upgrade-20261016/evaluation/replay
py -3.13 scripts/research-evaluation/audit_snapshots.py --snapshots target/research-upgrade-20261016/evaluation/bilibili --output target/research-upgrade-20261016/evaluation
py -3.13 scripts/research-evaluation/export_timing_inputs.py --decisions target/research-upgrade-20261016/evaluation/features --output target/research-upgrade-20261016/evaluation/timing-inputs.bin
```

真实模型模式仅从进程环境读取 `AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL`、`AI_CHAT_ENABLE_THINKING`。向量特征模式另需 `EVAL_EMBEDDING_PROVIDER`、`EVAL_EMBEDDING_MODEL`、`EVAL_EMBEDDING_URL`、`EVAL_EMBEDDING_KEY`、`EVAL_EMBEDDING_DIMENSION`，可选 `EVAL_EMBEDDING_REQUEST_DIMENSIONS`。凭据由运行者在内存注入；不得写入此目录或数据快照。

`offline-fixtures`只检查结构与来源上下文，明确报告模型结果NOT_RUN。live模式没有可用凭据就失败，不回退模拟答案。每条结果独立落盘；同输出目录再次执行仅跳过已有结果，不悄悄重试失败的付费请求。重新评测请指定新的输出目录保留此前证据。每份结果记录harness修订、实际加载字节码SHA256、当前源码SHA256和加载位置；PowerShell入口拒绝运行时间早于源码的test-class，不使用旧cli-check覆盖新编译。

## 输入与比较边界

- B站采集遵循agent-reach的公开主页CookieJar→search/all/v2→view路线；最多每查询8个BV，缓存同BV详情，节流。遇到412/429或等效限流码停止后续联网，仍给剩余查询留下“未请求”记录，不绕风控，不下载媒体。
- 30个固定查询只包括公开主题和用途。原始排序、状态、请求时间、真实URL、UP主、时长、分P及核验状态均保存；所有失败也保留。
- `features-live`用固定快照替代catalog网络输入，同时覆盖`search(query,budget)`和`search(query,budget,Duration)`两个公开签名，记录实际分发计数并用生产编排测试验证没有落回父类网络路径。其他步骤使用生产ClassroomRecommendationService、VideoTeachingClassifier、ClassroomAiGateway及ResourceSemanticFeatures，包括生产Duration总预算重载。其课程上下文为查询构造的工程输入，不冒充正式站学生记录。
- 检索顺序、单项语义排序、贪心与最优共用同一已核验候选池和硬约束。最终目标值是公开公式的代理指标，不能把同一算法的自评分写为客观教学质量。
- 独立RecommendationVerifier重算约束与最优。首次命中审计同时对比检索顺序与语义排序，以及检索顺序与“最优选中ID按卡片顺序优先，剩余候选按语义顺序补全”的完整排序。二者始终使用同一完整候选集合；补全只用于离线审计，不改变卡片。使用共享未知标签的精确相容区间，保留无命中完整扫描成本，没有人工标签时不把模型分数转换为真实正/负标签。
- 三项系数消融分别去掉语义相关度、覆盖与重复，并用原目标重算。门控在72输入上比较实际pipeline与“始终允许搜索”的离线反事实策略，绝不外搜被抑制的私密输入。反馈使用明确标为controlled-feedback-fixture的成对输入：从已有来源支持目标中按固定字典序选一项为补充重点，与全部原目标比较选择、重点覆盖及代理分；不把该敏感性实验称为真实学生反馈收益。
- 运行时间区分网络、模型及纯优化器。Windows本机优化P95不冒充正式Linux服务器P95，正式服务器另跑固定输入评测。

独立服务器计时入口 `OptimizerTimingCli` 仅依赖JDK与本轮生产Optimizer，可编为小JAR复制至验收工作目录：

```powershell
javac --release 21 -encoding UTF-8 -d target/research-upgrade-20261016/evaluation/timing-classes src/main/java/com/demo/edu/classroom/recommendation/RecommendationOptimizer.java src/test/java/com/demo/edu/classroom/evaluation/OptimizerTimingCli.java
jar --create --file target/research-upgrade-20261016/evaluation/optimizer-timing.jar -C target/research-upgrade-20261016/evaluation/timing-classes .
java -cp optimizer-timing.jar com.demo.edu.classroom.evaluation.OptimizerTimingCli timing-inputs.bin optimizer-server-timing.json
```

最后一行在实际被测服务器运行，报告同时列真实固定输入与明确标注的20候选合成容量夹具，均预热150次、采样500次。它不包含网络、语义模型或独立Verifier耗时。

## 审核与冻结

classification每组前2例为开发集，其余留出；feedback每类第1例为开发集，其余留出。模型输出与工程预期不一致时保留原记录并分析，不能改预期答案再宣称全通过。权重当前固定为生产`classroom-combination-v1`，不利用留出集调参。

看过整组模型结果并修复通用契约后，重跑必须使用新目录并传`-RunPurpose contract-repair-regression`（Java参数`--run-purpose contract-repair-regression`）。这属于开发期契约修复回归，不能因为仍有held-out字段就称独立泛化评测。所有报告固定声明`independentGeneralizationClaim=false`，r1失败和未确定记录不被覆盖。
