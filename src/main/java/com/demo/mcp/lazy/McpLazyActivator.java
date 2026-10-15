package com.demo.mcp.lazy;

import com.demo.mcp.McpClientManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MCP 懒加载核心服务 — 统一触发源激活的三种入口。
 * <p>
 * ① 前端显式控制：{@link #activate} / {@link #deactivate} / {@link #preloadAsync}
 * ② 消息关键词预加载：{@link #matchAndActivate}（CmdService 进入 LLM 前调用）
 * ③ 首次调用兜底：在 {@link McpClientManager#callTool} 内部完成（同步激活）
 * <p>
 * 激活本身（init + tools/list + 注入 AgentExecutor）由 {@link McpClientManager} 原子完成，
 * 本类只负责"什么时候触发"与"是否等待就绪"。
 */
@Slf4j
@Component
public class McpLazyActivator {

    /** 后台加载线程池（daemon，不阻塞应用退出；单个源激活慢不影响消息线程） */
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "mcp-lazy-loader");
        t.setDaemon(true);
        return t;
    });

    private final McpClientManager manager;
    private final McpLazyProperties props;

    public McpLazyActivator(McpClientManager manager, McpLazyProperties props) {
        this.manager = manager;
        this.props = props;
    }

    // ======================== 前端显式控制 ========================

    /**
     * 同步激活单个源（阻塞直至 READY/FAILED）。
     * 用于前端 {@code POST /api/mcp/activate}。
     */
    public boolean activate(String sourceName) {
        return manager.activate(sourceName);
    }

    /**
     * 同步卸载单个源（移除工具 + 释放连接）。
     * 用于前端 {@code POST /api/mcp/deactivate}。
     */
    public boolean deactivate(String sourceName) {
        return manager.deactivate(sourceName);
    }

    // ======================== 提前预加载 ========================

    /**
     * 单源异步预加载（提前预热接口）。返回是否已启动/已就绪。
     */
    public boolean preloadAsync(String sourceName) {
        if (!manager.hasRegistered(sourceName)) {
            log.warn("🔌 [McpLazy] 未知源，跳过预加载: {}", sourceName);
            return false;
        }
        if (manager.isLoaded(sourceName)) return true;
        LOADER.submit(() -> manager.activate(sourceName));
        log.info("🔌 [McpLazy] 异步预加载启动: {}", sourceName);
        return true;
    }

    /**
     * 批量异步预加载（前端 {@code POST /api/mcp/preload}）。
     *
     * @return 实际启动预加载的源列表
     */
    public List<String> preloadAsync(List<String> sourceNames) {
        List<String> started = new ArrayList<>();
        if (sourceNames == null) return started;
        for (String name : sourceNames) {
            if (name == null || name.isBlank()) continue;
            String s = name.trim();
            if (manager.hasRegistered(s) && !manager.isLoaded(s)) {
                preloadAsync(s);
                started.add(s);
            }
        }
        return started;
    }

    // ======================== 消息关键词预加载 ========================

    /**
     * 消息入口：关键词命中未加载源 → 触发异步激活。
     * <p>
     * 命中后若配置了 {@code matchAwaitMs>0}，会短等待源就绪，
     * 保证"这条消息"就能用上该源的工具（首条消息体验）；超时则继续后台加载。
     *
     * @param text 用户消息文本（预处理后的最终文本）
     */
    public void matchAndActivate(String text) {
        if (text == null || text.isBlank()) {
            log.debug("[McpLazy] 消息为空，跳过关键词匹配");
            return;
        }
        Map<String, String> triggers = props.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            log.warn("[McpLazy] 触发词表为空！请检查 mcp.lazy.triggers.* 是否配置且被 @ConfigurationProperties 绑定");
            return;
        }
        log.info("[McpLazy] 关键词匹配开始 | text={} | 触发词表{}个源: {}",
                truncateText(text, 40), triggers.size(), triggers.keySet());

        long awaitMs = Math.max(0, props.getMatchAwaitMs());
        boolean anyHit = false;

        for (Map.Entry<String, String> e : triggers.entrySet()) {
            String sourceName = e.getKey();

            if (!manager.hasRegistered(sourceName)) {
                log.info("[McpLazy] {} 未登记（hasRegistered=false），跳过", sourceName);
                continue;
            }
            if (manager.isLoaded(sourceName)) {
                log.info("[McpLazy] {} 已加载（isLoaded=true），无需重复触发", sourceName);
                continue;
            }

            String words = e.getValue();
            if (words == null || words.isBlank()) {
                log.info("[McpLazy] {} 触发词为空，跳过", sourceName);
                continue;
            }

            log.info("[McpLazy] {} 未加载，开始匹配触发词: {}", sourceName, words);
            for (String kw : words.split("[,，]")) {
                String w = kw.trim();
                if (w.isEmpty()) continue;
                if (!text.contains(w)) {
                    log.debug("[McpLazy]   词「{}」未命中", w);
                    continue;
                }
                anyHit = true;
                log.info("🔌 [McpLazy] 关键词「{}」命中 → 预加载 {}", w, sourceName);
                CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(
                        () -> manager.activate(sourceName), LOADER);
                if (awaitMs > 0) {
                    // 直接等待激活任务完成；不要轮询状态——异步任务尚未被线程池调度时
                    // 源状态还是 UNLOADED，会被误判为终态而提前返回（本轮工具就绪不了）
                    try {
                        f.get(awaitMs, TimeUnit.MILLISECONDS);
                        log.info("[McpLazy] {} 已激活（等待{}ms内完成）", sourceName, awaitMs);
                    } catch (Exception ex) {
                        log.info("🔌 [McpLazy] {} 预加载等待超时（{}ms），继续后台加载", sourceName, awaitMs);
                    }
                }
                break; // 该源已触发，无需匹配更多词
            }
        }

        if (!anyHit) {
            log.info("[McpLazy] 关键词匹配结束，无源命中（触发词表={}）", triggers.keySet());
        }
    }

    /** 截断长文本用于日志 */
    private static String truncateText(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

}
