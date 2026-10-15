package com.demo.mcp;

import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * MCP 工具定义 POJO — 描述一个 MCP 工具的名称、描述、参数约束。
 * <p>
 * 由 McpSource.listTools() 返回，McpClientManager 汇总后注册进全局工具中心。
 * 和 LangChain4j 的 ToolSpecification 对等，但不依赖 LangChain4j，保持 mcp 包纯净。
 */
public class McpToolDefinition {

    /** 工具名（MCP 侧的原始名称，如 "taxi_estimate"） */
    private String name;

    /** 工具描述 */
    private String description;

    /** 所属来源名称（如 "didi"） */
    private String sourceName;

    /** 参数 JSON Schema（properties + required） */
    private JSONObject inputSchema;

    /** 必填参数名列表 */
    private List<String> required;

    public McpToolDefinition() {}

    public McpToolDefinition(String name, String description, String sourceName,
                             JSONObject inputSchema) {
        this.name = name;
        this.description = description;
        this.sourceName = sourceName;
        this.inputSchema = inputSchema;
        if (inputSchema != null && inputSchema.containsKey("required")) {
            this.required = inputSchema.getJSONArray("required").toJavaList(String.class);
        } else {
            this.required = Collections.emptyList();
        }
    }

    /**
     * 生成全局唯一的工具名：{sourceName}_{name}
     * 防止不同 MCP 源的工具名冲突。
     */
    public String qualifiedName() {
        return sourceName + "_" + name;
    }

    // ======== Getters & Setters ========

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public JSONObject getInputSchema() { return inputSchema; }
    public void setInputSchema(JSONObject inputSchema) { this.inputSchema = inputSchema; }

    public List<String> getRequired() { return required; }
    public void setRequired(List<String> required) { this.required = required; }

    @Override
    public String toString() {
        return qualifiedName() + " — " + description;
    }
}
