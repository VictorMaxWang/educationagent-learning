package com.demo.agent;

/**
 * 工具结果长度护栏：单条工具结果过长时截断，避免撑爆 LLM 的输入长度上限。
 *
 * <h3>为什么需要它</h3>
 * 工具结果会被原样塞进下一轮 messages（{@code ToolExecutionResultMessage}），
 * 而 FSM 最多迭代 {@code MAX_TOOL_ITERATIONS = 8} 轮——每一轮的工具结果都会留在上下文里累积。
 * 只要有几个工具返回长文本（网页正文、大文件内容、MCP 的冗长 JSON），
 * 输入长度就会越界，DeepSeek 直接返回
 * {@code InvalidParameter: Range of input length should be [1, 1000000]}，
 * 用户看到的是"AI 服务暂时不可用"，而真正的原因是一次网页抓取。
 *
 * <p>方案书把这个 bug 归因于"绘图工具返回了含 base64/data_uri 的结果（约 1.7MB）"。
 * 本项目的 {@code ImageGenTool} <b>并没有</b>返回 base64（只返回一句"图片已生成完成"），
 * 所以那个具体成因在这里不存在；但<b>风险类别完全一样</b>——
 * 本项目真正会返回长文本的是 {@code readWebPage} / {@code webFetch} / {@code readFile}
 * 以及各 MCP 工具。护栏要防的是"任何工具结果过长"，而不是某一个工具。
 *
 * <h3>截断策略：保留头部，不做头尾拼接</h3>
 * 只保留头部并加显式标记。不采用"头 70% + 尾 30%"的常见做法，
 * 因为很多工具返回的是 JSON，头尾拼接会产出一段<b>语法不合法</b>的 JSON——
 * 模型看到半截 JSON 比看到一段明确的"已截断"更糟：
 * 前者会诱发它去猜测缺失字段，后者至少让它知道信息不完整。
 */
public final class ToolResultGuard {

    /** 单条工具结果的字符上限。约 1500~2000 token，给 8 轮迭代留足余量。 */
    public static final int DEFAULT_MAX_CHARS = 4000;

    private ToolResultGuard() {
    }

    /**
     * 用默认上限截断。
     *
     * @return 截断结果；{@code truncated=true} 时 {@code text} 已含截断标记
     */
    public static Result guard(String toolResult) {
        return guard(toolResult, DEFAULT_MAX_CHARS);
    }

    /**
     * 按指定上限截断。
     *
     * @param toolResult 工具返回的原始文本，可为 null
     * @param maxChars   上限（&lt; 1 时回退到默认值）
     */
    public static Result guard(String toolResult, int maxChars) {
        if (toolResult == null) {
            return new Result("", false, 0);
        }
        int limit = maxChars >= 1 ? maxChars : DEFAULT_MAX_CHARS;
        if (toolResult.length() <= limit) {
            return new Result(toolResult, false, toolResult.length());
        }
        String marker = "\n…[内容过长已截断，原始长度 " + toolResult.length()
                + " 字符，仅保留前 " + limit + " 字符]";
        return new Result(toolResult.substring(0, limit) + marker, true, toolResult.length());
    }

    /**
     * @param text           最终要放进 messages 的文本
     * @param truncated      是否发生了截断
     * @param originalLength 原始字符数
     */
    public record Result(String text, boolean truncated, int originalLength) {
    }
}
