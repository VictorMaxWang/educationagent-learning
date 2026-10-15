package com.demo.router;

/**
 * 路由上下文：随消息一起流转的、与消息内容无关的旁路信息。
 *
 * <p>存在的意义是让 {@link PreRouter} 不必关心调用方是谁（微信轮询 / 桌面 NDJSON 流），
 * 同时把「这条消息带不带附件」这类信息显式化——带附件的消息直接跳过 L2
 * （方案书 Prompt 3 已确认附件消息不走 L2，这也是 {@code MULTIMODAL} 被判为死代码的原因）。
 *
 * @param channel  消息来源渠道
 * @param hasImage 是否带图片附件（已由 MessagePreprocessor 转成文字描述，但旁路仍要标记）
 * @param hasVoice 是否带语音附件
 * @param hasFile  是否带文件附件
 */
public record MessageContext(Channel channel, boolean hasImage, boolean hasVoice, boolean hasFile) {

    /** 消息来源渠道。 */
    public enum Channel {
        /** 微信端（BotInstance 轮询 → WechatMsgListener → CmdService）。 */
        WECHAT,
        /** 桌面端（/api/desktop/chat/stream → SessionChatStreamer）。 */
        DESKTOP,
        UNKNOWN
    }

    /** 纯文本消息（最常用）。 */
    public static MessageContext text(Channel channel) {
        return new MessageContext(channel, false, false, false);
    }

    /** 微信端纯文本。 */
    public static MessageContext wechat() {
        return text(Channel.WECHAT);
    }

    /**
     * 按 {@code memoryId} 推断渠道。
     *
     * <p>为什么靠推断而不是显式传参：{@code AgentExecutor} 是微信与桌面两条链路唯一的汇合点，
     * 它的入口签名是 {@code chat(memoryId, userMessage)}——为了不破坏 {@code Assistant} 接口、
     * 不引入 ThreadLocal（微信链路跑在线程池里，ThreadLocal 忘了 clear 会串号），
     * 这里按 memoryId 的构造约定反推。
     *
     * <p>约定来源：{@code SessionChatStreamer} 用 {@code "session_" + sessionId} 或
     * {@code "desktop_" + userId}，微信链路直接用微信 userId。若将来约定变了，
     * 这里最多让埋点里的 channel 字段变得不准，<b>不影响路由判定本身</b>（判定只用 message）。
     */
    public static MessageContext inferFrom(String memoryId) {
        if (memoryId == null) return text(Channel.UNKNOWN);
        if (memoryId.startsWith("session_") || memoryId.startsWith("desktop_")) {
            return text(Channel.DESKTOP);
        }
        return text(Channel.WECHAT);
    }

    /** 桌面端纯文本。 */
    public static MessageContext desktop() {
        return text(Channel.DESKTOP);
    }

    /** 是否携带任意附件。 */
    public boolean hasAttachment() {
        return hasImage || hasVoice || hasFile;
    }
}
