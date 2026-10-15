package com.demo.router;

/**
 * 前置路由 L1 的决策结果。
 *
 * <p>语义分层：
 * <ul>
 *   <li>{@link #SYSTEM_CMD} / {@link #WATERMARK} / {@link #VOICE_PANEL} / {@link #DRAW} / {@link #STYLE_IMITATE}
 *       是「快速路径类」——识别出用户意图落在某个内建能力上，可以直接收窄工具集；</li>
 *   <li>{@link #CHAT} 是闲聊，理论上 0 工具；</li>
 *   <li>{@link #TOOL} 表示 L2 命中了一个具体的工具类意图，具体意图见 {@link RouteResult#getIntent()}；</li>
 *   <li>{@link #FALLBACK} 表示未识别，<b>必须回退到全量工具</b>兜底。</li>
 * </ul>
 *
 * <h3>为什么不实现「命中即跳转、0 token」</h3>
 * 原始方案书假设项目里有 {@code WatermarkCommandHandler} / {@code VoicePanelHandler} /
 * {@code ImageGenMessageHandler} / {@code StyleImitationHandler} 等 Handler 类，
 * 命中后可以绕过 LLM 直接执行。<b>本项目没有 Handler 模式</b>——水印、语音模式、绘图、风格模仿
 * 全部是 LLM 通过 {@code @Tool} 反射调用的，不存在可供绕过的执行体。
 * 因此 L1 在本项目的职责是「分类 + 收窄工具集」：
 * {@code "画一只猫"} 从 27 个工具收窄到 1 个（{@code generateImage}），
 * token 收益与"0 token"路线一致，但不改变任何执行语义、不引入重复实现。
 *
 * <p>注意：系统指令（help/version/status）依旧由 {@code CmdService} 在路由之前拦截，
 * 这里保留 {@link #SYSTEM_CMD} 只是为了观测口径统一。
 *
 * <p>{@code MULTIMODAL} 已按方案书 Prompt 3 的结论删除——附件消息不走 L2，该枚举是死代码。
 */
public enum RouteDecision {

    /**
     * 安全拦截：命中自伤风险或违规内容请求，<b>终止链路并返回固定引导话术</b>。
     *
     * <p>为什么做成一个路由判决而不是散在外面的 if：{@code HybridRouter} 的漏斗语义是
     * "任何一层给出非 FALLBACK 的结论即采纳"，所以把安全规则放在 L1 的<b>第一条</b>，
     * 就天然获得了"命中即终止、不进 L2/L3（含 L3 的那次 LLM 调用）"的性质，
     * 不需要在任何调用方再补一层判断。
     *
     * <p>配套：{@code AgentExecutor} 见到本判决即短路返回话术。
     *
     * <p><b>关于工具集</b>：L1 这里给的是<b>空集</b>（L1 单测断言的就是空集）。
     * 但经过 {@code HybridRouter} 的常规过滤后，配置里的「通用工具」
     * （{@code router.tool-filtering.general-tools}，默认 {@code [searchWeb]}）仍会被并入，
     * 所以日志里会看到 {@code tools=1} 而不是 0。<b>这不影响安全</b>：
     * 短路发生在 {@code buildMessages} 之前，那个工具集根本不会被用来发起任何调用。
     * 记在这里是为了避免下次有人看到 {@code tools=1} 以为"BLOCKED 没生效"。
     */
    BLOCKED,

    /** 系统指令（help / version / status），实际由 CmdService 在路由前拦截。 */
    SYSTEM_CMD,

    /** 图片水印。注意：本项目水印只有 REST 接口，没有 @Tool，收窄后工具集为空。 */
    WATERMARK,

    /** 语音模式 / 音色面板。 */
    VOICE_PANEL,

    /** 绘图指令。 */
    DRAW,

    /** 风格模仿。 */
    STYLE_IMITATE,

    /** 闲聊，零工具。 */
    CHAT,

    /** L2 命中了具体工具类意图，详见 {@link RouteResult#getIntent()}。 */
    TOOL,

    /** 未识别，回退全量工具。 */
    FALLBACK;

    /** 是否属于「快速路径类」（L1 直接判定、不依赖 L2）。 */
    public boolean isFastPath() {
        return this == BLOCKED || this == SYSTEM_CMD || this == WATERMARK
                || this == VOICE_PANEL || this == DRAW || this == STYLE_IMITATE;
    }

    /** 是否为安全拦截（调用方应据此短路，不要继续拼提示词或调 LLM）。 */
    public boolean isBlocked() {
        return this == BLOCKED;
    }

    /** 是否允许据此收窄工具集。FALLBACK 永远不允许。 */
    public boolean isFilterable() {
        return this != FALLBACK;
    }
}
