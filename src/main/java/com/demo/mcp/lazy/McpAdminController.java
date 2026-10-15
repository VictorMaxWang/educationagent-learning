package com.demo.mcp.lazy;

import com.demo.mcp.McpClientManager;
import jakarta.annotation.Resource;
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
 * MCP 管理接口 — {@code /api/mcp/*}，前端显式控制 MCP 源的生命周期。
 * <p>
 * 懒加载模式下，前端可在此「提前预加载」常用源，也可对低频源按需加载/卸载，
 * 避免所有 MCP 连接与工具 schema 常驻启动与提示词。
 * <ul>
 *   <li>{@code GET  /api/mcp/sources}      → 全部源元信息 + 生命周期状态</li>
 *   <li>{@code POST /api/mcp/activate}     → 显式激活单个源（同步等待结果）</li>
 *   <li>{@code POST /api/mcp/deactivate}   → 显式卸载单个源</li>
 *   <li>{@code POST /api/mcp/preload}      → 批量提前预加载（异步）</li>
 *   <li>{@code POST /api/mcp/call}         → 通用工具调用（未激活源自动懒加载）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpAdminController {

    @Resource
    private McpClientManager manager;

    @Resource
    private McpLazyActivator activator;

    @Resource
    private McpLazyProperties lazyProps;

    /** 全部源的状态面板 */
    @GetMapping("/sources")
    public Map<String, Object> sources() {
        Map<String, Object> out = ok();
        out.put("lazyEnabled", lazyProps.isEnabled());
        out.put("sources", manager.getStatus());
        return out;
    }

    /** 显式激活单个源（同步） */
    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestParam String source) {
        boolean ok = activator.activate(source);
        if (ok) {
            return result(200, "✅ " + source + " 已激活，工具可调用");
        }
        String err = manager.lastError(source);
        return result(500, "❌ " + source + " 激活失败：" + (err != null ? err : "未知原因"));
    }

    /** 显式卸载单个源 */
    @PostMapping("/deactivate")
    public Map<String, Object> deactivate(@RequestParam String source) {
        boolean ok = activator.deactivate(source);
        return ok ? result(200, "🔌 " + source + " 已卸载")
                : result(500, "❌ " + source + " 卸载失败或源不存在");
    }

    /**
     * 批量提前预加载（异步）。
     * body: {"sources": ["didi", "antvchart"]} 或 {"source": "didi"}
     */
    @PostMapping("/preload")
    public Map<String, Object> preload(@RequestBody(required = false) Map<String, Object> body) {
        List<String> names = new ArrayList<>();
        if (body != null) {
            Object arr = body.get("sources");
            if (arr instanceof List<?> l) {
                for (Object o : l) {
                    if (o != null) names.add(String.valueOf(o).trim());
                }
            }
            Object single = body.get("source");
            if (single != null) names.add(String.valueOf(single).trim());
        }
        if (names.isEmpty()) {
            return result(400, "请提供 sources 列表（如 {\"sources\":[\"didi\"]}）");
        }
        List<String> started = activator.preloadAsync(names);
        return result(200, "预加载启动：" + started);
    }

    /** 通用工具调用（未激活源的工具首次调用自动懒加载） */
    @PostMapping("/call")
    public Map<String, Object> call(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) return result(400, "请求体为空");
        String tool = str(body.get("tool"));
        if (tool.isBlank()) return result(400, "请指定工具名");
        Map<String, Object> args = new LinkedHashMap<>();
        Object argsObj = body.get("args");
        if (argsObj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) args.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        String r = manager.callTool(tool, args);
        Map<String, Object> out = ok();
        out.put("result", r);
        return out;
    }

    // ======================== 工具方法 ========================

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        return out;
    }

    private static Map<String, Object> result(int code, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("msg", msg);
        return out;
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return s.isBlank() ? "" : s.trim();
    }
}
