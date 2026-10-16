"""Build an allowlisted, publishable report from preserved real run artifacts.

Only aggregates, public query names, public BV identifiers, algorithm metrics and
case identifiers are exported. No prompts, answers, request IDs, endpoint hashes,
credentials, internal source paths or provider traces are copied to docs.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import statistics

METHODS = ["search_order", "semantic_rank", "lexical_bigram_rank", "greedy", "optimum"]
METHOD_NAMES = {"search_order": "检索原顺序", "semantic_rank": "单项语义排序", "lexical_bigram_rank": "词面二元字组排序", "greedy": "同目标贪心", "optimum": "约束穷举最优"}
ROOT = Path(__file__).resolve().parents[2]

def read(path):
    return json.loads(path.read_text(encoding="utf-8"))

def mean(values):
    return round(statistics.mean(values), 6) if values else None

def intervals(audit):
    return {key: audit.get(key) for key in ["status", "minCostDifference", "maxCostDifference", "unknownCount", "evaluatedAssignments", "noHitLeftAssignments", "noHitRightAssignments", "reason"]}

def study_result(data, kind):
    groups = defaultdict(lambda: Counter())
    failures = []
    mismatches = []
    for row in data["cases"]:
        group = row.get("category") or "classification"
        groups[group]["total"] += 1
        groups[group]["completed"] += row["status"] == "completed"
        groups[group]["agreement"] += row.get("matchesEngineeringExpectation", False)
        groups[group]["failedOrUnknown"] += row["status"] != "completed"
        if row["status"] != "completed":
            failures.append({"id": row["id"], "category": group, "failureKind": row.get("failureKind", "unknown"),
                             "attempts": row.get("modelEvidence", {}).get("attemptCount"),
                             "gradeEffect": "unknown outcome; no confirmed error or score"})
        elif not row.get("matchesEngineeringExpectation", False):
            mismatches.append({"id": row["id"], "category": group, "actualStatuses": row.get("actualStatuses", [row.get("actualStatus")]), "expected": row.get("expected")})
    return {"run": kind, "totalCases": data["totalCases"], "completedCases": data["completedCases"], "agreementCases": data["matchesEngineeringExpectation"],
            "failedOrUnknown": data["failedOrUnknown"], "modelCalledCases": data["modelCalledCases"], "categoryCounts": dict(groups),
            "failureCases": failures, "completedMismatchCases": mismatches,
            "purpose": "development-contract-repair-regression" if kind == "feedback-r2" else "initial-development-engineering-check",
            "humanReviewed": False, "independentGeneralizationClaim": False}

def calls_report(traces):
    # 每trace顶部tokenUsage已汇总其重试，不再重复累加attempts；embedding只传新batches。
    metrics = {"tasks": 0, "transportAttempts": 0, "usageMissingTasks": 0, "reportedPromptTokens": 0,
               "reportedCompletionTokens": 0, "reportedTotalTokens": 0, "actualModels": [], "knownElapsedMs": 0}
    models = set()
    for trace in traces:
        metrics["tasks"] += 1
        metrics["transportAttempts"] += trace.get("attemptCount", 0)
        metrics["knownElapsedMs"] += trace.get("elapsedMs", 0)
        if trace.get("model"):
            models.add(trace["model"])
        usage = trace.get("tokenUsage")
        if usage is None:
            metrics["usageMissingTasks"] += 1
        else:
            for source, destination in [("prompt_tokens", "reportedPromptTokens"), ("completion_tokens", "reportedCompletionTokens"), ("total_tokens", "reportedTotalTokens")]:
                if isinstance(usage.get(source), (int, float)):
                    metrics[destination] += usage[source]
    metrics["actualModels"] = sorted(models)
    metrics["billingCostInCurrency"] = None
    metrics["usageMeaning"] = "reported token fields only; missing fields are not estimated; no monetary price inferred"
    return metrics

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=Path, default=ROOT / "target/research-upgrade-20261016/evaluation")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/research-20261016")
    args = parser.parse_args()
    source = args.runs
    classification = read(source / "live-r1/classification-results.json")
    feedback1 = read(source / "live-r1/feedback-results.json")
    feedback2 = read(source / "live-r2/feedback-results.json")
    replay = read(source / "features-r1/replay/algorithm-comparison.json")
    snapshots = read(source / "snapshot-audit.json")
    server_timing_path = source.parent / "optimizer-server-timing.json"
    server_timing = read(server_timing_path) if server_timing_path.exists() else None
    outcomes = [read(path) for path in sorted((source / "features-r1").glob("BILI-*.json"))]
    by_id = {row["id"]: row for row in replay["queries"]}
    comparable = [row for row in replay["queries"] if row["status"] == "completed"]
    baseline = {}
    for method in METHODS:
        values = [row["comparison"]["methods"][method] for row in comparable]
        baseline[method] = {
            "denominator": len(values), "meanFiniteObjective": mean([row["score"]["total"] for row in values]),
            "meanDurationSecondsIncludingEmpty": mean([row["score"]["durationSeconds"] for row in values]),
            "meanSelectedCountIncludingEmpty": mean([len(row["ids"]) for row in values]),
            "meanGoalCoverageIncludingEmpty": mean([row["score"]["coverage"] for row in values]),
            "meanRedundancyIncludingEmpty": mean([row["score"]["redundancy"] for row in values]),
            "sameSelectionAsOptimum": sum(row["ids"] == original["comparison"]["methods"]["optimum"]["ids"] for row, original in zip(values, comparable)),
            "feasibleOrValidEmpty": sum(row["feasible"] for row in values),
        }
    ablations = {}
    for name in ["without_semantic_relevance", "without_coverage", "without_redundancy"]:
        values = [row["comparison"]["ablations"][name] for row in comparable]
        ablations[name] = {"denominator": len(values), "selectionChanged": sum(row["ids"] != original["comparison"]["methods"]["optimum"]["ids"] for row, original in zip(values, comparable)),
                           "meanOriginalObjective": mean([row["originalObjectiveScore"]["total"] for row in values])}
    focused = [row["comparison"]["ablations"]["feedback_target_sensitivity"] for row in comparable]
    ablations["controlled_feedback"] = {"denominator": len(focused), "selectionChanged": sum(row["selectionChanged"] for row in focused),
        "baseGoalCounts": dict(Counter(len(row["sourceSupportedBaseGoals"]) for row in focused)), "mode": "controlled-feedback-fixture", "realStudentFeedback": False,
        "conclusion": "No observed selection change in these inputs; no feedback benefit established"}
    gate = classification["gateAblation"]
    ablations["gate"] = {key: gate[key] for key in ["mode", "inputCases", "actualPipelineWouldSearch", "withoutGateWouldSearch", "authoredProhibitedCases", "authoredProhibitedCasesBlocked", "modelFailureCasesRetained", "networkSearchesForThisAblation"]}
    first_hit = {}
    for name, getter in [("retrieval_vs_semantic", lambda row: row["comparison"]["firstHitAudit"]),
                         ("retrieval_vs_optimized_first", lambda row: row["comparison"]["firstHitOptimizedAudit"]["audit"])]:
        values = [getter(row) for row in comparable]
        computed = [row for row in values if row["status"] == "COMPUTED"]
        first_hit[name] = {"totalComparableQueries": len(values), "computed": len(computed),
            "intervalContainsZero": sum(row["minCostDifference"] <= 0 <= row["maxCostDifference"] for row in computed),
            "strictCostSavingCertified": sum(row["minCostDifference"] > 0 for row in computed),
            "strictCostIncreaseCertified": sum(row["maxCostDifference"] < 0 for row in computed),
            "identicallyZeroInterval": sum(row["minCostDifference"] == row["maxCostDifference"] == 0 for row in computed),
            "minimumLowerBound": min(row["minCostDifference"] for row in computed), "maximumUpperBound": max(row["maxCostDifference"] for row in computed),
            "allCandidateLabelsUnknown": True, "eachComparedRankingUsesSameFullPool": True,
            "scope": "exact compatibility bounds on checks, not estimated probabilities or observed student cost"}
    query_rows = []
    feature_traces, embedding_traces = [], []
    final_counts = Counter()
    for entry in outcomes:
        decision = entry.get("decision", {})
        audit = decision.get("audit", {})
        final_counts[decision.get("status", entry["status"])] += 1
        for key in ("classificationCall", "matchingCall", "modelCall"):
            if key in audit:
                feature_traces.append(audit[key])
        embedding_traces.extend(audit.get("embedding", {}).get("batches", []))
        row = {"id": entry["id"], "query": entry["query"], "snapshotSearchStatus": entry["searchStatus"],
               "featureStatus": entry["status"], "businessDisplayStatus": decision.get("status"), "comparable": by_id[entry["id"]]["status"] == "completed",
               "matchedCandidateCount": len(audit.get("optimizerInput", {}).get("candidates", [])), "selectedCount": len(decision.get("items", [])),
               "failureKind": audit.get("failureKind"), "classificationStatus": decision.get("classification", {}).get("status"),
               "fixedCatalogInvocations": entry.get("fixedCatalogCalls", {}).get("budgetArgument", 0), "newLiveSearchRequestsDuringReplay": 0}
        if row["comparable"]:
            comparison = by_id[entry["id"]]["comparison"]
            row["baselineScores"] = {method: comparison["methods"][method]["score"]["total"] for method in METHODS}
            row["selectedPublicResourceIds"] = comparison["methods"]["optimum"]["ids"]
            row["independentlyVerifiedOptimum"] = comparison["verification"]["optimal"]
            row["ablations"] = {name: {"selectionChanged": comparison["ablations"][name]["ids"] != comparison["methods"]["optimum"]["ids"],
                                      "originalObjective": comparison["ablations"][name]["originalObjectiveScore"]["total"]} for name in ["without_semantic_relevance", "without_coverage", "without_redundancy"]}
            row["controlledFeedbackSelectionChanged"] = comparison["ablations"]["feedback_target_sensitivity"]["selectionChanged"]
            row["firstHitSemantic"] = intervals(comparison["firstHitAudit"])
            row["firstHitOptimizedFirst"] = intervals(comparison["firstHitOptimizedAudit"]["audit"])
        query_rows.append(row)
    issues = [
        {"ids": ["BILI-08"], "stage": "metadata-semantic-matching", "finding": "水循环：8项均被语义/学段检查排除；若干有明确高中标记，另一些只因缺少适龄标记或作者名称被排除。", "boundary": "筛除是模型判断，未经人工内容质量审核；存在将缺少年级标签等同不适合的过度保守风险。"},
        {"ids": ["BILI-12"], "stage": "metadata-semantic-matching", "finding": "光的折射：8项均被排除；包含明确初高中内容，也有仅因年级信息不足而被排除的实验。", "boundary": "不能把所有排除项当作已证实不适合，未观看完整视频。"},
        {"ids": ["BILI-17"], "stage": "video-metadata-matching", "finding": "分数通分：分类通过；匹配两次HTTP200响应均未通过输出契约校验，最终contract-invalid，未进入向量及选优。", "attempts": 2, "elapsedMs": outcomes[16]["decision"]["audit"]["modelCall"]["elapsedMs"], "boundary": "失败原始输出和具体字段未保存，现有证据无法进一步定位是哪个字段；不能推断资源不相关，也未再次付费重试。"},
        {"ids": ["BILI-18"], "stage": "hard-duration-constraint", "finding": "分数约分：4个已匹配候选时长分别为1560、625、833、936秒，全部超过600秒预算；独立校验确认空可行集。", "boundary": "这是有效的空结果比较，计入24组算法分母；业务上隐藏视频区域。"},
        {"ids": ["BILI-28", "BILI-29", "BILI-30"], "stage": "teaching-task-classification", "finding": "数组排序可视化、Python循环讲解、Java递归流程被分类器判为代码调试/不在补充视频范围。", "boundary": "Python循环概念与递归流程不当然等同当前代码调试；这暴露出分类过度阻断风险，不能把3条都宣传为正确拒绝。后续通用提示修订不改变本轮冻结结果。"},
    ]
    calls = {}
    for name, data in [("classification-r1", classification), ("feedback-r1", feedback1), ("feedback-r2", feedback2)]:
        calls[name] = calls_report([row["modelEvidence"] for row in data["cases"] if row.get("modelEvidence", {}).get("attemptCount", 0) > 0])
    calls["features-r1-chat"] = calls_report(feature_traces)
    calls["features-r1-embedding-new-batches"] = calls_report(embedding_traces)
    report = {"schemaVersion": "publishable-research-evaluation-v1", "asOf": "2026-10-16", "humanReviewed": False, "independentGeneralizationClaim": False,
        "studyScope": "engineering contract checks, fixed-input finite-objective comparisons and controlled sensitivity; no real student study",
        "classification": study_result(classification, "classification-r1"), "feedbackInitial": study_result(feedback1, "feedback-r1"),
        "feedbackRegression": study_result(feedback2, "feedback-r2"),
        "publicResourceCapture": {key: snapshots[key] for key in ["queryCount", "outcomeCounts", "candidateOccurrences", "uniqueVideos", "verifiedOccurrences", "humanReviewed"]},
        "recommendationDenominators": {"queryOutcomes": len(outcomes), "businessStatusCounts": dict(final_counts), "comparableFeatureInputs": len(comparable), "nonComparableOutcomesRetained": len(outcomes)-len(comparable),
                                      "validEmptyFeasibleSetCasesIncluded": sum(row["comparable"] and row["selectedCount"] == 0 for row in query_rows)},
        "algorithmVersion": "classroom-combination-v1", "baselineVersion": replay["baselineVersion"], "baselineComparison": baseline, "ablations": ablations, "firstHit": first_hit,
        "optimizerLocalTiming": {"host": replay["optimizerTimingHost"], "p95Ms": replay["optimizerP95Ms"], "scope": "Windows fixed-input optimizer only; does not establish server P95"},
        "optimizerServerTiming": ({"status": "PASS", "hostOs": server_timing["hostOs"], "javaVersion": server_timing["javaVersion"],
            "realInputCount": server_timing["realInputCount"], "realFixedInputTiming": server_timing["realFixedInputTiming"],
            "syntheticCapacity20Timing": server_timing["syntheticCapacity20Timing"], "scope": server_timing["scope"]} if server_timing else
            {"status": "NOT_RUN_IN_THIS_REPORT", "requiredInput": "24 frozen verified feature inputs exported by export_timing_inputs.py; no new model call"}),
        "queryOutcomes": query_rows, "observedLimitations": issues, "reportedModelUsage": calls,
        "modelIdentityBoundary": "Cloud text model returned qwen3.8-flash; embeddings returned qwen3.7-text-embedding-flash at 1024 dimensions. Adapter name siliconflow denotes compatibility code path, not verified actual provider. Embedding provider field was absent; no provider invented.",
        "testSnapshot": {"fullBackend": {"tests": 752, "failures": 0, "errors": 14, "skipped": 1, "status": "FAILED_LOCAL_DATABASE_AUTHENTICATION", "cause": "Existing local MySQL Access denied for root@localhost"},
                         "frontend": {"files": 19, "tests": 178, "status": "PASS", "typecheck": "PASS", "build": "PASS"},
                         "engine": {"tests": 27, "status": "PASS", "build": "PASS"},
                         "postPatchTargeted": {"completedBatchTests": 284, "status": "PASS", "additionalConceptGateTests": 7, "additionalConceptGateStatus": "PASS", "batchCountsMayOverlap": True, "finalV13AdditionalTests": "PENDING_ROOT_FINAL_COUNTS"},
                         "deploymentAcceptance": "NOT_CLAIMED_BY_THIS_REPORT"},
        "releasePolicy": "Only this allowlisted summary is publishable; original run files remain local. No request IDs, prompts, raw answers, credentials or endpoint fingerprints copied."}
    # 显式允许清单输出；最后再检查常见内部trace字段，阻止将原JSON整段粘贴进入可发布资料。
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    for forbidden in ('"requestId"', '"endpointFingerprint"', '"loadedFrom"', '"apiKey"', '"understanding"', '"quote"'):
        if forbidden in serialized:
            raise ValueError("Internal trace field in publishable summary: " + forbidden)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "工程评测结果.json").write_text(serialized+"\n", encoding="utf-8")
    md = ["# 补充视频与学习核对：工程评测结果", "", "数据日期：2026年10月16日。本文依据保留的真实运行记录生成，未发起新模型或检索请求。", "",
          "## 结论与完整分母", "",
          "分类工程夹具72/72符合预期，其中48条实际调用模型，24条由规则直接处理。反馈初测40条全部返回，35条符合预期；5条资料歧义处理不符合契约。通用来源一致性修复后，原40条回归得到38条确认结果且均符合预期，另2条输出契约失败、结果未确定。**完整回归分母为40，不能写成40/40通过。**", "",
          "公开资源采集30/30查询成功，共240条候选出现、238个独立BV，240条详情均完成身份与元数据核验。固定输入推荐产生**23条可展示、6条隐藏、1条错误**；其中24组具有可复核特征输入，包含1组因时长预算形成的有效空结果，其他6组保留在全量分母中但不能进行算法得分比较。", "",
          "工程预期标签为代理编写、未人工审核；资料句段明确为自编夹具。反馈r2是看过初测结果后进行的开发期契约修复回归，不是独立留出泛化验证。本文不报告教师准确率、真实学生掌握度或学习提升。", "",
          "## 推荐基线与消融", "",
          "所有方法使用同一已匹配候选池、600秒预算、去重/适用性约束，并由独立Verifier重算。均值分母为24，包含分数约分的有效空结果；不把其他6个不可比较结果伪装为0分。下表J是既定有限目标函数值，不能当成独立教学质量评分。", "",
          "|方法|平均J|平均总秒数（含空结果）|平均选择数|与最优选择相同|", "|---|---:|---:|---:|---:|"]
    for method in METHODS:
        row = baseline[method]
        md.append(f"|{METHOD_NAMES[method]}|{row['meanFiniteObjective']:.6f}|{row['meanDurationSecondsIncludingEmpty']:.2f}|{row['meanSelectedCountIncludingEmpty']:.3f}|{row['sameSelectionAsOptimum']}/24|")
    md += ["", "独立验证器确认24/24组有限模型最优。**本批穷举与贪心24/24结果相同，没有建立穷举优于贪心的经验优势。** 23个非空最优均选择1项，另1组为空；这批数据多为单目标，不能据此证明多资源互补或复杂覆盖选择的收益。", "",
           "|消融|选择变化|按原目标重算的平均J|含义|", "|---|---:|---:|---|"]
    for key, label in [("without_semantic_relevance", "移除语义相关度"), ("without_coverage", "移除覆盖项"), ("without_redundancy", "移除重复惩罚")]:
        row=ablations[key]
        md.append(f"|{label}|{row['selectionChanged']}/24|{row['meanOriginalObjective']:.6f}|只移除选优系数，其他约束不变|")
    md += ["|控制反馈目标|0/24|按各自重点目标比较|明确为controlled-feedback-fixture，没有观测到选择变化|", "",
           "控制反馈情景从已有来源支持目标中选一项作为补充重点，候选和真实特征不变。23组原来仅有1个目标，另1组有2个目标；本次不能证明反馈目标优化有收益。覆盖和重复项的0变化也不等于这些设计无价值，只表示当前冻结输入没有揭示其增量作用。", "",
           "门控消融在同72条输入上离线比较无门控策略与真实分类结果：无门控允许72条搜索，实际允许24条；24条工程定义的禁止情境全部被阻断，另24条不确定情境也不搜索。**这项比较实际联网搜索次数为0**，没有把私密夹具发送给搜索平台。", "",
           "## 联合首次命中审计", "",
           "每组比较使用相同完整候选集合，同一个BV分P始终共享同一标签。未人工取得相关性标签，因此所有标签均保留未知，枚举的是相容赋值，不是估计真实正例概率。", "",
           "|比较|完成|区间包含0|恒为0|可严格证明节省检查次数|", "|---|---:|---:|---:|---:|"]
    for key,label in [("retrieval_vs_semantic","检索顺序 vs 语义排序"),("retrieval_vs_optimized_first","检索顺序 vs 最优选择优先")]:
        row=first_hit[key]
        md.append(f"|{label}|{row['computed']}/24|{row['intervalContainsZero']}/24|{row['identicallyZeroInterval']}/24|{row['strictCostSavingCertified']}/24|")
    md += ["", "最优优先排序先放当前推荐ID，再按语义顺序补全其余候选，仅用于离线审计。无命中按完整扫描成本计入，未删去失败赋值。所有24组区间均包含0；两种比较的总区间范围均为−4到5次检查，现有未知标签不足以得出某种排序严格节省查看成本。", "",
           "## 全部30条查询的结果", "", "|ID|公开查询|业务结果|匹配候选|选择项|算法比较|", "|---|---|---|---:|---:|---|"]
    for row in query_rows:
        md.append(f"|{row['id']}|{row['query']}|{row['businessDisplayStatus']}|{row['matchedCandidateCount']}|{row['selectedCount']}|{'纳入（含有效空集）' if row['comparable'] else '不可比较，仍保留'}|")
    md += ["", "## 无结果、错误与待改进项", ""]
    for issue in issues:
        md.append(f"- **{'、'.join(issue['ids'])}**：{issue['finding']} {issue['boundary']}")
    md += ["", "反馈r2的两个未确定项为 `FB-paraphrase-02` 与 `FB-conflict-07`，均为两次结构尝试后 `contract-invalid`。它们保持未确定，不计成学生错误；没有通过修改期望标签或覆盖r1结果消除失败。", "",
           "## 模型、耗时与验证边界", "",
           "文本接口真实返回模型为qwen3.8-flash；向量接口真实返回qwen3.7-text-embedding-flash，1024维。代码中的siliconflow是兼容适配器名称，不能据此断言实际上游厂商；向量响应未提供provider字段，报告保留未知。只统计本轮新embedding batches，缓存历史来源不重复计入用量；各运行Token聚合见JSON，不估算缺失用量或货币费用。", "",
           f"Windows 11本机固定输入优化器P95为**{replay['optimizerP95Ms']:.4f}毫秒**，仅计算选择器，不含模型、网络或独立Verifier。固定特征重放没有重新检索，原采集仅记录请求时间，不能据此虚构搜索延迟。", "",
           (f"正式Linux服务器使用Java {server_timing['javaVersion']}，在同24个冻结输入上采样{server_timing['realFixedInputTiming']['samples']}次，P95为**{server_timing['realFixedInputTiming']['p95Ms']:.6f}毫秒**；另列20候选合成容量夹具采样{server_timing['syntheticCapacity20Timing']['samples']}次，P95为**{server_timing['syntheticCapacity20Timing']['p95Ms']:.6f}毫秒**，两项均低于100毫秒。容量夹具只验证性能边界，不算真实资源样本或教学成效。" if server_timing else "正式Linux服务器P95待测，本机结果不代替服务器验收。"), "",
           "当前全后端快照：752项、0失败、14错误、1跳过；14项错误关联既有本机MySQL认证失败，所以全量构建没有通过。前端19个文件、178项测试通过，类型检查与发布构建通过；课程引擎27项测试及构建通过。后端284项针对性检查通过，另一次编程概念门控7项通过，批次可能重叠，不累计成291项唯一测试。v13追加补丁最终检查仍待主任务回填，本文不提前声称部署验收完成。", "",
           "## 复核入口", "",
           "- [脱敏机器可读结果](工程评测结果.json)：全部30条结果、基线分值、消融和首次命中上下界。",
           "- [评测程序说明](../../scripts/research-evaluation/README.md)：固定输入、模型调用、源码及编译身份和复跑规则。",
           "- 原始运行记录留在本地target目录，r1及r2分别保留；公开文档不包含请求ID、提示词、回答全文、内部端点或密钥。",
           "- 后续验证应用新的结果目录，不能覆盖本轮冻结记录；技术说明应把尚未显示收益的设计与已通过的工程性质分开陈述。", ""]
    (args.output / "工程评测结果.md").write_text("\n".join(md), encoding="utf-8")
    print(json.dumps({"classification": report["classification"]["agreementCases"], "feedbackR1Agreement": report["feedbackInitial"]["agreementCases"],
                      "feedbackR2Agreement": report["feedbackRegression"]["agreementCases"], "featureOutcomes": len(outcomes), "businessStatuses": dict(final_counts),
                      "comparable": len(comparable), "publishableTraceFields": "allowlisted", "output": str(args.output)}, ensure_ascii=False))

if __name__ == "__main__":
    main()
