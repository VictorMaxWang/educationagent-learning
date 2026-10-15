package com.demo.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.agent.AgentExecutor;
import com.demo.entity.TaxiOrderEntity;
import com.demo.mapper.TaxiOrderMapper;
import com.demo.mcp.lazy.McpLoadState;
import com.demo.mcp.lazy.McpLoadStatus;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP 多源管理器 — 维护 toolName → McpSource 索引 + 生命周期状态机。
 * <p>
 * 懒加载设计（{@code mcp.lazy.enabled=true} 时生效）：
 * <ul>
 *   <li><b>register 与 init 解耦</b>：启动仅登记源引用（零网络），不连接、不发现工具</li>
 *   <li><b>激活即事务</b>：{@link #activate} 一次完成「连接 → 工具发现 → 注入 AgentExecutor」，
 *       由状态机 {@link McpLoadState} 驱动（UNLOADED→LOADING→READY/FAILED）</li>
 *   <li><b>首次调用兜底</b>：{@link #callTool} 遇到未激活源自动同步激活，前端/关键词漏判也不断链</li>
 * </ul>
 * 线程安全：所有索引与状态使用 ConcurrentHashMap；单源激活用 per-source 锁 + CompletableFuture 去重。
 */
@Slf4j
@Component
public class McpClientManager {

    /** sourceName → McpSource */
    private final Map<String, McpSource> sources = new ConcurrentHashMap<>();

    /** toolName(原始名) → McpToolDefinition */
    private final Map<String, McpToolDefinition> toolIndex = new ConcurrentHashMap<>();

    /** qualifiedName(sourceName_toolName) → McpToolDefinition */
    private final Map<String, McpToolDefinition> qualifiedToolIndex = new ConcurrentHashMap<>();

    /** 生命周期状态：sourceName → McpLoadState */
    private final Map<String, McpLoadState> states = new ConcurrentHashMap<>();

    /** 激活失败原因：sourceName → error（成功则无） */
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    /** 进行中的激活任务：sourceName → future（并发去重） */
    private final Map<String, CompletableFuture<Boolean>> loadingTasks = new ConcurrentHashMap<>();

    /** 由 McpToolBridge 启动后注入，供激活时动态添加/卸载 MCP 工具 */
    private volatile AgentExecutor agentExecutor;

    // ======================== 注册（零网络） ========================

    /**
     * 登记一个 MCP 源引用。仅写入 sources 索引并置为 UNLOADED，<b>不连接、不发现工具</b>。
     * 真正建立连接见 {@link #activate}。
     */
    public void register(McpSource source) {
        String name = source.getSourceName();
        sources.put(name, source);
        states.putIfAbsent(name, McpLoadState.UNLOADED);
        log.info("📡 [McpManager] 已登记 MCP 源引用（懒加载，未连接）: {}", name);
    }

    /** 由 McpToolBridge 注入，供激活时向 AgentExecutor 动态添加/卸载工具 */
    public void setAgentExecutor(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    // ======================== 生命周期控制 ========================

    /**
     * 激活指定源：连接 → 工具发现 → 注入 AgentExecutor → READY。
     * <p>
     * 幂等 + 并发去重：已 READY 直接返回 true；LOADING 中则等待同一任务结果；
     * 失败置 FAILED 并记录原因。
     *
     * @return 是否激活成功
     */
    public boolean activate(String sourceName) {
        McpSource source = sources.get(sourceName);
        if (source == null) return false;
        if (isLoaded(sourceName)) return true;

        // 并发去重：同一源只允许一个加载任务
        CompletableFuture<Boolean> inflight = loadingTasks.get(sourceName);
        if (inflight != null) {
            try {
                return inflight.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                return false;
            }
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture<Boolean> existing = loadingTasks.putIfAbsent(sourceName, future);
        if (existing != null) {
            try {
                return existing.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                return false;
            }
        }
        try {
            boolean ok = doActivate(sourceName);
            future.complete(ok);
            return ok;
        } catch (Throwable t) {
            log.error("❌ [McpManager] {} 激活异常: {}", sourceName, t.getMessage(), t);
            future.complete(false);
            return false;
        } finally {
            loadingTasks.remove(sourceName);
        }
    }

    /** 真正执行激活事务（per-source 同步，保证同一源不并发握手） */
    private synchronized boolean doActivate(String sourceName) {
        McpSource source = sources.get(sourceName);
        if (source == null) return false;
        if (isLoaded(sourceName)) return true;

        states.put(sourceName, McpLoadState.LOADING);
        errors.remove(sourceName);
        log.info("🔄 [McpManager] 开始激活 {} ...", sourceName);

        try {
            if (!source.isReady()) {
                source.init();
            }
            if (!source.isReady()) {
                fail(sourceName, "初始化后未就绪（可能未配置或握手失败）");
                return false;
            }

            List<McpToolDefinition> tools = source.listTools();
            for (McpToolDefinition def : tools) {
                def.setSourceName(sourceName);
                toolIndex.put(def.getName(), def);                  // "taxi_estimate"
                qualifiedToolIndex.put(def.qualifiedName(), def);   // "didi_taxi_estimate"
            }

            // 注入 AgentExecutor（供 LLM 本轮起可见）
            if (agentExecutor != null && !tools.isEmpty()) {
                List<ToolSpecification> specs = tools.stream().map(this::toLangChainSpec).toList();
                agentExecutor.registerMcpTools(specs, this);
            }

            states.put(sourceName, McpLoadState.READY);
            log.info("✅ [McpManager] {} 激活成功，{} 个工具已注入", sourceName, tools.size());
            return true;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            fail(sourceName, msg);
            return false;
        }
    }

    /**
     * 卸载指定源：移除全局索引 + 从 AgentExecutor 摘除工具 + 释放连接 → UNLOADED。
     */
    public boolean deactivate(String sourceName) {
        McpSource source = sources.get(sourceName);
        if (source == null) return false;
        synchronized (source) {
            toolIndex.entrySet().removeIf(e -> sourceName.equals(e.getValue().getSourceName()));
            qualifiedToolIndex.entrySet().removeIf(e -> sourceName.equals(e.getValue().getSourceName()));

            if (agentExecutor != null) {
                agentExecutor.removeMcpTools(sourceName);
            }
            try {
                source.close();
            } catch (Exception e) {
                log.warn("⚠️ [McpManager] {} close 异常: {}", sourceName, e.getMessage());
            }
            states.put(sourceName, McpLoadState.UNLOADED);
            errors.remove(sourceName);
            log.info("🔌 [McpManager] {} 已卸载，连接已释放", sourceName);
            return true;
        }
    }

    // ======================== 查询 ========================

    /**
     * 根据工具名查找对应的 McpSource。
     * ① 精确匹配原始名 / qualifiedName → ② 直接匹配 sourceName → ③ 按 "_" 前缀回退到源。
     * 前缀回退保证懒加载未激活（索引为空）时也能定位源。
     */
    public McpSource findSource(String toolName) {
        McpToolDefinition def = toolIndex.get(toolName);
        if (def == null) {
            def = qualifiedToolIndex.get(toolName);
        }
        if (def != null) {
            return sources.get(def.getSourceName());
        }
        McpSource direct = sources.get(toolName);
        if (direct != null) {
            return direct;
        }
        int idx = toolName.indexOf('_');
        if (idx > 0) {
            McpSource byPrefix = sources.get(toolName.substring(0, idx));
            if (byPrefix != null) return byPrefix;
        }
        return null;
    }

    /** 工具名是否可路由（索引命中，或可解析到已登记源） */
    public boolean hasTool(String toolName) {
        return toolIndex.containsKey(toolName)
                || qualifiedToolIndex.containsKey(toolName)
                || findSource(toolName) != null;
    }

    /** 源是否已登记（引用存在） */
    public boolean hasRegistered(String sourceName) {
        return sources.containsKey(sourceName);
    }

    /** 源是否已激活就绪 */
    public boolean isLoaded(String sourceName) {
        return states.getOrDefault(sourceName, McpLoadState.UNLOADED) == McpLoadState.READY;
    }

    /** 源当前生命周期状态 */
    public McpLoadState stateOf(String sourceName) {
        return states.getOrDefault(sourceName, McpLoadState.UNLOADED);
    }

    /** 源最近一次激活失败原因（未失败为 null） */
    public String lastError(String sourceName) {
        return errors.get(sourceName);
    }

    /** 全部源的状态面板（供前端 /api/mcp/sources 与日志） */
    public List<McpLoadStatus> getStatus() {
        List<McpLoadStatus> list = new ArrayList<>();
        for (Map.Entry<String, McpSource> e : sources.entrySet()) {
            String name = e.getKey();
            McpSource src = e.getValue();
            long count = toolIndex.values().stream().filter(d -> name.equals(d.getSourceName())).count();
            list.add(McpLoadStatus.builder()
                    .sourceName(name)
                    .displayName(src.displayName())
                    .state(states.getOrDefault(name, McpLoadState.UNLOADED))
                    .lastError(errors.get(name))
                    .toolCount((int) count)
                    .build());
        }
        return list;
    }

    /**
     * 获取所有已注册 MCP 工具的 ToolSpecification 列表（供 AgentExecutor 使用）。
     * 仅包含已激活源的已发现工具。
     */
    public List<ToolSpecification> getToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (McpToolDefinition def : toolIndex.values()) {
            specs.add(toLangChainSpec(def));
        }
        return specs;
    }

    /** 获取所有已发现工具定义（用于日志/监控/前端） */
    public List<McpToolDefinition> getAllTools() {
        return new ArrayList<>(toolIndex.values());
    }

    /** 登记的源数量 */
    public int sourceCount() {
        return sources.size();
    }

    /** 已发现工具总数 */
    public int toolCount() {
        return toolIndex.size();
    }

    // ======================== 调用 ========================

    /**
     * 统一的 MCP 工具调用入口。
     * 根据 toolName 自动路由到正确的 McpSource；
     * 源已登记但未激活时<b>首次调用自动激活</b>（懒加载兜底）。
     *
     * @param toolName  工具名（原始名或 qualifiedName）
     * @param arguments 参数键值对
     * @return 工具执行结果
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        McpSource source = findSource(toolName);
        if (source == null) {
            return "未找到工具 " + toolName + "，请检查工具名是否正确。";
        }

        String sourceName = source.getSourceName();

        // 懒加载兜底：源已登记但未激活 → 首次调用同步激活
        if (!isLoaded(sourceName)) {
            log.info("🔄 [McpManager] 首次调用触发懒加载: {}/{}", sourceName, toolName);
            boolean ok = activate(sourceName);
            if (!ok) {
                String err = errors.get(sourceName);
                return "🚫 " + sourceName + " 服务加载失败，请稍后再试。"
                        + (err != null ? "（" + err + "）" : "");
            }
        }

        // 将 qualifiedName 还原为 MCP 原始工具名
        String rawToolName = toolName;
        McpToolDefinition def = qualifiedToolIndex.get(toolName);
        if (def != null) {
            rawToolName = def.getName();
        } else {
            int idx = toolName.indexOf('_');
            if (idx > 0) rawToolName = toolName.substring(idx + 1);
        }

        log.info("🔧 [McpManager] 调用 {}/{} args={}", sourceName, rawToolName,
                JSON.toJSONString(arguments));

        long start = System.currentTimeMillis();
        String result = source.callTool(rawToolName, arguments);
        long elapsed = System.currentTimeMillis() - start;

        log.info("📤 [McpManager] {}/{} 完成 ({}ms) resultLen={}",
                sourceName, rawToolName, elapsed,
                result != null ? result.length() : 0);

        // 滴滴打车下单成功 → 异步写入 taxi_orders 表
        if ("didi".equals(sourceName) && "taxi_create_order".equals(rawToolName)
                && result != null && result.contains("订单创建成功")) {
            logTaxiOrderAsync(arguments, result);
        }

        // 异步记 MCP 调用日志
        logCallAsync(sourceName, rawToolName, arguments, result, elapsed);

        return result;
    }

    // ======================== 内部方法 ========================

    /** 激活失败统一处理：置 FAILED + 记原因 */
    private void fail(String sourceName, String reason) {
        states.put(sourceName, McpLoadState.FAILED);
        errors.put(sourceName, reason);
        log.warn("❌ [McpManager] {} 激活失败: {}", sourceName, reason);
    }

    /**
     * 将 McpToolDefinition 转为 LangChain4j ToolSpecification。
     * 通过 JSON 序列化绕过 builder 的类型限制。
     */
    private ToolSpecification toLangChainSpec(McpToolDefinition def) {
        String description = def.getDescription() != null ? def.getDescription() : "";
        description = "[" + def.getSourceName() + "] " + description;

        JSONObject json = new JSONObject();
        json.put("name", def.qualifiedName());
        json.put("description", description);

        // 构建 JsonObjectSchema 格式的 parameters
        JSONObject params = new JSONObject();
        params.put("type", "object");
        if (def.getInputSchema() != null) {
            if (def.getInputSchema().containsKey("properties")) {
                params.put("properties", def.getInputSchema().get("properties"));
            }
            if (def.getInputSchema().containsKey("required")) {
                params.put("required", def.getInputSchema().get("required"));
            }
        } else {
            params.put("properties", new JSONObject());
        }
        json.put("parameters", params);

        return ToolSpecification.fromJson(json.toJSONString());
    }

    // ---- 日志记录 ----
    @Resource
    private McpCallLogService logService;

    @Resource
    private TaxiOrderMapper taxiOrderMapper;

    /** 异步写打车订单到 taxi_orders 表 */
    private void logTaxiOrderAsync(Map<String, Object> args, String result) {
        try {
            TaxiOrderEntity entity = new TaxiOrderEntity();
            entity.setUserId(com.demo.util.VoiceContext.getUserId());
            entity.setOrigin(String.valueOf(args.getOrDefault("from_name", "")));
            entity.setDestination(String.valueOf(args.getOrDefault("to_name", "")));
            entity.setStatus("completed");
            entity.setRawResponse(result);

            // 从结果文本中提取订单号（格式：订单号：At00Yt4RDIZdcy）
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("订单号[：:]\\s*([A-Za-z0-9]+)").matcher(result);
                if (m.find()) entity.setOrderNo(m.group(1));
            } catch (Exception ignored) {}

            taxiOrderMapper.insert(entity);
            log.info("📝 打车订单已写入 taxi_orders: orderNo={}", entity.getOrderNo());
        } catch (Exception e) {
            log.warn("⚠ 打车订单写入失败: {}", e.getMessage());
        }
    }

    private void logCallAsync(String sourceName, String toolName,
                              Map<String, Object> arguments, String result, long elapsedMs) {
        if (logService != null) {
            try {
                logService.log(sourceName, toolName,
                        JSON.toJSONString(arguments), result, "success", elapsedMs);
            } catch (Exception e) {
                // 日志记录失败不影响主流程
            }
        }
    }
}
