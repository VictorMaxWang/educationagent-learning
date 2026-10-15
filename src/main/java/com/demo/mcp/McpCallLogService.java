package com.demo.mcp;

import com.demo.entity.McpCallLogEntity;
import com.demo.mapper.McpCallLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 调用日志服务。
 * 异步写入 mcp_call_logs 表，不阻塞 MCP 工具调用主流程。
 */
@Slf4j
@Service
//McpCallLogService：MCP 工具调用的异步日志落库服务
//你项目里所有滴滴 MCP、聚合 MCP 等第三方工具接口调用，
// 都会通过这个类把调用记录存到数据库表 mcp_call_logs，用来排查问题、统计耗时、追溯请求。
public class McpCallLogService {

    @Resource
    private McpCallLogMapper mapper;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-log-persist");
        t.setDaemon(true);
        return t;
    });

    /**
     * 异步记录一次 MCP 调用。
     */
    public void log(String sourceName, String toolName, String requestParams,
                    String responseBody, String status, long durationMs) {
        // 截断超长响应，避免撑爆数据库
        String truncatedResponse = responseBody != null && responseBody.length() > 4000
                ? responseBody.substring(0, 4000) + "...(truncated)"
                : responseBody;

        executor.submit(() -> {
            try {
                McpCallLogEntity entity = new McpCallLogEntity(
                        sourceName, toolName, requestParams, truncatedResponse, status, durationMs);
                mapper.insert(entity);
            } catch (Exception e) {
                // 日志落库失败不影响业务
            }
        });
    }
}
