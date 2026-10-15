package com.demo.mcp.lazy;

import lombok.Builder;
import lombok.Data;

/**
 * MCP 源运行状态快照 — 供前端 {@code /api/mcp/sources} 与日志展示。
 */
@Data
@Builder
public class McpLoadStatus {

    /** 源标识（sourceName，如 "didi"） */
    private String sourceName;

    /** 人类可读名称（默认同 sourceName） */
    private String displayName;

    /** 生命周期状态 */
    private McpLoadState state;

    /** 激活失败原因（未失败为 null） */
    private String lastError;

    /** 已注册工具数（未激活为 0） */
    private int toolCount;
}
