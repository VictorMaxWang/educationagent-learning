package com.demo.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 零依赖、零 token 的本地中文关键词抽取。
 *
 * <h3>为什么不用现成的分词器</h3>
 * 本项目的离线 Maven 仓库里只有 {@code lucene-core} 和 {@code lucene-analysis-common}，
 * <b>没有 {@code lucene-analysis-smartcn}</b>，{@code SmartChineseAnalyzer} 拿不到；
 * 全仓库也没有任何中文停用词表。而这是"每轮对话都要跑一次"的路径，不可能引大词典或调模型。
 *
 * <h3>做法：按停用字切片段，而不是切 n-gram</h3>
 * 路由那边的 {@code IntentTfIdfStore.tokenize} 产出的是带 {@code u}/{@code b} 前缀的
 * unigram/bigram，做分类特征很好，但拿来当**标签**没法看（"用b户"这种东西）。
 * 这里反过来：先用功能字把句子切成片段，片段天然就是词级别的可读单元——
 * <pre>
 *   我最近压力有点大   → 最近压力 / 大      → 最近压力
 *   我下周要搬家      → 下周 / 搬家        → 下周, 搬家
 *   今天天气怎么样     → 今天天气 /         → 今天天气
 * </pre>
 *
 * <h3>局限（已知且接受）</h3>
 * 无词典就做不到真正的词级切分——切出来的可能比词长（"最近压力"）或比词短。
 * 它<B>只用来做脑图节点标签和粗略分组</b>，不参与检索排序（检索走 Lucene 的 CJK 全文字段）。
 */
public final class KeywordExtractor {

    private KeywordExtractor() {
    }

    /**
     * 功能字：出现在片段里就把片段切开并丢弃。
     *
     * <p>只收"单独出现时不承载信息"的字。刻意<b>不</b>收 上/下/周/天/年/月/大 这类——
     * 它们看着虚，但"下周""今天""压力大"里的信息就在这几个字上，
     * 尤其 上/下 出现在"上学""下周""下班"里，切掉会把词打碎（实测过）。
     */
    private static final String STOP_CHARS =
            "的了呢吧吗啊呀哦嘛么嗯唔呃唉"
                    + "是在和与就都也这那我你他她它们个"
                    + "很会要能可以之其并且但因为所以还又再把被让给对从向到于而或及等些"
                    + "有没不太挺蛮什怎样如何请帮想说做只更着过来去点";

    private static final Set<Character> STOP = toCharSet(STOP_CHARS);

    /** 单个关键词的最大长度：再长就不是"词"而是半句话了。 */
    private static final int MAX_TERM_CHARS = 8;

    /** 片段短于这个长度就不算关键词（单字噪声太大）。 */
    private static final int MIN_TERM_CHARS = 2;

    /** 默认取几个关键词。 */
    public static final int DEFAULT_MAX = 5;

    private static Set<Character> toCharSet(String s) {
        Set<Character> set = new HashSet<>(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            set.add(s.charAt(i));
        }
        return set;
    }

    /**
     * 抽取关键词。
     *
     * @param text 原文；null/空白 → 空列表
     * @param max  最多返回几个；&le;0 时用 {@link #DEFAULT_MAX}
     * @return 按重要性降序的关键词；没有内容词时返回空列表
     */
    public static List<String> extract(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        int limit = max > 0 ? max : DEFAULT_MAX;

        // 与 TextSimilarity.normalize / IntentTfIdfStore.tokenize 同一套归一化正则，
        // 保证"什么算分隔符"在记忆模块内部只有一个答案
        String s = text.replaceAll("[\\s\\p{P}\\p{S}]+", "");
        if (s.isEmpty()) return out;

        // 按停用字切成候选片段
        Map<String, Integer> counts = new LinkedHashMap<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (STOP.contains(c)) {
                flush(buf, counts);
            } else {
                buf.append(c);
            }
        }
        flush(buf, counts);
        if (counts.isEmpty()) return out;

        // 打分：长片段更具体，重复出现更重要
        record Scored(String term, double score) {
        }
        List<Scored> scored = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double score = e.getKey().length() * (1.0 + 0.5 * Math.log1p(e.getValue() - 1));
            scored.add(new Scored(e.getKey(), score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Scored::term));

        for (Scored sc : scored) {
            if (out.size() >= limit) break;
            out.add(sc.term());
        }
        return out;
    }

    /** 逗号拼接形式，直接落库到 {@code MemoryRecord.keywords}。 */
    public static String extractJoined(String text, int max) {
        return String.join(",", extract(text, max));
    }

    /** 收集一个片段：丢太短的，超长的截断，计入词频。 */
    private static void flush(StringBuilder buf, Map<String, Integer> counts) {
        if (buf.isEmpty()) return;
        String term = buf.toString();
        buf.setLength(0);

        if (term.length() < MIN_TERM_CHARS) return;
        if (term.length() > MAX_TERM_CHARS) {
            term = term.substring(0, MAX_TERM_CHARS);
        }
        counts.merge(term, 1, Integer::sum);
    }
}
