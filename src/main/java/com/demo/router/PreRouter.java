package com.demo.router;

/**
 * 前置路由单层接口。L1 / L2 / L3 各自实现一层，由 {@code HybridRouter} 按序调用。
 *
 * <p>约定：
 * <ul>
 *   <li><b>任何实现都不得抛异常</b>——路由是旁路能力，失败必须降级为
 *       {@link RouteResult#fallback}，绝不能因为路由挂了导致主对话不可用。</li>
 *   <li>{@link #isEnabled()} 由配置开关驱动，关闭时 {@code HybridRouter} 直接跳过该层。</li>
 * </ul>
 */
public interface PreRouter {

    /**
     * 对一条消息做路由判定。
     *
     * @param userId  用户 ID（仅用于日志/埋点，不参与判定）
     * @param message 预处理后的文本消息（语音/图片已转成文字）
     * @param ctx     旁路上下文（渠道、附件标记）
     * @return 路由结果，非 null；无法识别时返回 {@link RouteResult#fallback}
     */
    RouteResult route(String userId, String message, MessageContext ctx);

    /** 该层是否启用。 */
    boolean isEnabled();

    /** 层名，用于日志与埋点（如 {@code "L1"} / {@code "L2"} / {@code "L3"}）。 */
    String layerName();
}
