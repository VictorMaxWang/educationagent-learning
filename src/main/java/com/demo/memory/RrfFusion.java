package com.demo.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 加权 RRF（Reciprocal Rank Fusion）融合算法——纯函数，可脱离 Chroma / Lucene 单测。
 *
 * <p>{@code fusedScore = memoryWeight × 1/(k + memRank + 1) + lawWeight × 1/(k + lawRank + 1)}
 *
 * <h3>为什么只看排名、不看分数</h3>
 * 两路召回器的分数<b>量纲不同、分布不同</b>：记忆侧是 Lucene 余弦（集中在 0.3~0.6），
 * 法律侧是 Chroma 距离换算出的相似度。把它们归一化后相加，等于拿两把刻度不同的尺子量同一条边。
 * 排名则天然可比，这也是 RRF 在异构检索器融合上被广泛采用的原因。
 *
 * <h3>权重怎么理解</h3>
 * {@code k} 是平滑常数（业界常用 60）。k 越大，名次差异被压得越平缓；
 * k 很小时第 1 名与第 2 名的差距会非常悬殊。
 * 权重则决定"哪一路的第 1 名更值钱"——法律条文是权威原文，默认权重高于个人记忆。
 *
 * <h3>⚠️ 为什么下限过滤作用在 originalScore 而不是 fusedScore</h3>
 * RRF 融合分的量级是 {@code 权重 / (k + 排名)}，k=60 时只有 <b>0.005 ~ 0.016</b>，
 * 即使"两路都排第 1"也只有 {@code 0.3/61 + 0.5/61 ≈ 0.0131}。
 * 方案书给的配置写的是 {@code memory.hybrid.min-score=0.6}——
 * 如果把它当成融合分下限，<b>0.6 大于理论最大值 0.0131，会把所有结果全部截断</b>，
 * {@code fusedSearch} 永远返回空、整条混合 RAG 静默失效且不报错。
 * 所以这里把该参数解释为"融合<b>前</b>的原始相关度下限"——
 * 只有这个解释下 0.6 才是有意义的（记忆余弦 / 法律相似度都是 0~1）。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * 融合两路结果。
     *
     * @param memoryHits      记忆侧召回，已按相关度降序
     * @param lawHits         法律侧召回，已按相关度降序
     * @param k               RRF 平滑常数
     * @param memoryWeight    记忆侧权重
     * @param lawWeight       法律侧权重
     * @param dedupeThreshold 内容去重阈值（字符 bigram Jaccard）
     * @param topK            最终返回条数上限
     * @param minOriginalScore 原始相关度下限，作用于<b>融合前的 originalScore</b>
     */
    public static List<FusedResult> fuse(List<FusedResult> memoryHits,
                                        List<FusedResult> lawHits,
                                        int k,
                                        double memoryWeight,
                                        double lawWeight,
                                        double dedupeThreshold,
                                        int topK,
                                        double minOriginalScore) {
        int smooth = Math.max(1, k);
        List<FusedResult> acc = new ArrayList<>();

        if (memoryHits != null) {
            for (int i = 0; i < memoryHits.size(); i++) {
                FusedResult r = memoryHits.get(i);
                if (r.getOriginalScore() < minOriginalScore) continue;
                r.setFusedScore(memoryWeight * 1.0 / (smooth + i + 1));
                mergeInto(acc, r, dedupeThreshold);
            }
        }
        if (lawHits != null) {
            for (int i = 0; i < lawHits.size(); i++) {
                FusedResult r = lawHits.get(i);
                if (r.getOriginalScore() < minOriginalScore) continue;
                r.setFusedScore(lawWeight * 1.0 / (smooth + i + 1));
                mergeInto(acc, r, dedupeThreshold);
            }
        }

        acc.sort(Comparator.comparingDouble(FusedResult::getFusedScore).reversed());

        int limit = topK > 0 ? Math.min(topK, acc.size()) : acc.size();
        List<FusedResult> out = new ArrayList<>(limit);
        int rank = 1;
        for (int i = 0; i < limit; i++) {
            FusedResult r = acc.get(i);
            r.setRank(rank++);
            out.add(r);
        }
        return out;
    }

    /**
     * 把候选并入结果列表：与已有条目高度相似时<b>累加融合分</b>而不是新增一条。
     *
     * <p>累加正是 RRF 的价值所在——同一条内容在两路都排前面，
     * 说明它很可能是核心事实，得分应当叠加；
     * 若只是"发现重复就丢掉一条"，这个信号就没了。
     */
    private static void mergeInto(List<FusedResult> acc, FusedResult candidate, double threshold) {
        for (FusedResult existing : acc) {
            if (TextSimilarity.bigramJaccard(existing.getContent(), candidate.getContent()) >= threshold) {
                existing.setFusedScore(existing.getFusedScore() + candidate.getFusedScore());
                existing.setHitSources(existing.getHitSources() + 1);
                existing.getMetadata().put("alsoFrom", candidate.getSource());
                return;
            }
        }
        candidate.setHitSources(1);
        acc.add(candidate);
    }
}
