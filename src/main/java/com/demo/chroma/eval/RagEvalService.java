package com.demo.chroma.eval;

import com.demo.chroma.base.ChromaClient;
import com.demo.chroma.base.EmbeddingService;
import com.demo.entity.RagEvalCaseEntity;
import com.demo.entity.RagEvalReportEntity;
import com.demo.mapper.RagEvalCaseMapper;
import com.demo.mapper.RagEvalReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 自动化评估编排服务。
 *
 * <p>流水线：抽样 chunk 反向生成黄金集 → 对每个用例「检索 context → 基于 context 现算答案 →
 * 裁判打三项分」→ 逐行落 {@code rag_eval_report}。批次级聚合由 SQL 现算，不落库。</p>
 *
 * <p><b>评估的是 RAG 召回+接地生成链路，不是 AgentExecutor 的线上表现</b>：
 * 答案由本模块独立的 LLM 调用在「严格基于 context」约束下现算，不含工具调用与多轮上下文。
 * 这样指标才能复现、才能跨轮次对比 —— 线上真实回答噪声太大，无法用来判断知识库好坏。</p>
 */
@Slf4j
@Service
@DependsOn("databaseMigration")
public class RagEvalService {

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private RagEvalJudge judge;

    @Resource
    private RagEvalCaseMapper caseMapper;

    @Resource
    private RagEvalReportMapper reportMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${rag.eval.parallelism:4}")
    private int parallelism;

    @Value("${rag.eval.max-top-k:20}")
    private int maxTopK;

    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 编排线程：一次只跑一个批次 */
    private ExecutorService orchestrator;
    /** 用例线程池：批内并行 */
    private ExecutorService casePool;

    /** 防重入：手动触发与定时触发共用 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 当前批次号（仅用于冲突时告诉调用方「谁在跑」） */
    private volatile String currentRunId;

    /** 批次实时进度（内存态，重启即丢；已落库的结果不受影响） */
    private final ConcurrentHashMap<String, RunState> runStates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.orchestrator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rag-eval-orchestrator");
            t.setDaemon(true);
            return t;
        });
        this.casePool = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "rag-eval-case");
            t.setDaemon(true);
            return t;
        });

        // 上次进程被 kill 后遗留的 RUNNING 行必须收尾，否则报告里永远像「评估卡住了」
        try {
            int n = reportMapper.markRunningAsInterrupted();
            if (n > 0) {
                log.info("📊 已把 {} 条遗留的 RUNNING 评估记录标记为 INTERRUPTED", n);
            }
        } catch (Exception e) {
            log.warn("📊 清理遗留 RUNNING 记录失败: {}", e.getMessage());
        }

        log.info("📊 RAG 评估模块就绪: 并发度={} topK上限={}", parallelism, maxTopK);
    }

    @PreDestroy
    public void destroy() {
        if (orchestrator != null) orchestrator.shutdownNow();
        if (casePool != null) casePool.shutdownNow();
        log.info("📊 RAG 评估线程池已关闭");
    }

    // ======================== 对外：黄金集 ========================

    /**
     * 生成/补齐黄金集。
     *
     * <p>默认「补齐」语义：已有用例数达到 target 就原样复用，不重新出题 ——
     * 这是「改分块策略前后分数可比」的前提。{@code rebuild=true} 时先清空该集合用例再重生成，
     * 会破坏与该集合历史轮次的可比性，仅在知识库内容大幅变动后使用。</p>
     *
     * @return 本次新增的用例数
     */
    public int buildGoldenSet(String collection, int sampleSize, boolean rebuild) {
        String collectionId = requireUsableCollection(collection);
        int total = chromaClient.count(collectionId);
        if (total <= 0) {
            // requireUsableCollection 已挡过一次，这里是防御性兜底
            throw new RagEvalException("集合 " + collection + " 文档数为 " + total + "，无法抽取出题");
        }

        if (rebuild) {
            int removed = caseMapper.deleteByCollection(collection);
            log.info("📊 已清空集合 {} 的 {} 条历史用例（rebuild=true，历史轮次不再可比）", collection, removed);
        }

        Set<String> existing = new HashSet<>(caseMapper.findHashesByCollection(collection));
        int target = Math.min(Math.max(1, sampleSize), total);
        if (!rebuild && existing.size() >= target) {
            log.info("📊 集合 {} 已有 {} 条用例（>= {}），直接复用", collection, existing.size(), target);
            return 0;
        }
        int need = target - existing.size();

        // 分层抽样：把 [0,total) 均分成 need 段，每段随机取一个 offset。
        // 比「纯随机 offset」好在两点：① 调用次数有界（<= need，而不是无界重试撞哈希）；
        // ② 强制铺满整个 ID 空间 —— chunk 的 id 是「文件名_序号」连续追加的，
        //    纯随机容易让黄金集被某一篇文档的连续 chunk 垄断，指标就只反映那一篇的检索质量。
        Random rnd = new Random();
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < need; i++) {
            int lo = (int) ((long) i * total / need);
            int hi = (int) ((long) (i + 1) * total / need) - 1;
            offsets.add(lo + rnd.nextInt(Math.max(1, hi - lo + 1)));
        }
        Collections.shuffle(offsets, rnd);

        int created = 0;
        int failed = 0;
        for (int offset : offsets) {
            if (created >= need) break;
            try {
                List<Map<String, Object>> got = chromaClient.getDocuments(collectionId, 1, offset);
                if (got.isEmpty()) {
                    // offset 越界时 Chroma 静默返回空页（HTTP 200 而非报错）。
                    // 必须显式跳过，否则 count 与实际不一致时会静默少抽几条却当成成功
                    failed++;
                    continue;
                }
                Object content = got.get(0).get("content");
                if (content == null) continue;
                String chunk = String.valueOf(content);
                if (chunk.isBlank()) continue;

                String hash = sha256(chunk);
                if (existing.contains(hash)) continue;

                RagEvalJudge.GoldenCase gc = judge.generateCaseFromChunk(chunk);
                if (gc == null) {
                    failed++;
                    continue;
                }

                RagEvalCaseEntity entity = new RagEvalCaseEntity();
                entity.setCollection(collection);
                entity.setQuestion(gc.question);
                entity.setGroundTruth(gc.groundTruth);
                entity.setSourceChunk(chunk);
                entity.setChunkHash(hash);
                caseMapper.insert(entity);

                existing.add(hash);
                created++;
            } catch (Exception e) {
                // 单条失败不影响整体：可能是重复哈希撞唯一键，也可能是一次 LLM 抖动
                failed++;
                log.warn("📊 生成用例失败(offset={}): {}", offset, e.getMessage());
            }
        }
        int nowTotal = caseMapper.countByCollection(collection);
        log.info("📊 黄金集生成完成: collection={} 新增={} 失败={} 现有总数={}",
                collection, created, failed, nowTotal);
        if (created < need) {
            // 不比 need 少就静默通过：否则「说好 20 条实际只有 7 条」会一路带到报告里
            log.warn("📊 黄金集未达预期：期望新增 {} 条，实际新增 {} 条（现总数 {}/{}）",
                    need, created, nowTotal, target);
        }
        return created;
    }

    // ======================== 对外：触发评估 ========================

    /**
     * 触发一轮评估（异步）。
     *
     * <p>集合可用性校验<b>在提交异步任务之前同步完成</b>：否则「集合为空」会变成一个
     * HTTP 200 + 空报告，让人以为模块坏了。</p>
     *
     * @return runId，供后续轮询 /report
     * @throws IllegalArgumentException 参数非法（采集名为空、topK 越界等）→ 调用方返回 400
     * @throws RagEvalException        集合为空 / Chroma 不可达 → 调用方返回 500
     * @throws RagEvalBusyException    已有批次在跑 → 调用方返回 409
     */
    public String startRun(String collection, int topK, int sampleSize, boolean rebuildGoldenSet) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection 不能为空");
        }
        if (topK < 1 || topK > maxTopK) {
            throw new IllegalArgumentException("top_k 必须在 1~" + maxTopK + " 之间"
                    + "（上限来自 ChromaClient 既有实现 n_results=Math.min(topK,20)，"
                    + "配更大值会被静默截断，所以这里直接拒绝）");
        }
        if (sampleSize < 1) {
            throw new IllegalArgumentException("sample_size 必须大于 0");
        }
        String collectionId = requireUsableCollection(collection);

        if (!running.compareAndSet(false, true)) {
            throw new RagEvalBusyException("已有评估任务在运行中：run_id=" + currentRunId
                    + "，请等它跑完或用 /api/eval/report 查看进度");
        }

        String runId = "eval_" + LocalDateTime.now().format(RUN_ID_FMT) + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        currentRunId = runId;
        RunState st = new RunState(runId, collection);
        runStates.put(runId, st);

        orchestrator.submit(() -> {
            try {
                runBatch(runId, st, collection, collectionId, topK, sampleSize, rebuildGoldenSet);
            } catch (Exception e) {
                st.error = e.getMessage();
                log.error("📊 评估批次 {} 异常终止: {}", runId, e.getMessage(), e);
            } finally {
                st.running = false;
                st.finishedAt = LocalDateTime.now();
                running.set(false);
                log.info("📊 评估批次 {} 结束: 总数={} 完成={} 失败={}",
                        runId, st.total, st.done, st.failed);
            }
        });
        return runId;
    }

    /**
     * 重跑某批次中未成功的用例，<b>写回原行</b>（不新建批次）。
     * 这样该批次的聚合指标会变得完整，而不是留下一批 FAILED 永远拉低均值。
     *
     * @return 实际重跑的用例数
     */
    public int retry(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id 不能为空");
        }
        List<RagEvalReportEntity> rows = reportMapper.findRetryable(runId);
        if (rows.isEmpty()) {
            return 0;
        }
        String collection = rows.get(0).getCollection();
        if (!running.compareAndSet(false, true)) {
            throw new RagEvalBusyException("已有评估任务在运行中：run_id=" + currentRunId);
        }
        String collectionId = requireUsableCollection(collection);

        RunState st = runStates.get(runId);
        if (st == null) {
            st = new RunState(runId, collection);
            runStates.put(runId, st);
        }
        final RunState state = st;
        state.total = rows.size();
        state.done = 0;
        state.failed = 0;
        state.running = true;

        orchestrator.submit(() -> {
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (RagEvalReportEntity row : rows) {
                    tasks.add(() -> {
                        // 用该行自己记录的 topK，而不是重新取一个默认值 ——
                        // 重跑必须与首次同参数，否则重跑出来的分数和同批次其他行不可比
                        rerun(row, collectionId);
                        return null;
                    });
                }
                invokeAll(tasks);
            } catch (Exception e) {
                state.error = e.getMessage();
                log.error("📊 重跑批次 {} 异常: {}", runId, e.getMessage(), e);
            } finally {
                state.running = false;
                state.finishedAt = LocalDateTime.now();
                running.set(false);
            }
        });
        return rows.size();
    }

    // ======================== 内部：批次执行 ========================

    private void runBatch(String runId, RunState st, String collection, String collectionId,
                          int topK, int sampleSize, boolean rebuildGoldenSet) {
        st.running = true;
        // 黄金集不在则先补齐（这一步含 LLM 调用，所以放在异步里）
        buildGoldenSet(collection, sampleSize, rebuildGoldenSet);

        List<RagEvalCaseEntity> cases = caseMapper.findByCollection(collection, sampleSize);
        if (cases.isEmpty()) {
            throw new RagEvalException("集合 " + collection + " 没有可用的评估用例");
        }
        st.total = cases.size();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (RagEvalCaseEntity c : cases) {
            tasks.add(() -> {
                insertAndEvaluate(runId, collection, collectionId, topK, c, st);
                return null;
            });
        }
        invokeAll(tasks);
    }

    /** 统一提交并等待；单任务异常已在任务体内部消化，这里只保证不中断其他任务 */
    private void invokeAll(List<Callable<Void>> tasks) {
        try {
            List<Future<Void>> futures = casePool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    log.warn("📊 用例任务异常: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("📊 用例批次被中断");
        }
    }

    /** 新建报告行（先落 RUNNING 让 /report 能看到进度）→ 评估 → 回写 */
    private void insertAndEvaluate(String runId, String collection, String collectionId,
                                   int topK, RagEvalCaseEntity c, RunState st) {
        RagEvalReportEntity row = new RagEvalReportEntity();
        row.setRunId(runId);
        row.setCollection(collection);
        row.setTopK(topK);
        row.setQuestion(c.getQuestion());
        row.setGroundTruth(c.getGroundTruth());
        row.setStatus(RagEvalReportEntity.STATUS_RUNNING);
        try {
            reportMapper.insert(row);
        } catch (Exception e) {
            log.warn("📊 写入评估行失败: {}", e.getMessage());
            st.failed++;
            st.done++;
            return;
        }
        evaluateInto(row, collectionId, topK);
        try {
            reportMapper.updateById(row);
        } catch (Exception e) {
            log.warn("📊 回写评估结果失败: {}", e.getMessage());
        }
        st.done++;
        if (RagEvalReportEntity.STATUS_FAILED.equals(row.getStatus())) st.failed++;
    }

    /** 复用已有报告行重跑（retry 路径），沿用该行自己记录的 topK */
    private void rerun(RagEvalReportEntity row, String collectionId) {
        RunState st = runStates.get(row.getRunId());
        int topK = row.getTopK() != null ? row.getTopK() : 5;
        row.setStatus(RagEvalReportEntity.STATUS_RUNNING);
        row.setErrorMsg("");
        try {
            reportMapper.updateById(row);
        } catch (Exception e) {
            log.warn("📊 重跑前更新状态失败: {}", e.getMessage());
        }
        evaluateInto(row, collectionId, topK);
        try {
            reportMapper.updateById(row);
        } catch (Exception e) {
            log.warn("📊 重跑结果回写失败: {}", e.getMessage());
        }
        if (st != null) {
            st.done++;
            if (RagEvalReportEntity.STATUS_FAILED.equals(row.getStatus())) st.failed++;
        }
    }

    /**
     * 单个用例的完整流水线，把结果写进 row（不落库）。
     *
     * <p>注意「检索不到 context」<b>不记为失败</b>：照常交给裁判，三项指标自然得 0。
     * 因为「这条问题检索不到」本身就是被评估出来的结果，比一个 FAILED 更有信息量。
     * 只有 embedding 失败、LLM 调用失败、裁判 JSON 解析失败才记 FAILED。</p>
     */
    private void evaluateInto(RagEvalReportEntity row, String collectionId, int topK) {
        long t0 = System.currentTimeMillis();
        try {
            String question = row.getQuestion();

            List<Float> emb = embeddingService.generateEmbedding(question);
            if (emb == null || emb.isEmpty()) {
                throw new IllegalStateException("embedding 生成失败（DashScope 不可用或超限）");
            }
            List<Double> embDouble = embeddingService.toDoubleList(emb);

            List<Map<String, Object>> hits = chromaClient.queryDetail(collectionId, embDouble, topK);
            List<String> contexts = new ArrayList<>();
            for (Map<String, Object> h : hits) {
                Object content = h.get("content");
                if (content != null && !String.valueOf(content).isBlank()) {
                    contexts.add(String.valueOf(content));
                }
            }
            row.setRetrievedContexts(toJson(contexts));
            row.setJudgeModel(judge.getModelName());
            row.setPromptVersion(RagEvalJudge.PROMPT_VERSION);

            if (contexts.isEmpty()) {
                // 一条都没召回：不交给裁判。否则答案必然是「资料中未提及」，
                // 而 prompt 对拒答给固定分，会得出「检索越烂、faithfulness 越高」的倒挂结论。
                // contextRecall 记 0 是合法的（确实没覆盖任何事实点）；
                // faithfulness / factual_correctness 记 NULL 表示「无定义」而非 0 分，
                // MySQL 的 AVG() 会自动跳过 NULL，不会被算进均值。
                row.setContextRecall(score(0.0));
                row.setFaithfulness(null);
                row.setFactualCorrectness(null);
                row.setJudgeDetail("{\"note\":\"检索为空，faithfulness / factual_correctness 记为 NULL（无定义）\"}");
                row.setStatus(RagEvalReportEntity.STATUS_SUCCESS);
                row.setErrorMsg("");
                return;
            }

            String answer = judge.generateAnswer(question, contexts);
            if (answer == null) {
                throw new IllegalStateException("生成答案失败（LLM 返回空或调用异常）");
            }
            row.setAnswer(answer);

            RagEvalJudge.JudgeResult jr = judge.judge(question, row.getGroundTruth(), contexts, answer);
            if (jr == null) {
                throw new IllegalStateException("裁判打分失败（LLM 返回空或 JSON 解析失败）");
            }
            row.setContextRecall(score(jr.contextRecall));
            row.setFaithfulness(score(jr.faithfulness));
            row.setFactualCorrectness(score(jr.factualCorrectness));
            row.setJudgeDetail(jr.rawJson);

            row.setStatus(RagEvalReportEntity.STATUS_SUCCESS);
            row.setErrorMsg("");
        } catch (Exception e) {
            row.setStatus(RagEvalReportEntity.STATUS_FAILED);
            row.setErrorMsg(truncate(e.getMessage(), 900));
        } finally {
            row.setDurationMs(System.currentTimeMillis() - t0);
        }
    }

    // ======================== 内部：查询与工具 ========================

    /** 查批次进度：优先内存态，内存没有（重启后）则用库里聚合兜底 */
    public Map<String, Object> getProgress(String runId) {
        RunState st = runStates.get(runId);
        if (st != null) return st.toMap();
        Map<String, Object> agg = reportMapper.aggregateByRun(runId);
        if (agg == null) return null;
        Map<String, Object> m = new LinkedHashMap<>(agg);
        m.put("running", false);
        m.put("progressNote", "实时进度已随进程重启丢失，以下为库中已完成的结果");
        return m;
    }

    public List<Map<String, Object>> listRecentRuns(int limit) {
        return reportMapper.listRecentRuns(limit);
    }

    public Map<String, Object> aggregateByRun(String runId) {
        return reportMapper.aggregateByRun(runId);
    }

    public List<RagEvalReportEntity> findReports(String runId, int limit) {
        return reportMapper.findByRunId(runId, limit);
    }

    public List<RagEvalCaseEntity> findCases(String collection, int limit) {
        return caseMapper.findByCollection(collection, limit);
    }

    public int countCases(String collection) {
        return caseMapper.countByCollection(collection);
    }

    /**
     * 校验集合可用并返回 collectionId。
     *
     * <p>用只读的 {@code findCollectionId} 而不是 {@code getOrCreateCollection}：后者在集合不存在时
     * 会<b>顺手把集合建出来</b>，于是「集合不存在」和「集合存在但为空」在调用方看来都是「count=0」，
     * 报错文案就没法指对方向。这里把三种状态分开报：不可达 / 不存在 / 为空。</p>
     */
    private String requireUsableCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection 不能为空");
        }
        String collectionId;
        try {
            collectionId = chromaClient.findCollectionId(collection);
        } catch (Exception e) {
            // findCollectionId 只吞 404，连接类异常会抛出来 —— 这正是用来区分「不可达」的信号
            throw new RagEvalException("Chroma 不可达，无法确认集合 " + collection + "：" + e.getMessage());
        }
        if (collectionId == null) {
            throw new RagEvalException("集合 " + collection + " 不存在。"
                    + "请先向对应知识目录放入文档并重启触发索引"
                    + "（旅行知识库：data/travel-knowledge/；法律：data/law-knowledge/）");
        }
        int total = chromaClient.count(collectionId);
        if (total < 0) {
            throw new RagEvalException("集合 " + collection + " 存在但读不到文档数，请确认 Chroma 服务可达");
        }
        if (total == 0) {
            throw new RagEvalException("集合 " + collection + " 文档数为 0，无法评估。"
                    + "请先向对应知识目录放入文档并重启触发索引"
                    + "（旅行知识库：data/travel-knowledge/；法律：data/law-knowledge/）");
        }
        return collectionId;
    }

    private static BigDecimal score(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 一定存在；退化为长度+哈希码，仅用于去重，不用于安全
            return "fallback_" + text.length() + "_" + text.hashCode();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ======================== 批次进度 ========================

    /** 批次的实时进度 */
    public static class RunState {
        private final String runId;
        private final String collection;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile LocalDateTime finishedAt;
        private volatile int total;
        private volatile int done;
        private volatile int failed;
        private volatile boolean running = true;
        private volatile String error;

        public RunState(String runId, String collection) {
            this.runId = runId;
            this.collection = collection;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("collection", collection);
            m.put("running", running);
            m.put("total", total);
            m.put("done", done);
            m.put("failed", failed);
            m.put("startedAt", startedAt);
            m.put("finishedAt", finishedAt);
            if (error != null) m.put("error", error);
            return m;
        }
    }
}
