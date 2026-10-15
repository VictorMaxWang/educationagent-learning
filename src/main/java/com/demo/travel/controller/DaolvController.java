package com.demo.travel.controller;

import com.demo.mcp.McpClientManager;
import com.demo.mcp.McpToolDefinition;
import com.demo.mcp.daolv.DaolvFlightMcpSource;
import com.demo.mcp.daolv.DaolvMcpSource;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 道旅（Daolv）出行服务接口 — {@code /api/desktop/daolv/*}。
 * <p>
 * 代理道旅酒店/机票 MCP 工具（SearchHotels / searchFlights / searchAirports / GetHotelDetail 等），
 * 供桌面端「出行 → 旅游计划」的酒店/机票搜索区调用。工具与参数 schema 由 MCP Server 动态发现。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/daolv")
public class DaolvController {

    @Resource
    private McpClientManager mcpManager;

    @Resource
    private DaolvMcpSource daolvSource;

    @Resource
    private DaolvFlightMcpSource daolvFlightSource;

    /** 工具列表（道旅酒店 + 机票，含输入 schema 供前端动态渲染表单） */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> out = ok();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpToolDefinition def : mcpManager.getAllTools()) {
            String src = def.getSourceName();
            if (!"daolv".equals(src) && !"daolv-flight".equals(src)) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("source", src);
            t.put("name", def.getName());
            t.put("qualifiedName", def.qualifiedName());
            t.put("description", def.getDescription());
            t.put("inputSchema", def.getInputSchema());
            t.put("required", def.getRequired());
            tools.add(t);
        }
        out.put("tools", tools);
        return out;
    }

    /** 调用道旅工具（tool 传原始名或 qualifiedName，args 为参数对象） */
    @PostMapping("/call")
    public Map<String, Object> call(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return error("请求体为空");
        }
        String tool = str(body.get("tool"));
        if (tool.isBlank()) {
            return error("请指定工具名");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        Object argsObj = body.get("args");
        if (argsObj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) args.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        String result = mcpManager.callTool(tool, args);
        Map<String, Object> out = ok();
        out.put("result", result);
        return out;
    }

    /** 就绪状态（前端据此提示未就绪原因：未配置 token / 初始化失败详情） */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = ok();
        out.put("hotelReady", daolvSource.isReady());
        out.put("flightReady", daolvFlightSource.isReady());
        out.put("hotelError", daolvSource.getLastError());
        out.put("flightError", daolvFlightSource.getLastError());
        return out;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        return out;
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 500);
        out.put("msg", msg);
        return out;
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return s.isBlank() ? "" : s.trim();
    }
}
