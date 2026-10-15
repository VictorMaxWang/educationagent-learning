package com.demo.mcp.lazy;

/**
 * MCP 源生命周期状态（懒加载状态机）。
 * <p>
 * 由 {@link com.demo.mcp.McpClientManager} 驱动：
 * <pre>
 *   UNLOADED → LOADING → READY → (FAILED) → UNLOADED
 * </pre>
 */
public enum McpLoadState {

    /** 已登记引用，未连接、未发现工具（懒加载初始态，零网络开销） */
    UNLOADED,

    /** 正在激活：连接 + 工具发现 + 注入 AgentExecutor */
    LOADING,

    /** 已激活，工具可被 LLM 调用 */
    READY,

    /** 激活失败，lastError 记录原因 */
    FAILED
}
