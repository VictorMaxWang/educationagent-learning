package com.demo.memory;

import java.util.HashSet;
import java.util.Set;

/**
 * 文本相似度工具：字符 bigram Jaccard。
 *
 * <p>用于记忆去重（"用户叫张三" 与 "用户的名字是张三" 高度重合）。
 * 选字符 bigram 而不是分词，是因为中文短句分词成本高、歧义大，
 * 而记忆去重只需要"看起来是不是同一句话"，字符级已经够用。
 *
 * <p>⚠️ 本项目 {@code chroma/UserMemoryService} 里有一份功能相同的私有实现
 * （阈值同为 0.82）。这里没有去改它，是为了避免在不必要的文件上制造改动；
 * 两处逻辑应当合并到本类，留待后续清理。
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /**
     * 字符 bigram 的 Jaccard 相似度。
     *
     * @return 0.0 ~ 1.0；任一为空时返回 0
     */
    public static double bigramJaccard(String a, String b) {
        if (a == null || b == null) return 0.0;
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;

        Set<String> sa = bigrams(x);
        Set<String> sb = bigrams(y);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;

        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    /** 去掉空白与标点，统一小写——只比较"实质内容"。 */
    private static String normalize(String s) {
        return s.replaceAll("[\\s\\p{P}\\p{S}]+", "").toLowerCase();
    }

    /** 单字串没有 bigram，退化为单字集合，避免长度 1 的文本永远相似度为 0。 */
    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s.length() == 1) {
            out.add(s);
            return out;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }
}
