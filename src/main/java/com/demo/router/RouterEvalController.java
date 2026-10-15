package com.demo.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由评测 REST 接口，供桌面端「路由评测 Dashboard」调用。
 *
 * <p>返回体沿用本项目既有约定：手搓 {@code LinkedHashMap} 装 {@code {code, msg, data}}。
 * 本项目<b>没有</b>统一的 {@code Result} 类，也没有 {@code @ControllerAdvice}，
 * 所以错误码在这里就地约定：200 正常 / 400 参数错 / 500 内部错
 * （与 {@code chroma.eval.RagEvalController} 保持一致）。
 *
 * <p>路径落在 {@code /api/**} 前缀下，因此已被 {@code CorsConfig} 放行，无需额外配置。
 */
@Slf4j
@RestController
@RequestMapping("/api/router")
public class RouterEvalController {

    private final RouterHealthPrinter healthPrinter;
    private final RouterEvalRecorder recorder;
    private final HybridRouter hybridRouter;
    private final IntentTfIdfStore tfIdfStore;

    public RouterEvalController(RouterHealthPrinter healthPrinter,
                                RouterEvalRecorder recorder,
                                HybridRouter hybridRouter,
                                IntentTfIdfStore tfIdfStore) {
        this.healthPrinter = healthPrinter;
        this.recorder = recorder;
        this.hybridRouter = hybridRouter;
        this.tfIdfStore = tfIdfStore;
    }

    /** 各层开关、阈值、L2 模型状态、熔断器状态。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return ok(healthPrinter.snapshot());
    }

    /** 命中率 / FALLBACK 率 / 各决策与各层分布 / 平均耗时与平均省下的工具数。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return ok(recorder.stats());
    }

    /** 最近若干条路由记录（时间倒序，来自内存 ring buffer）。 */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ok(recorder.recent(limit));
    }

    /** 意图 → 工具映射表，供 Dashboard 展示"每个意图会收窄到哪些工具"。 */
    @GetMapping("/labels")
    public Map<String, Object> labels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (IntentLabel label : IntentLabel.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", label.name());
            item.put("tools", label.tools());
            item.put("toolCount", label.tools().size());
            list.add(item);
        }
        return ok(list);
    }

    /**
     * 干跑一条消息，返回路由判定结果但<b>不写入评测日志</b>。
     * <p>用途：调阈值时反复试不同说法，不会污染线上统计。
     */
    @PostMapping("/route")
    public Map<String, Object> dryRun(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return err(400, "请求体不能为空");
        }
        String message = str(body.get("message"));
        if (message == null || message.isBlank()) {
            return err(400, "message 不能为空");
        }
        String userId = body.get("userId") == null ? "router-eval" : str(body.get("userId"));
        MessageContext ctx = "DESKTOP".equalsIgnoreCase(str(body.get("channel")))
                ? MessageContext.desktop() : MessageContext.wechat();

        RouteResult r = hybridRouter.dryRun(userId, message, ctx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        data.put("layer", r.getLayer());
        data.put("decision", r.getDecision().name());
        data.put("intent", r.getIntent() == null ? null : r.getIntent().name());
        data.put("matchedRule", r.getMatchedRule());
        data.put("confidence", r.getConfidence());
        data.put("vectorSim", r.getVectorSim());
        data.put("costMs", r.getCostMs());
        // 三态语义原样透出：null 表示"不过滤/全量"，与空列表含义完全不同
        data.put("tools", r.getTools());
        data.put("toolsOmitted", r.getTools() == null);
        data.put("llmIntent", r.getLlmIntent());
        data.put("tokenUsed", r.getTokenUsed());
        data.put("features", r.getFeatures());
        return ok(data);
    }

    /** 重建 L2 意图库（改完 intent-seeds.json 不想重启时用）。 */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        try {
            tfIdfStore.rebuild();
            return ok(healthPrinter.snapshot());
        } catch (Exception e) {
            log.error("L2 意图库重建失败", e);
            return err(500, "重建失败：" + e.getMessage());
        }
    }

    // ======================== 返回体 ========================

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 200);
        m.put("msg", "ok");
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> err(int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg);
        m.put("data", null);
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
