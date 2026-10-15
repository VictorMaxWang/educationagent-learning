package com.demo.mcp.lazy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 懒加载配置绑定类 — 映射 {@code mcp.lazy.*} 全部配置项。
 *
 * <pre>
 * mcp.lazy.enabled=true
 * mcp.lazy.preload=didi,antvchart                 # 启动预加载白名单（逗号分隔）
 * mcp.lazy.match-await-ms=2500                     # 关键词命中后等待源就绪上限
 * mcp.lazy.triggers.didi=打车,叫车                  # 触发词表：sourceName → 触发词
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.lazy")
public class McpLazyProperties {

    /**
     * 是否启用懒加载。
     * true=仅登记引用，由前端/关键词/首次调用按需激活；
     * false=兼容旧模式，启动全量激活（等价于改造前行为）。
     */
    private boolean enabled = true;

    /** 启动预加载白名单（逗号分隔 → List，对应 sourceName）。留空=纯懒加载 */
    private List<String> preload = new ArrayList<>();

    /** 关键词命中后，等待源就绪的最长时间（毫秒），保证首条消息就能用上工具；0=纯异步不等待 */
    private long matchAwaitMs = 2500;

    /** 关键词触发表：sourceName → 触发词（逗号或中文逗号分隔） */
    private Map<String, String> triggers = new LinkedHashMap<>();
}
