package com.demo.chroma.eval;

import com.demo.chroma.base.ChromaClient;
import com.demo.entity.RagEvalCaseEntity;
import com.demo.entity.RagEvalReportEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评估接口。
 *
 * <p>返回体沿用项目约定：{@code {code, msg, data}}，无统一 Result 类。
 * code 语义：200 成功 / 400 入参非法 / 409 已有批次在跑 / 500 服务端故障。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/eval")
public class RagEvalController {

    @Resource
    private RagEvalService ragEvalService;

    @Resource
    private RagEvalScheduleService ragEvalScheduleService;

    @Resource
    private ChromaClient chromaClient;

    @Value("${rag.eval.default-collection:travel_knowledge}")
    private String defaultCollection;

    @Value("${rag.eval.default-top-k:5}")
    private int defaultTopK;

    @Value("${rag.eval.default-sample-size:20}")
    private int defaultSampleSize;

    @Value("${rag.eval.max-top-k:20}")
    private int maxTopK;

    /** 报告里长文本字段的预览长度（full=true 时返回原文） */
    private static final int PREVIEW_CHARS = 400;
    private static final int CONTEXT_PREVIEW_CHARS = 1200;

    // ======================== 评估 ========================

    /**
     * 触发一轮评估（异步）。
     * POST /api/eval/run  body: {collection, top_k, sample_size, rebuild_golden_set}
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String collection = str(body == null ? null : body.get("collection"));
            if (collection.isBlank()) collection = defaultCollection;
            int topK = intVal(body, "top_k", defaultTopK);
            int sampleSize = intVal(body, "sample_size", defaultSampleSize);
            boolean rebuild = boolVal(body, "rebuild_golden_set", false);

            String runId = ragEvalService.startRun(collection, topK, sampleSize, rebuild);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run_id", runId);
            data.put("collection", collection);
            data.put("top_k", topK);
            data.put("sample_size", sampleSize);
            data.put("rebuild_golden_set", rebuild);
            data.put("progress", ragEvalService.getProgress(runId));
            result.put("code", 200);
            result.put("msg", "评估已启动（异步），请用 /api/eval/report?run_id=" + runId + " 轮询进度");
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (RagEvalBusyException e) {
            result.put("code", 409);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (Exception e) {
            log.error("📊 启动评估失败", e);
            result.put("code", 500);
            result.put("msg", "启动评估失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    /**
     * 重跑某批次中未成功的用例（写回原行）。
     * POST /api/eval/retry  body: {run_id}
     */
    @PostMapping("/retry")
    public Map<String, Object> retry(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String runId = str(body == null ? null : body.get("run_id"));
            int n = ragEvalService.retry(runId);
            result.put("code", 200);
            result.put("msg", n == 0 ? "该批次没有需要重跑的用例（已全部 SUCCESS）" : "已开始重跑 " + n + " 条用例");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run_id", runId);
            data.put("retried", n);
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (RagEvalBusyException e) {
            result.put("code", 409);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (Exception e) {
            log.error("📊 重跑评估失败", e);
            result.put("code", 500);
            result.put("msg", "重跑评估失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    /**
     * 查评估报告。
     * GET /api/eval/report?run_id=xxx&limit=50&full=false
     *
     * <p>不传 run_id 时返回最近若干批次的聚合列表 + 最新一批的明细。</p>
     */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(required = false) String run_id,
                                     @RequestParam(required = false, defaultValue = "50") int limit,
                                     @RequestParam(required = false, defaultValue = "false") boolean full) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String runId = run_id == null ? "" : run_id.trim();
            Map<String, Object> data = new LinkedHashMap<>();
            if (!runId.isEmpty()) {
                Map<String, Object> agg = ragEvalService.aggregateByRun(runId);
                if (agg == null) {
                    result.put("code", 400);
                    result.put("msg", "该 run_id 没有任何评估记录：" + runId);
                    result.put("data", null);
                    return result;
                }
                data.put("aggregate", agg);
                data.put("cases", toCaseMaps(ragEvalService.findReports(runId, limit), full));
                Map<String, Object> progress = ragEvalService.getProgress(runId);
                if (progress != null) data.put("progress", progress);
            } else {
                List<Map<String, Object>> runs = ragEvalService.listRecentRuns(Math.max(1, limit));
                data.put("runs", runs);
                if (!runs.isEmpty()) {
                    String latest = String.valueOf(runs.get(0).get("runId"));
                    data.put("latest_run_id", latest);
                    data.put("latest", toCaseMaps(ragEvalService.findReports(latest, limit), full));
                }
                data.put("hint", "带 run_id 参数可查看指定批次的聚合指标与明细；加 full=true 返回完整长文本");
            }
            result.put("code", 200);
            result.put("msg", "ok");
            result.put("data", data);
        } catch (Exception e) {
            log.error("📊 查询评估报告失败", e);
            result.put("code", 500);
            result.put("msg", "查询评估报告失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    // ======================== 黄金集 ========================

    /**
     * 生成/补齐黄金集。
     * POST /api/eval/golden/build  body: {collection, sample_size, rebuild}
     */
    @PostMapping("/golden/build")
    public Map<String, Object> buildGolden(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String collection = str(body == null ? null : body.get("collection"));
            if (collection.isBlank()) collection = defaultCollection;
            int sampleSize = intVal(body, "sample_size", defaultSampleSize);
            boolean rebuild = boolVal(body, "rebuild", false);

            int created = ragEvalService.buildGoldenSet(collection, sampleSize, rebuild);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("collection", collection);
            data.put("created", created);
            data.put("total", ragEvalService.countCases(collection));
            data.put("rebuild", rebuild);
            result.put("code", 200);
            result.put("msg", created == 0
                    ? "已有用例已达到 sample_size，直接复用（跨轮次可比）"
                    : "新增 " + created + " 条测试用例");
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (Exception e) {
            log.error("📊 生成黄金集失败", e);
            result.put("code", 500);
            result.put("msg", "生成黄金集失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    /**
     * 查看黄金集用例。
     * GET /api/eval/golden?collection=xxx&limit=50
     */
    @GetMapping("/golden")
    public Map<String, Object> golden(@RequestParam(required = false) String collection,
                                     @RequestParam(required = false, defaultValue = "50") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String col = (collection == null || collection.isBlank()) ? defaultCollection : collection.trim();
            List<RagEvalCaseEntity> cases = ragEvalService.findCases(col, Math.max(1, limit));
            List<Map<String, Object>> list = new ArrayList<>();
            for (RagEvalCaseEntity c : cases) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("question", c.getQuestion());
                m.put("ground_truth", c.getGroundTruth());
                m.put("source_chunk", preview(c.getSourceChunk(), PREVIEW_CHARS));
                m.put("chunk_hash", c.getChunkHash());
                list.add(m);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("collection", col);
            data.put("total", ragEvalService.countCases(col));
            data.put("cases", list);
            result.put("code", 200);
            result.put("msg", "ok");
            result.put("data", data);
        } catch (Exception e) {
            log.error("📊 查询黄金集失败", e);
            result.put("code", 500);
            result.put("msg", "查询黄金集失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    // ======================== 调度 ========================

    /** 查周度调度状态（含下次执行时间）。GET /api/eval/schedule */
    @GetMapping("/schedule")
    public Map<String, Object> getSchedule() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("code", 200);
            result.put("msg", "ok");
            result.put("data", ragEvalScheduleService.status());
        } catch (Exception e) {
            log.error("📊 查询评估调度失败", e);
            result.put("code", 500);
            result.put("msg", "查询评估调度失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    /**
     * 设置周度调度。
     * POST /api/eval/schedule  body: {enabled, days, time, collection, sample_size, top_k}
     */
    @PostMapping("/schedule")
    public Map<String, Object> setSchedule(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Boolean enabled = body != null && body.get("enabled") != null
                    ? boolVal(body, "enabled", false) : null;
            String days = body == null ? null : str(body.get("days"));
            String time = body == null ? null : str(body.get("time"));
            String collection = body == null ? null : str(body.get("collection"));
            Integer sampleSize = body != null && body.get("sample_size") != null
                    ? intVal(body, "sample_size", defaultSampleSize) : null;
            Integer topK = body != null && body.get("top_k") != null
                    ? intVal(body, "top_k", defaultTopK) : null;

            Map<String, Object> data = ragEvalScheduleService.setSchedule(
                    enabled,
                    days == null || days.isBlank() ? null : days,
                    time == null || time.isBlank() ? null : time,
                    collection == null || collection.isBlank() ? null : collection,
                    sampleSize, topK);
            result.put("code", 200);
            result.put("msg", "调度已更新");
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("msg", e.getMessage());
            result.put("data", null);
        } catch (Exception e) {
            log.error("📊 设置评估调度失败", e);
            result.put("code", 500);
            result.put("msg", "设置评估调度失败：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    // ======================== 健康检查 ========================

    /**
     * 健康检查：Chroma 可达性 + 目标集合文档数 + 黄金集条数 + 库表探活。
     * GET /api/eval/health?collection=xxx
     *
     * <p>三种状态分开报：Chroma 不可达 / 集合不存在 / 集合存在但为空。
     * 前两者都表现为「没有数据」，但排查方向完全不同。</p>
     */
    @GetMapping("/health")
    public Map<String, Object> health(@RequestParam(required = false) String collection) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            String col = (collection == null || collection.isBlank()) ? defaultCollection : collection.trim();
            data.put("collection", col);

            // 1) 库表探活：spring.sql.init.continue-on-error=true 意味着 DDL 写错不会阻止启动，
            //    只会在运行时以「表不存在」暴露，所以这里主动查一次
            try {
                data.put("db_ok", true);
                data.put("golden_case_count", ragEvalService.countCases(col));
            } catch (Exception e) {
                data.put("db_ok", false);
                data.put("db_error", "评估表不可用（可能是 schema.sql 建表失败）：" + e.getMessage());
            }

            // 2) Chroma 可达性 + 集合状态
            String collectionId;
            try {
                collectionId = chromaClient.findCollectionId(col);
            } catch (Exception e) {
                data.put("chroma_ok", false);
                data.put("collection_exists", false);
                data.put("hint", "Chroma 不可达：" + e.getMessage() + "，请确认 Chroma 服务已启动（默认 :8001）");
                result.put("code", 500);
                result.put("msg", "Chroma 向量库不可用");
                result.put("data", data);
                return result;
            }
            data.put("chroma_ok", true);
            data.put("collection_exists", collectionId != null);
            if (collectionId == null) {
                data.put("hint", "集合不存在。请先向对应知识目录放入文档并重启触发索引"
                        + "（旅行知识库：data/travel-knowledge/；法律：data/law-knowledge/）");
            } else {
                int n = chromaClient.count(collectionId);
                data.put("document_count", n);
                if (n == 0) {
                    data.put("hint", "集合存在但文档数为 0，评估会因无 chunk 可抽样而失败");
                }
            }

            // 3) 当前是否有批次在跑
            data.put("default_top_k", defaultTopK);
            data.put("max_top_k", maxTopK);
            data.put("schedule", ragEvalScheduleService.status());

            result.put("code", 200);
            result.put("msg", "ok");
            result.put("data", data);
        } catch (Exception e) {
            log.error("📊 评估健康检查异常", e);
            result.put("code", 500);
            result.put("msg", "评估健康检查异常：" + e.getMessage());
            result.put("data", null);
        }
        return result;
    }

    // ======================== 内部工具 ========================

    /** 报告行转 Map；长文本按长度截断，full=true 时原样返回 */
    private List<Map<String, Object>> toCaseMaps(List<RagEvalReportEntity> rows, boolean full) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RagEvalReportEntity r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("run_id", r.getRunId());
            m.put("collection", r.getCollection());
            m.put("top_k", r.getTopK());
            m.put("question", r.getQuestion());
            m.put("ground_truth", full ? r.getGroundTruth() : preview(r.getGroundTruth(), PREVIEW_CHARS));
            m.put("answer", full ? r.getAnswer() : preview(r.getAnswer(), PREVIEW_CHARS));
            m.put("retrieved_contexts", full ? r.getRetrievedContexts()
                    : preview(r.getRetrievedContexts(), CONTEXT_PREVIEW_CHARS));
            m.put("context_recall", r.getContextRecall());
            m.put("faithfulness", r.getFaithfulness());
            m.put("factual_correctness", r.getFactualCorrectness());
            m.put("judge_model", r.getJudgeModel());
            m.put("prompt_version", r.getPromptVersion());
            m.put("status", r.getStatus());
            m.put("error_msg", r.getErrorMsg());
            m.put("duration_ms", r.getDurationMs());
            m.put("create_time", r.getCreateTime());
            list.add(m);
        }
        return list;
    }

    private static String preview(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…(已截断，加 full=true 看全文)";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intVal(Map<String, Object> body, String key, int def) {
        if (body == null) return def;
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是数字，收到：" + v);
        }
    }

    private static boolean boolVal(Map<String, Object> body, String key, boolean def) {
        if (body == null) return def;
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
