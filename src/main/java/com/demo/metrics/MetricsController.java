package com.demo.metrics;

import com.demo.memory.MemoryRagService;
import com.demo.memory.store.LuceneMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务指标快照接口，供桌面端「指标监控面板」调用。
 *
 * <p>与 {@code /actuator/prometheus} 的分工：
 * <ul>
 *   <li>{@code /actuator/prometheus} —— 给 Prometheus 抓取的标准指标（含 JVM/HTTP 等基础设施指标）；</li>
 *   <li>{@code /api/metrics/snapshot} —— 给前端直接渲染的<b>业务</b>快照，
 *       把分散的计数器整理成"LLM 花了多少 token、工具调了多少次、路由命中率多少"这种
 *       一眼能看懂的形状，前端不需要懂 Prometheus 的命名规范。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final BusinessMetrics metrics;
    private final MemoryRagService memoryRagService;
    private final LuceneMemoryStore memoryStore;

    public MetricsController(BusinessMetrics metrics,
                             MemoryRagService memoryRagService,
                             LuceneMemoryStore memoryStore) {
        this.metrics = metrics;
        this.memoryRagService = memoryRagService;
        this.memoryStore = memoryStore;
    }

    /** 业务指标快照。 */
    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.putAll(metrics.snapshot());

            // 记忆库的运行态：与计数器互补——计数器说"检索了多少次"，
            // 这里说"库里现在存了多少"，两者都要有才能判断系统是不是在正常工作
            Map<String, Object> store = new LinkedHashMap<>();
            store.put("usable", memoryRagService.isUsable());
            store.put("totalMemories", memoryRagService.totalCount());
            store.put("totalUsers", memoryRagService.countUsers());
            store.put("indexAvailable", memoryStore.isAvailable());
            data.put("memoryStore", store);

            data.put("jvm", jvmSnapshot());
            data.put("process", processSnapshot());
        } catch (Exception e) {
            log.warn("指标快照构造失败：{}", e.toString());
        }
        return ok(data);
    }

    /**
     * 内存里的指标名清单。排查"埋点到底有没有生效"时用——
     * 如果某个指标没出现在这里，说明那段代码根本没被跑到。
     */
    @GetMapping("/meters")
    public Map<String, Object> meters() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("names", metrics.registeredMeterNames());
        return ok(data);
    }

    /** 记忆脑图页面的健康数据复用同一份来源，这里给一个便捷别名。 */
    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam(value = "userId", required = false) String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("usable", memoryRagService.isUsable());
        data.put("totalMemories", memoryRagService.totalCount());
        data.put("totalUsers", memoryRagService.countUsers());
        if (userId != null && !userId.isBlank()) {
            data.put("userMemories", memoryRagService.count(userId));
        }
        return ok(data);
    }

    // ======================== JVM / 进程 ========================

    private Map<String, Object> jvmSnapshot() {
        Map<String, Object> jvm = new LinkedHashMap<>();
        try {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();
            jvm.put("heapUsedMb", mb(used));
            jvm.put("heapMaxMb", mb(max));
            jvm.put("heapUsedPercent", max == 0 ? 0.0 : Math.round(used * 1000.0 / max) / 10.0);
            jvm.put("availableProcessors", rt.availableProcessors());

            ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            jvm.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        } catch (Exception e) {
            jvm.put("error", e.getMessage());
        }
        return jvm;
    }

    private Map<String, Object> processSnapshot() {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            p.put("uptimeMs", uptimeMs);
            p.put("uptimeMinutes", uptimeMs / 60_000);
        } catch (Exception e) {
            p.put("error", e.getMessage());
        }
        return p;
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 200);
        m.put("msg", "ok");
        m.put("data", data);
        return m;
    }
}
