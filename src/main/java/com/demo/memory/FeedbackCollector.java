package com.demo.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户反馈采集：让记忆的重要度能被用户"纠正"。
 *
 * <h3>两类反馈</h3>
 * <ul>
 *   <li><b>显式</b>：用户直接说「记住了」（正向）/「你记错了」（负向）/「忘掉」（清除）。</li>
 *   <li><b>隐式</b>：用户反复问同一件事 → 说明这条记忆有用，强化它。</li>
 * </ul>
 *
 * <h3>两个必须踩对的细节</h3>
 * <ol>
 *   <li><b>匹配顺序必须是 清除 → 负向 → 正向。</b>
 *       因为「不对」里含有「对」、「记错了」与「记住了」只差一个字。
 *       先判正向的话，「不对」会被当成"用户确认了"，把错误记忆<b>加强</b>——
 *       这是反馈系统里最坏的一种 bug：用户明明在纠错，系统却更确信了。</li>
 *   <li><b>单字关键词只认短句。</b>正向词表里的「对」是个单字，而中文里「对」出现在
 *       「对比一下」「对话记录」「面对」等大量非反馈语境中。
 *       所以规定：长度 ≤1 的关键词只有整句长度 ≤ {@link #SHORT_UTTERANCE_MAX} 时才生效
 *       （"对"、"对！" 算反馈；"对比一下这两个方案" 不算）。</li>
 * </ol>
 */
@Slf4j
@Component
public class FeedbackCollector {

    /** 单字关键词生效的最大整句长度。 */
    private static final int SHORT_UTTERANCE_MAX = 6;

    private final MemoryRagService memoryRagService;
    private final MemoryProperties properties;

    public FeedbackCollector(MemoryRagService memoryRagService, MemoryProperties properties) {
        this.memoryRagService = memoryRagService;
        this.properties = properties;
    }

    /** 反馈类型。 */
    public enum Signal {
        /** 无反馈。 */
        NONE,
        /** 正向：加强。 */
        POSITIVE,
        /** 负向：削弱。 */
        NEGATIVE,
        /** 清除：删除。 */
        CLEAR
    }

    /**
     * @param affected 被处理的记忆条数
     * @param signal   识别出的信号
     */
    public record Outcome(Signal signal, int affected) {
    }

    /**
     * 处理一条用户消息里的反馈。
     *
     * @param lastRetrieved 上一轮检索命中并注入过的记忆；显式的正/负反馈作用在它们身上
     *                      （而不是"用户全部记忆"——用户纠正的是刚才那句话）
     */
    public Outcome handle(String userId, String message, List<MemoryRecord> lastRetrieved) {
        if (!properties.getFeedback().isEnabled() || !memoryRagService.isUsable()) {
            return new Outcome(Signal.NONE, 0);
        }
        if (message == null || message.isBlank()) {
            return new Outcome(Signal.NONE, 0);
        }

        // 顺序不能变：清除 → 负向 → 正向。见类注释。
        if (containsAny(message, properties.getFeedback().getClearKeywords())) {
            int n = memoryRagService.deleteAll(userId);
            log.info("【记忆库-Feedback】[{}] 用户要求清除记忆，已删除 {} 条", userId, n);
            return new Outcome(Signal.CLEAR, n);
        }

        if (containsAny(message, properties.getFeedback().getNegativeKeywords())) {
            int n = adjust(userId, lastRetrieved, -2);
            log.info("【记忆库-Feedback】[{}] 负向反馈，削弱 {} 条记忆", userId, n);
            return new Outcome(Signal.NEGATIVE, n);
        }

        if (containsAny(message, properties.getFeedback().getPositiveKeywords())) {
            int n = adjust(userId, lastRetrieved, +2);
            log.info("【记忆库-Feedback】[{}] 正向反馈，加强 {} 条记忆", userId, n);
            return new Outcome(Signal.POSITIVE, n);
        }

        if (properties.getFeedback().isImplicitEnabled()) {
            int n = implicitReinforce(userId, message);
            if (n > 0) {
                log.debug("【记忆库-Feedback】[{}] 隐式反馈：重复提及，强化 {} 条", userId, n);
                return new Outcome(Signal.POSITIVE, n);
            }
        }
        return new Outcome(Signal.NONE, 0);
    }

    private int adjust(String userId, List<MemoryRecord> memories, int delta) {
        if (memories == null || memories.isEmpty()) return 0;
        int n = 0;
        for (MemoryRecord r : memories) {
            if (r.getId() == null) continue;
            if (memoryRagService.adjustImportance(userId, r.getId(), delta)) n++;
        }
        return n;
    }

    /**
     * 隐式反馈：用户又提了一件和某条记忆高度重合的事 → 这条记忆有用，强化它。
     */
    private int implicitReinforce(String userId, String message) {
        double threshold = properties.getFeedback().getImplicitSimilarityThreshold();
        int n = 0;
        for (MemoryRecord r : memoryRagService.listAll(userId, false)) {
            if (r.getContent() == null) continue;
            if (TextSimilarity.bigramJaccard(message, r.getContent()) >= threshold) {
                memoryRagService.reinforce(userId, r.getId());
                n++;
            }
        }
        return n;
    }

    /** 关键词命中判断，含单字关键词的短句限制。 */
    private static boolean containsAny(String message, List<String> keywords) {
        if (keywords == null) return false;
        String trimmed = message.trim();
        for (String kw : keywords) {
            if (kw == null || kw.isEmpty()) continue;
            if (!trimmed.contains(kw)) continue;
            // 单字关键词太容易误命中（"对" 出现在 "对比/对话/面对" 里），
            // 只在短句（基本就是一句纯确认）时才认
            if (kw.length() <= 1 && trimmed.length() > SHORT_UTTERANCE_MAX) {
                continue;
            }
            return true;
        }
        return false;
    }
}
