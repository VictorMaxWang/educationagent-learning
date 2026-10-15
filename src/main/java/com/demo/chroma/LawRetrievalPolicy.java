package com.demo.chroma;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 法律检索的过滤策略：绝对阈值 → 相对 cutoff → 条数下限保护。
 *
 * <p>抽成纯函数是为了能脱离 Chroma 单测——过滤策略是"过度召回"这个问题的唯一防线，
 * 而过度召回的后果很隐蔽：噪声法条会被拼进提示词，模型据此一本正经地引用无关条文。
 *
 * <h3>为什么要两道过滤</h3>
 * 旧实现只有一道绝对阈值（默认 1.2），实测 query=「正当防卫怎么判」时
 * 三条召回的距离是 0.4397 / 0.5096 / 0.5120，全部小于 1.2 → 三条全保留，
 * 其中第 2、3 条讲的是"监护人指定""堆放物倒塌"，与问题毫无关系。
 *
 * <p>Chroma 返回的距离是<b>越小越相关</b>，所以真正可靠的信息是"相对差距"：
 * 第 1 条 0.4397 与第 2 条 0.5096 差了 16%，说明第 1 条明显更贴题，
 * 后面两条只是"同一个库里也不太相关的东西"。按 {@code top1 * 1.08} 卡一刀正好把它们切掉。
 */
public final class LawRetrievalPolicy {

    /** Chroma 距离的兜底值：拿不到距离时视为"很远"，从而被过滤掉。 */
    private static final double MISSING_DISTANCE = 9.0;

    private LawRetrievalPolicy() {
    }

    /**
     * 应用过滤策略。
     *
     * @param hits               Chroma 召回结果（按距离升序），每项含 {@code content} 与 {@code distance}
     * @param distanceThreshold  绝对阈值：距离大于它一律丢弃
     * @param relativeRatio      相对 cutoff 系数，形如 1.08；小于 1 会被当作 1（保证至少留 top1）
     * @param minContentLength   条文内容最短长度，过短的（如"1. 。"这类空条文）丢弃
     * @param keepAtLeastOne     是否保证至少保留一条（避免全部过滤导致空结果）
     */
    public static Decision filter(List<Map<String, Object>> hits,
                                  double distanceThreshold,
                                  double relativeRatio,
                                  int minContentLength,
                                  boolean keepAtLeastOne) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> passedContentAndThreshold = new ArrayList<>();

        if (hits == null || hits.isEmpty()) {
            return new Decision(kept, log, "no-hits");
        }

        // 第 1 道：空/过短条文 + 绝对阈值
        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> h = hits.get(i);
            double dist = distance(h);
            String content = content(h);
            String tag = "第" + (i + 1) + "条(d=" + String.format("%.4f", dist) + ")";

            if (content.isBlank()) {
                log.add(tag + " 丢弃：内容为空");
                continue;
            }
            if (content.length() < minContentLength) {
                log.add(tag + " 丢弃：内容过短(" + content.length() + "<" + minContentLength + ")");
                continue;
            }
            if (dist > distanceThreshold) {
                log.add(tag + " 丢弃：超绝对阈值 " + distanceThreshold);
                continue;
            }
            passedContentAndThreshold.add(h);
        }

        if (passedContentAndThreshold.isEmpty()) {
            return new Decision(kept, log, "all-filtered-by-threshold");
        }

        // 第 2 道：相对 cutoff（以存活项里最近的那条为基准）
        double best = distance(passedContentAndThreshold.get(0));
        for (Map<String, Object> h : passedContentAndThreshold) {
            best = Math.min(best, distance(h));
        }
        double ratio = Math.max(1.0, relativeRatio);
        double cutoff = best * ratio;

        for (int i = 0; i < passedContentAndThreshold.size(); i++) {
            Map<String, Object> h = passedContentAndThreshold.get(i);
            double dist = distance(h);
            String tag = "存活第" + (i + 1) + "条(d=" + String.format("%.4f", dist) + ")";
            if (dist <= cutoff) {
                kept.add(h);
            } else {
                log.add(tag + " 丢弃：超相对 cutoff " + String.format("%.4f", cutoff) + "（top1×" + ratio + "）");
            }
        }

        // 第 3 道：条数下限保护。
        // 正常情况下走不到这里——cutoff = top1 × ratio 且 ratio ≥ 1，所以 top1 必然存活。
        // 保留这段是为了防御"未来有人把 ratio 配成小于 1"或浮点比较边界，
        // 宁可多留一条噪声，也不要返回空结果让用户看到"暂无法解答"。
        if (kept.isEmpty() && keepAtLeastOne) {
            kept.add(passedContentAndThreshold.get(0));
            log.add("触发条数下限保护：强制保留最近的一条 d="
                    + String.format("%.4f", distance(passedContentAndThreshold.get(0))));
        }
        return new Decision(kept, log, kept.isEmpty() ? "empty" : "ok");
    }

    /** 读取距离；缺失或非数字时给一个大值，使其自然被过滤。 */
    public static double distance(Map<String, Object> hit) {
        if (hit == null) return MISSING_DISTANCE;
        Object d = hit.get("distance");
        return d instanceof Number n ? n.doubleValue() : MISSING_DISTANCE;
    }

    /** 读取条文正文，null 安全。 */
    public static String content(Map<String, Object> hit) {
        if (hit == null) return "";
        Object c = hit.get("content");
        return c == null ? "" : String.valueOf(c).trim();
    }

    /**
     * @param kept  最终保留的召回项
     * @param log   逐条的保留/丢弃原因（供日志排查，方案书要求"输出前日志增加每条的距离与丢弃原因"）
     * @param state 整体结论：{@code ok} / {@code empty} / {@code no-hits} / {@code all-filtered-by-threshold}
     */
    public record Decision(List<Map<String, Object>> kept, List<String> log, String state) {
    }
}
