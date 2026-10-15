package com.demo.service;

import com.demo.agent.Assistant;
import com.demo.agent.FileConvertTool;
import com.demo.edu.classroom.ClassroomWechatBridge;
import com.demo.config.AiChatProperties;
import com.demo.mcp.lazy.McpLazyActivator;
import com.demo.service.tools.FileSystemService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CmdService {

    @Resource
    private Assistant assistant;

    @Resource
    private FileSystemService fileSystemService;

    @Resource
    private FileConvertTool fileConvertTool;

    @Resource
    private McpLazyActivator mcpLazyActivator;

    @Resource
    private ClassroomWechatBridge classroomWechatBridge;

    @Resource
    private AiChatProperties aiChat;

    /**
     * 处理用户消息
     * 系统指令走快速路径，其余全部交给 LangChain4j Agent 处理。
     * Agent 会自主决定调用天气、绘图、语音控制等工具。
     *
     * @param input    用户输入的消息
     * @param memoryId 对话记忆 ID（微信用户 ID 或 "default"）
     * @return 回复内容
     */
    public String dealMsg(String input, String memoryId) {
        if (input == null || input.trim().isEmpty()) {
            return "错误：输入内容不能为空";
        }
        // 课堂指令走精确入口；在通用日志之前处理，避免把一次性配对码写入日志。
        var classroomReply = classroomWechatBridge.handle(input, memoryId);
        if (classroomReply.isPresent()) return classroomReply.get();
        String msg = input.trim().toLowerCase();
        log.info("📩 [{}] 接收消息：{}", memoryId, msg);

        // 系统指令走快速路径，不消耗 AI 额度
        switch (msg) {
            case "help":
                return """
                        ============ 使用帮助 ============
                        help         查看帮助
                        version      查看版本
                        status       查看运行状态
                        开启语音      全局语音回复模式
                        关闭语音      切回文字回复
                        用语音说/唱  本条用语音回复
                        其他输入      AI 智能回复（聊天、天气查询等）
                        =================================""";
            case "version":
                return "educationagent · 学习与成长助手（Java + LangChain4j）";
            case "status":
                return "程序状态：运行中\n文本模型：" + aiChat.getModel() + "\n各服务的可用性请查看健康检查。";
            default:
                // 检查是否为 6 位文件操作验证码（绕过 LLM，直接执行）
                String rawInput = input.trim();
                if (rawInput.matches("\\d{6}")) {
                    String result = fileSystemService.executePendingOp(rawInput, memoryId);
                    // 如果确实是有效的验证码，直接返回操作结果
                    if (!result.contains("无效") && !result.contains("过期")) {
                        log.info("🔐 验证码直接执行：{}", rawInput);
                        return result;
                    }
                    // 否则当作普通消息交给 LLM
                }
                // ★ 文件转换预检：有缓存文件 + 转换关键词 → 直接执行，绕过 LLM（零幻觉零费用）
                String convertResult = fileConvertTool.tryPrecheckConvert(memoryId, rawInput);
                if (convertResult != null) {
                    log.info("📄 [{}] 转换预检执行完成", memoryId);
                    return convertResult;
                }
                // ★ MCP 懒加载：关键词命中未加载源 → 触发预加载（让 LLM 本轮就能用上工具）
                mcpLazyActivator.matchAndActivate(rawInput);
                // 交给 LangChain4j Agent 处理（自动选择工具或直接对话）
                log.info("🤖 [{}] 委托 Agent 处理...", memoryId);
                return assistant.chat(memoryId, rawInput);
        }
    }

    /**
     * 兼容旧接口（无 memoryId），使用默认 ID
     */
    public String dealMsg(String input) {
        return dealMsg(input, "default");
    }
}
