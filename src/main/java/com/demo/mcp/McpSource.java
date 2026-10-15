package com.demo.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具源顶层接口。
 * <p>
 * 每个外部 MCP Server（滴滴、聚合数据火车票、高德等）各自实现此接口，
 * 统一由 {@link McpClientManager} 管理，对上层 Agent 屏蔽底层差异。
 * <p>
 * 实现类只需关心：①如何连接远端 ②如何发现工具 ③如何调用工具。
 * 会话管理、超时、重试、日志由 {@link McpClientManager} 统一处理。
 */
public interface McpSource {

    /**
     * 全局唯一的来源标识名。
     * 用于日志、配置绑定、toolName 前缀。
     * 例如："didi"、"juhe-train"、"amap"
     */
    String getSourceName();

    /**
     * 当前源是否已初始化并就绪可调用。
     * 未就绪时 McpClientManager 会跳过该源的工具注册。
     */
    boolean isReady();

    /**
     * 初始化连接（建立会话、鉴权等）。
     * 由 McpClientManager 在注册时调用，失败不阻塞启动。
     */
    void init();

    /**
     * 列出该 MCP Server 提供的所有工具。
     * 对应 MCP 协议的 tools/list。
     */
    List<McpToolDefinition> listTools();

    /**
     * 调用指定工具。
     * 对应 MCP 协议的 tools/call。
     *
     * @param toolName  工具名
     * @param arguments 参数键值对
     * @return 工具执行结果（文本）
     */
    String callTool(String toolName, Map<String, Object> arguments);

    // ======================== 懒加载生命周期 ========================

    /**
     * 人类可读的源名称（用于前端展示/日志），默认同 sourceName。
     */
    default String displayName() {
        return getSourceName();
    }

    /**
     * 释放连接资源（懒加载卸载时由 McpClientManager 调用）。
     * <p>
     * 实现方需保证：close 后再次 init() 可重新建立连接。
     * 无外部连接（纯配置型）的源可保留默认空实现。
     */
    default void close() {
    }
}
