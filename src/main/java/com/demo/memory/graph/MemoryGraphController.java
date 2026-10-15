package com.demo.memory.graph;

import com.demo.memory.MemoryProperties;
import com.demo.memory.MemoryRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆脑图 REST 接口，供桌面端「记忆脑图」页面调用。
 *
 * <p><b>返回体是本模块特有的一套：</b>{@code {success, data, timestamp}}（失败时
 * {@code {success:false, msg, code, timestamp}}）——按方案书对脑图接口的规定。
 * 注意这与项目主流的 {@code {code, msg, data}} 不同（那套有 19 个控制器在用），
 * 属于本接口的既定契约，不要顺手"统一"回去：前端 {@code ops/opsApi.js} 已同时兼容两种。
 *
 * <p>路径落在 {@code /api/**} 下，已被 {@code CorsConfig} 放行。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryGraphController {

    private final MemoryGraphService graphService;
    private final MemoryRagService memoryRagService;
    private final MemoryProperties properties;

    public MemoryGraphController(MemoryGraphService graphService,
                                 MemoryRagService memoryRagService,
                                 MemoryProperties properties) {
        this.graphService = graphService;
        this.memoryRagService = memoryRagService;
        this.properties = properties;
    }

    /** 记忆脑图数据。 */
    @GetMapping("/graph")
    public Map<String, Object> graph(@RequestParam(value = "userId", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return err(400, "userId 不能为空");
        }
        try {
            return ok(graphService.build(userId));
        } catch (Exception e) {
            log.error("【记忆库-脑图】构造失败 userId={}", userId, e);
            return err(500, "记忆脑图构造失败：" + e.getMessage());
        }
    }

    /** 健康与统计：前端用来显示"当前有多少记忆"。 */
    @GetMapping("/health")
    public Map<String, Object> health(@RequestParam(value = "userId", required = false) String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeEnabled", properties.getStore().isEnabled());
        data.put("ragEnabled", properties.getRag().isEnabled());
        data.put("hybridEnabled", properties.getHybrid().isEnabled());
        data.put("integrationEnabled", properties.getIntegration().isEnabled());
        data.put("usable", memoryRagService.isUsable());
        data.put("totalMemories", memoryRagService.totalCount());
        data.put("totalUsers", memoryRagService.countUsers());
        data.put("indexPath", properties.getStore().getIndexPath());
        data.put("dimension", properties.getStore().getDimension());
        if (userId != null && !userId.isBlank()) {
            data.put("userMemories", memoryRagService.count(userId));
        }
        return ok(data);
    }

    /**
     * 清理某用户的记忆。<b>默认是安全模式（只删重复）</b>。
     *
     * <table>
     *   <tr><td>{@code {"userId": "..."}}</td>
     *       <td><b>只删近似重复</b>的记忆，每组保留最有价值的一条 —— 默认</td></tr>
     *   <tr><td>{@code {"userId": "...", "wipeAll": true}}</td>
     *       <td><b>清空全部</b>，不可恢复</td></tr>
     * </table>
     *
     * <p><b>为什么默认是"只删重复"而不是"清空"：</b>这条路踩过坑——
     * 调用方只想把几条重复清掉，发了 {@code {"userId": ...}}，结果连不该删的一起没了。
     * 破坏性操作必须让调用方<b>明确写出 {@code wipeAll: true}</b> 才执行，
     * 而不能靠"没写别的参数"来推断。名字里带 clear 就当清空用，是这里最容易犯的错。
     *
     * <p>用 POST 而不是 DELETE：本项目没有鉴权体系，一个可被 GET 触发的
     * "清空全部记忆"很容易被误触（比如浏览器预取）。
     */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return err(400, "请求体不能为空");
        Object uid = body.get("userId");
        if (uid == null || String.valueOf(uid).isBlank()) {
            return err(400, "userId 不能为空");
        }
        String userId = String.valueOf(uid);

        Object wipe = body.get("wipeAll");
        boolean wipeAll = Boolean.TRUE.equals(wipe)
                || "true".equalsIgnoreCase(String.valueOf(wipe));

        int deleted = wipeAll
                ? memoryRagService.deleteAll(userId)
                : memoryRagService.deleteDuplicates(userId);

        // 必须让缓存失效，否则清空后最长 10 秒内前端还会看到那张旧图
        graphService.evict(userId);
        log.info("【记忆库-脑图】用户 {} {}：删除 {} 条",
                userId, wipeAll ? "⚠️ 清空全部记忆" : "去重压缩（安全模式）", deleted);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        data.put("mode", wipeAll ? "wipeAll" : "dedupeOnly");
        return ok(data);
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        m.put("timestamp", System.currentTimeMillis());
        return m;
    }

    /**
     * 失败响应。保留数字 {@code code} 便于排查（400 / 500），
     * 但判定依据是 {@code success:false}——前端只关心成没成。
     */
    private static Map<String, Object> err(int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("msg", msg);
        m.put("data", null);
        m.put("timestamp", System.currentTimeMillis());
        return m;
    }
}
