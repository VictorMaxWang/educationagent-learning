package com.demo.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息全链路追踪上下文
 * <p>
 * 职责：为每一条用户消息生成唯一 traceId，贯穿整个处理流程，
 * 在每个关键节点记录时间戳 + 耗时 + 数据快照，最终输出 JSON 追踪文件。
 * <p>
 * 使用方式（与 VoiceContext 同模式，ThreadLocal 传递）：
 * <pre>
 *   TraceContext.begin(userId);       // 消息入口处
 *   TraceContext.span("预处理", data); // 每个关键节点
 *   TraceContext.end("success");      // 消息处理完毕
 * </pre>
 * <p>
 * 输出：data/traces/{yyyyMMdd}/{traceId}.json
 *       同时通过 log.info 输出到控制台，节点耗时一目了然。
 * <p>
 * 设计原则：零额外依赖（SLF4J + fastjson2 + JDK 已有），
 *           不修改任何现有方法签名，不侵入业务逻辑。
 */
@Slf4j
public class TraceContext {

    private static final String TRACE_DIR = "data/traces";
    private static final ThreadLocal<TraceEntry> CURRENT = new ThreadLocal<>();
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trace-writer");
        t.setDaemon(true);
        return t;
    });

    /** 清理节流：最多每小时执行一次 */
    private static final AtomicLong lastCleanupMs = new AtomicLong(0);
    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;
    private static final int MAX_TRACE_DAYS = 3;

    // ======================== 公开 API ========================

    /**
     * 开始追踪一条用户消息
     * 生成唯一 traceId，记录起始时间
     */
    public static String begin(String userId) {
        TraceEntry entry = new TraceEntry();
        entry.traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        entry.userId = userId;
        entry.startTime = System.currentTimeMillis();
        entry.spans = new ArrayList<>();

        // 记录入口 Span
        entry.spans.add(new TraceSpan("entry", 0, "收到消息"));

        CURRENT.set(entry);
        log.info("[Trace:{}] ⏱️ [{}] 开始追踪", entry.traceId, userId);
        return entry.traceId;
    }

    /**
     * 记录一个追踪节点
     * @param name  节点名称（如 "预处理图片"、"LLM 第1轮"、"调用天气"、"发送回复"）
     * @param data  节点数据快照（如图片大小、LLM 回复截断、工具调用参数）
     */
    public static void span(String name, String data) {
        TraceEntry entry = CURRENT.get();
        if (entry == null) return;

        long now = System.currentTimeMillis();
        long elapsed = now - entry.startTime;
        entry.spans.add(new TraceSpan(name, elapsed, data));

        String indent = "  ".repeat(Math.min(entry.spans.size() - 1, 5));
        log.info("[Trace:{}] {}{} │ +{}ms │ {}", entry.traceId, indent, name, elapsed,
                data != null ? truncate(data, 60) : "");
    }

    /**
     * 记录节点（不带额外数据）
     */
    public static void span(String name) {
        span(name, null);
    }

    /**
     * 结束追踪，输出追踪文件
     * @param status 状态：success / error / timeout
     */
    public static void end(String status) {
        TraceEntry entry = CURRENT.get();
        if (entry == null) return;

        entry.endTime = System.currentTimeMillis();
        entry.duration = entry.endTime - entry.startTime;
        entry.status = status;

        // 控制台输出汇总
        long total = entry.duration;
        log.info("[Trace:{}] ⏱️ [{}] 追踪结束，总计 {}ms，状态={}",
                entry.traceId, entry.userId, total, status);

        // 异步写入追踪文件
        IO_EXECUTOR.submit(() -> writeTraceFile(entry));

        CURRENT.remove();
    }

    /**
     * 结束追踪（默认 success）
     */
    public static void end() {
        end("success");
    }

    /**
     * 获取当前 traceId（null 表示不在追踪中）
     */
    public static String getTraceId() {
        TraceEntry entry = CURRENT.get();
        return entry != null ? entry.traceId : null;
    }

    // ======================== 内部实现 ========================

    /**
     * 自动清理超过 MAX_TRACE_DAYS 天的旧追踪数据
     * 按日期目录删除，每个目录代表一天的全部追踪记录
     * 内置节流：最多每小时执行一次实际扫描+删除
     */
    private static void cleanupOldTraces() {
        long now = System.currentTimeMillis();
        long last = lastCleanupMs.get();
        if (now - last < CLEANUP_INTERVAL_MS) return;
        if (!lastCleanupMs.compareAndSet(last, now)) return; // CAS 保证单线程执行

        try {
            Path traceRoot = Paths.get(TRACE_DIR);
            if (!Files.exists(traceRoot)) return;

            LocalDate cutoff = LocalDate.now().minusDays(MAX_TRACE_DAYS);
            int deletedDirs = 0;

            try (var dirs = Files.list(traceRoot)) {
                List<Path> toDelete = dirs
                        .filter(Files::isDirectory)
                        .filter(dir -> {
                            try {
                                LocalDate dirDate = LocalDate.parse(
                                        dir.getFileName().toString(),
                                        DateTimeFormatter.ofPattern("yyyyMMdd"));
                                return dirDate.isBefore(cutoff);
                            } catch (DateTimeParseException e) {
                                return false; // 非日期命名的目录跳过
                            }
                        })
                        .toList();

                for (Path dir : toDelete) {
                    deleteRecursively(dir);
                    deletedDirs++;
                }
            }

            if (deletedDirs > 0) {
                log.info("🧹 清理旧追踪数据：删除 {} 个日期目录（保留最近 {} 天）", deletedDirs, MAX_TRACE_DAYS);
            }
        } catch (Exception e) {
            log.warn("清理旧追踪数据异常: {}", e.getMessage());
        }
    }

    /** 递归删除目录及其内容 */
    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    /**
     * 将追踪记录写入 JSON 文件
     * 路径：data/traces/{yyyyMMdd}/{traceId}.json
     */
    private static void writeTraceFile(TraceEntry entry) {
        try {
            JSONObject root = new JSONObject();
            root.put("traceId", entry.traceId);
            root.put("userId", entry.userId);
            root.put("startTime", entry.startTime);
            root.put("endTime", entry.endTime);
            root.put("duration", entry.duration);
            root.put("status", entry.status);

            JSONArray spansArr = new JSONArray();
            for (TraceSpan span : entry.spans) {
                JSONObject s = new JSONObject();
                s.put("name", span.name);
                s.put("elapsed", span.elapsed);
                s.put("data", span.data);
                spansArr.add(s);
            }
            root.put("spans", spansArr);

            // 按日期分目录
            String dateDir = new java.text.SimpleDateFormat("yyyyMMdd")
                    .format(new java.util.Date(entry.startTime));
            Path dir = Paths.get(TRACE_DIR, dateDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            Path file = dir.resolve(entry.traceId + ".json");
            Files.writeString(file, root.toJSONString(true), StandardCharsets.UTF_8);
            log.debug("[Trace:{}] 追踪文件已写入：{}", entry.traceId, file);

            // 每次写入后尝试触发清理（内部有节流，实际最多每小时执行一次）
            cleanupOldTraces();
        } catch (Exception e) {
            log.warn("[Trace:{}] 写入追踪文件失败: {}", entry.traceId, e.getMessage());
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ======================== 内部数据结构 ========================

    private static class TraceEntry {
        private String traceId;
        private String userId;
        private long startTime;
        private long endTime;
        private long duration;
        private String status;
        private List<TraceSpan> spans;
    }

    /** 单个追踪节点：名称 + 相对起始的累积耗时 + 数据 */
    public record TraceSpan(String name, long elapsed, String data) {}
}
