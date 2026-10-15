package com.demo.chroma.doc;

import lombok.Getter;

import java.util.List;

/**
 * 公共知识库领域配置。每个知识领域（通识教育/心理学/旅行/法律）提供一个子类实例（@Component），
 * 构造器填死全部字段；DocKnowledgeRegistry 注入 List<DocKnowledgeConfig> 自动收集。
 */
@Getter
public class DocKnowledgeConfig {

    private final String domainId;
    private final String collectionPrefix;
    private final String knowledgeDir;
    private final List<SubLibrary> subLibraries;
    private final String systemPrompt;
    private final int topK;
    private final double distanceThreshold;
    private final int maxRound;
    private final String disclaimer;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int embedBatchSize;

    // ---- 三级过滤链（复用 LawRetrievalPolicy）的可选参数，默认「不启用」 ----

    /**
     * 相对 cutoff 系数，形如 1.08（法律库同参）。<b>0 = 不启用</b>。
     *
     * <p>为什么默认不启用而不是给个宽松值：{@code LawRetrievalPolicy} 内部会做
     * {@code Math.max(1.0, ratio)}，所以 <b>ratio=1.0 反而是最严的</b>（cutoff=top1，只留最像的一条），
     * 不是"禁用"。要表达"禁用"只能由调用方显式跳过这一步，故此处用 0 作哨兵值。
     *
     * <p>也不能默认就给 1.08：那会让<b>已上线的通识/心理学也悄悄被收紧</b>
     * （等于改了两个既有学科的行为）。默认不启用 → 现有学科零回归，由新学科显式开启。
     */
    private final double relativeCutoffRatio;

    /** 内容最短长度，过短的（如 OCR 产物 "1. 。"）丢弃。0 = 不启用。 */
    private final int minContentLength;

    /** 全被滤掉时是否强制保留最近一条（防"库里有内容却答'无法解答'"）。 */
    private final boolean keepAtLeastOne;

    /** 改造前的 12 参构造：三级过滤默认<b>不启用</b>，行为与改造前逐字一致。 */
    protected DocKnowledgeConfig(String domainId, String collectionPrefix, String knowledgeDir,
                                 List<SubLibrary> subLibraries, String systemPrompt,
                                 int topK, double distanceThreshold, int maxRound,
                                 String disclaimer, int chunkSize, int chunkOverlap, int embedBatchSize) {
        this(domainId, collectionPrefix, knowledgeDir, subLibraries, systemPrompt,
                topK, distanceThreshold, maxRound, disclaimer, chunkSize, chunkOverlap, embedBatchSize,
                0.0, 0, true);
    }

    /** 15 参构造：显式开启三级过滤（绝对阈值 + 相对 cutoff + 内容长度 + 条数下限）。 */
    protected DocKnowledgeConfig(String domainId, String collectionPrefix, String knowledgeDir,
                                 List<SubLibrary> subLibraries, String systemPrompt,
                                 int topK, double distanceThreshold, int maxRound,
                                 String disclaimer, int chunkSize, int chunkOverlap, int embedBatchSize,
                                 double relativeCutoffRatio, int minContentLength, boolean keepAtLeastOne) {
        this.relativeCutoffRatio = relativeCutoffRatio;
        this.minContentLength = minContentLength;
        this.keepAtLeastOne = keepAtLeastOne;
        this.domainId = domainId;
        this.collectionPrefix = collectionPrefix;
        this.knowledgeDir = knowledgeDir;
        this.subLibraries = subLibraries;
        this.systemPrompt = systemPrompt;
        this.topK = topK;
        this.distanceThreshold = distanceThreshold;
        this.maxRound = maxRound;
        this.disclaimer = disclaimer;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.embedBatchSize = embedBatchSize;
    }

    /** 文件名 → 子库 id：取第一个 '_' 前部分，先按 id 匹配，再按 displayName/aliases 反查；未命中返回 null */
    public String resolveSubLibraryId(String fileName) {
        if (fileName == null) return null;
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String prefix = stem;
        int us = stem.indexOf('_');
        if (us > 0) prefix = stem.substring(0, us);
        prefix = prefix.trim();
        if (prefix.isEmpty()) return null;
        for (SubLibrary sl : subLibraries) {
            if (sl.id.equals(prefix)) return sl.id;
        }
        for (SubLibrary sl : subLibraries) {
            if (sl.displayName.equals(prefix) || (sl.aliases != null && sl.aliases.contains(prefix))) {
                return sl.id;
            }
        }
        // 单子库领域：所有文件归入唯一子库（如旅行知识库）
        if (subLibraries.size() == 1) return subLibraries.get(0).id;
        return null;
    }

    public String collectionName(String subLibId) {
        return collectionPrefix + subLibId;
    }

    public String fingerprintFile() {
        return knowledgeDir + "/.fingerprints.json";
    }

    /** 子库定义 */
    public static class SubLibrary {
        public final String id;
        public final String displayName;
        public final List<String> aliases;

        public SubLibrary(String id, String displayName, List<String> aliases) {
            this.id = id;
            this.displayName = displayName;
            this.aliases = aliases == null ? List.of() : aliases;
        }
    }
}
