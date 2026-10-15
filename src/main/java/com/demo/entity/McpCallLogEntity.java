package com.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * MCP 调用日志实体 — 映射 mcp_call_logs 表。
 * 每一次 MCP 工具调用（请求参数 + 返回结果）都持久化到此表，
 * 用于调用链路审计、故障排查、成本分析。
 */
@TableName("mcp_call_logs")
public class McpCallLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceName;
    private String toolName;
    private String requestParams;
    private String responseBody;
    private String status;
    private Long durationMs;
    private LocalDateTime createdAt;

    public McpCallLogEntity() {}

    public McpCallLogEntity(String sourceName, String toolName, String requestParams,
                            String responseBody, String status, long durationMs) {
        this.sourceName = sourceName;
        this.toolName = toolName;
        this.requestParams = requestParams;
        this.responseBody = responseBody;
        this.status = status;
        this.durationMs = durationMs;
    }

    // ======== Getters & Setters ========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
