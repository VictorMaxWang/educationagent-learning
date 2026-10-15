package com.demo.chroma;

import com.demo.router.IntentLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 法律 RAG 的对话链路门控 + 上下文块构造。
 *
 * <h3>为什么需要这个类</h3>
 * 本项目原先法律 RAG <b>根本没有接进对话链路</b>——它只能通过
 * {@code POST /api/desktop/law/search} 手工调用，而 {@code AgentExecutor.buildMessages}
 * 里只注入了旅行知识库、个人知识库、长期记忆三块。
 * 所以方案书说的"只在 LAW 意图命中才执行"，在本项目里不是"修 bug"，而是<b>新增能力</b>：
 * 先把它接进对话，再谈"什么时候不该接"。
 *
 * <p>而一旦接进对话，门控就是必需的：法律检索会嵌入查询、打 Chroma、再拼一大段条文进提示词，
 * 对"今天天气怎么样"做这件事纯属浪费，还会把无关法条塞进上下文污染回答。
 *
 * <h3>与方案书的一处顺序调整（有意的）</h3>
 * 方案书给的优先级是「intent==LAW → 命中强制法律词 → 命中跳过词 → 否则 false」。
 * 但按这个顺序，<b>跳过词永远轮不到</b>（强制词命中就返回 true 了），等于死代码。
 * 这里把跳过词提到强制词前面，让它真正起作用：
 * 「把合同文件存到知识库」同时含 {@code 合同}（强制）与 {@code 文件}（跳过），
 * 这明显是文件操作而不是法律咨询，应当跳过。
 * 显式的 {@code intent==LAW} 仍然最高优先，所以方案书那 4 条验收标准全部成立。
 */
@Slf4j
@Component
public class LawContextProvider {

    /** 命中即强制查法律（即使路由没判成 LAW）。 */
    private static final List<String> FORCE_LAW_KEYWORDS = List.of(
            "法条", "违法", "判刑", "判几年", "合同", "侵权", "离婚", "财产", "纠纷",
            "赔偿", "起诉", "法律", "正当防卫", "民法典", "劳动法", "刑法",
            "租房", "押金", "遗产", "抚养", "赡养", "违约金", "工伤", "仲裁", "辞退");

    /** 命中即跳过法律检索（明确不是法律咨询的领域）。 */
    private static final List<String> SKIP_KEYWORDS = List.of(
            "天气", "气温", "温度", "下雨", "提醒", "日程", "文件", "图片", "照片",
            "你好", "晚上好", "早上好", "订单", "车票", "高铁", "宠物", "打卡", "日记");

    /** 单条条文注入上下文时的截断长度。 */
    private static final int MAX_ARTICLE_CHARS = 400;

    /** 整个法律上下文块的长度上限，避免挤占对话预算。 */
    private static final int MAX_BLOCK_CHARS = 2000;

    private final LawKnowledgeService lawKnowledgeService;

    public LawContextProvider(LawKnowledgeService lawKnowledgeService) {
        this.lawKnowledgeService = lawKnowledgeService;
    }

    /**
     * 是否应当为本条消息检索法律库。
     *
     * @param intent  路由判定的意图，可为 {@code null}（未识别）
     * @param message 用户原始消息
     */
    public boolean shouldQuery(IntentLabel intent, String message) {
        if (intent == IntentLabel.LAW) {
            return true;
        }
        if (message == null || message.isBlank()) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        // 跳过词优先于强制词：见类注释的说明
        if (containsAny(msg, SKIP_KEYWORDS)) {
            return false;
        }
        return containsAny(msg, FORCE_LAW_KEYWORDS);
    }

    /**
     * 构造注入提示词的法律上下文块。
     *
     * @return 可直接作为 {@code SystemMessage} 的文本；不需要检索或检索无结果时返回 {@code null}
     */
    public Block buildBlock(IntentLabel intent, String message, String libId) {
        if (!shouldQuery(intent, message)) {
            return null;
        }
        if (intent != IntentLabel.LAW) {
            log.info("⚖️ 法律RAG 门控：意图={} 但关键词强制命中，仍执行检索", intent);
        }

        LawKnowledgeService.LawRetrieval retrieval;
        try {
            retrieval = lawKnowledgeService.retrieveForContext(message, libId);
        } catch (Exception e) {
            // 法律 RAG 失败绝不能让主对话失败
            log.warn("⚖️ 法律检索异常，本轮不注入法律上下文：{}", e.toString());
            return null;
        }

        if (retrieval.isEmpty()) {
            // 库里确实没有相关内容：把"暂未收录"如实告诉模型，让它别硬编
            if (retrieval.note() != null) {
                return new Block("【法律检索结果】\n" + retrieval.note(), retrieval.note(), false, 0);
            }
            return null;
        }

        StringBuilder sb = new StringBuilder("【法律条文参考（只引用与问题直接相关的条文，无关条文不要提）】\n");
        int i = 1;
        for (Map<String, Object> art : retrieval.articles()) {
            String content = String.valueOf(art.get("content"));
            if (content.length() > MAX_ARTICLE_CHARS) {
                content = content.substring(0, MAX_ARTICLE_CHARS) + "…";
            }
            sb.append("- ").append(i++).append(") [").append(art.get("source")).append("] ")
                    .append(content).append('\n');
            if (sb.length() > MAX_BLOCK_CHARS) {
                sb.append("…（更多条文已省略）\n");
                break;
            }
        }
        if (retrieval.note() != null) {
            sb.append("注：").append(retrieval.note());
        }

        log.info("⚖️ 法律RAG 注入上下文：lib={}（请求 {}，降级={}）条文 {} 条",
                retrieval.usedLib(), retrieval.requestedLib(), retrieval.degraded(), retrieval.articles().size());
        return new Block(sb.toString(), retrieval.note(), retrieval.degraded(), retrieval.articles().size());
    }

    private static boolean containsAny(String msg, List<String> keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw)) return true;
        }
        return false;
    }

    /**
     * @param text      注入用的完整文本
     * @param note      降级/未收录提示（null 表示无需提示）
     * @param degraded  是否发生了软降级
     * @param articleCount 注入的条文数
     */
    public record Block(String text, String note, boolean degraded, int articleCount) {
    }
}
