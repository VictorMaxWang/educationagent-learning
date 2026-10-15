package com.demo.memory.graph;

import java.util.List;

/**
 * 记忆脑图的数据传输结构。
 *
 * <p><b>刻意不在后端算坐标。</b>布局交给前端一次性算（径向分代布局），
 * 因为方案书踩过一个坑：用物理模拟 + ResizeObserver 会导致死循环重排。
 * 后端只给"节点 + 边 + 分代"，前端用 {@code useMemo} 算一次坐标就固定下来，
 * 既不抖动也可复现（同样的数据每次渲染位置完全一致）。
 */
public final class MemoryGraphDTO {

    private MemoryGraphDTO() {
    }

    /**
     * 记忆分代——借用 JVM GC 的分代视角，让"这条记忆还活着吗"一眼可见。
     *
     * <p>命名直接沿用方案书的 NEW / SURVIVOR / OLD / PENDING_GC：
     * 它在视觉上自解释（NEW 是刚生成的、PENDING_GC 是即将被回收的），
     * 而且和"衰减调度每天在做什么"形成对应关系。
     */
    public enum Generation {
        /** 新生成，尚未被访问过。 */
        NEW,
        /** 存活过若干次访问，已证明有用。 */
        SURVIVOR,
        /** 长期高频且高重要度，属于稳定事实。 */
        OLD,
        /** 低重要度或过老的情景记忆，下次衰减就会被删除。 */
        PENDING_GC
    }

    /**
     * 一个记忆节点。
     *
     * @param id          记忆 ID；根节点为 {@code "__root__"}
     * @param label       显示文本。优先用 {@code keywords}（短、可读），没有关键词才回退到正文截断
     * @param fullContent 完整内容，供前端 hover / 点击展示
     * @param keywords    本地抽取的关键词，逗号分隔；可能为空
     * @param gen         分代
     * @param type        记忆类型名
     * @param importance  重要度 1~10
     * @param accessCount 被检索命中次数
     * @param createdAt   创建时间（epoch millis）
     * @param ageDays     年龄（天）
     * @param size        建议绘制半径（根节点固定 30）
     * @param root        是否为根节点
     */
    public record Node(String id,
                       String label,
                       String fullContent,
                       String keywords,
                       String gen,
                       String type,
                       int importance,
                       int accessCount,
                       long createdAt,
                       long ageDays,
                       double size,
                       boolean root) {
    }

    /** 边类型：根 → 记忆的归属关系（用户画像 / 经历事件）。 */
    public static final String EDGE_HIERARCHY = "HIERARCHY";

    /** 边类型：记忆 ↔ 记忆的语义近邻关系。 */
    public static final String EDGE_SIMILARITY = "SIMILARITY";

    /**
     * 一条边。
     *
     * @param source 源节点 ID
     * @param target 目标节点 ID
     * @param weight 权重；{@link #EDGE_HIERARCHY} 边为重要度归一化（0~1），
     *               {@link #EDGE_SIMILARITY} 边为余弦相似度（0~1）
     * @param type   边类型，见 {@link #EDGE_HIERARCHY} / {@link #EDGE_SIMILARITY}
     */
    public record Link(String source, String target, double weight, String type) {
    }

    /**
     * 整张图。
     *
     * @param userId    所属用户
     * @param nodes     节点（第 0 个是根）
     * @param links     边
     * @param truncated 是否因为超过了 maxNodes 而截断
     * @param stats     统计。同时含两个维度：
     *                  <b>分代计数</b>（{@code NEW/SURVIVOR/OLD/PENDING_GC}，前端图例用）与
     *                  <b>汇总指标</b>（{@code totalCount/nodeCount/linkCount/rootDegree/
     *                  typeDistribution/traceId}）。两者维度不同（Generation vs MemoryType），并存不冗余。
     */
    public record Graph(String userId,
                        List<Node> nodes,
                        List<Link> links,
                        boolean truncated,
                        java.util.Map<String, Object> stats) {
    }
}
