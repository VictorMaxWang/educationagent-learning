package com.demo.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合算法测试。
 *
 * <p>用可手算的名次构造用例：k=60 时第 1 名的贡献是 {@code 1/61 ≈ 0.01639}，
 * 第 2 名是 {@code 1/62 ≈ 0.01613}——差距很小，这正是 RRF "名次差被压平"的特性。
 */
class RrfFusionTest {

    private static final int K = 60;
    private static final double MEM_W = 0.3;
    private static final double LAW_W = 0.5;
    private static final double DEDUPE = 0.95;

    private static FusedResult r(String source, String content, double original) {
        return FusedResult.builder()
                .source(source).content(content).originalScore(original).build();
    }

    private static List<FusedResult> mem(String... contents) {
        return java.util.Arrays.stream(contents)
                .map(c -> r(FusedResult.SOURCE_MEMORY, c, 0.8)).toList();
    }

    private static List<FusedResult> law(String... contents) {
        return java.util.Arrays.stream(contents)
                .map(c -> r(FusedResult.SOURCE_LAW, c, 0.9)).toList();
    }

    /** 法律权重大于记忆权重时，法律侧第 1 名应当排在记忆侧第 1 名之前。 */
    @Test
    void 权重大的一路排前() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("用户喜欢清淡口味"), law("正当防卫的相关规定"),
                K, MEM_W, LAW_W, DEDUPE, 10, 0.0);

        assertEquals(2, out.size());
        assertEquals(FusedResult.SOURCE_LAW, out.get(0).getSource());
        assertEquals(1, out.get(0).getRank());
        assertEquals(2, out.get(1).getRank());
    }

    /** 把权重反过来，排名应当跟着翻转。 */
    @Test
    void 权重翻转后排名翻转() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("用户喜欢清淡口味"), law("正当防卫的相关规定"),
                K, 0.9, 0.1, DEDUPE, 10, 0.0);
        assertEquals(FusedResult.SOURCE_MEMORY, out.get(0).getSource());
    }

    /**
     * 同一条内容两路都命中 → 融合分累加、hitSources=2、只保留一条。
     * <p>这是 RRF 的核心价值：两路都排前面说明它是核心事实。
     */
    @Test
    void 两路命中同一内容时累加分数且只留一条() {
        String same = "用户叫张三";
        List<FusedResult> out = RrfFusion.fuse(
                mem(same), law(same), K, MEM_W, LAW_W, DEDUPE, 10, 0.0);

        assertEquals(1, out.size(), "重复内容应当合并成一条");
        FusedResult merged = out.get(0);
        assertEquals(2, merged.getHitSources());

        double expected = MEM_W * 1.0 / (K + 1) + LAW_W * 1.0 / (K + 1);
        assertEquals(expected, merged.getFusedScore(), 1e-9, "两路的贡献应当相加");
    }

    /** 累加之后的分数必须高于任何单路命中，否则"两路都命中"反而没有优势。 */
    @Test
    void 两路命中的条目排名高于单路() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("用户叫张三", "用户喜欢跑步"),
                law("用户叫张三"),
                K, MEM_W, LAW_W, DEDUPE, 10, 0.0);

        assertEquals("用户叫张三", out.get(0).getContent());
        assertEquals(2, out.get(0).getHitSources());
    }

    /** 相似但不同的内容不应被误合并。 */
    @Test
    void 相似度不足时不合并() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("用户喜欢跑步"), law("正当防卫怎么判"),
                K, MEM_W, LAW_W, DEDUPE, 10, 0.0);
        assertEquals(2, out.size());
    }

    @Test
    void topK截断() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("记忆一", "记忆二", "记忆三"), law("法条一", "法条二"),
                K, MEM_W, LAW_W, DEDUPE, 3, 0.0);
        assertEquals(3, out.size());
        assertEquals(1, out.get(0).getRank());
        assertEquals(3, out.get(2).getRank());
    }

    /**
     * minScore 作用在<b>融合前的原始相关度</b>上，低于它的候选在融合前就被剔除。
     *
     * <p>这条用例同时是一个"别把 minScore 当成融合分下限"的守门测试：
     * 融合分的量级只有 {@code 权重/(k+排名)}，k=60 时最大也就 0.0131，
     * 若按融合分过滤，传 0.6 会把结果全部清空。
     */
    @Test
    void minScore作用在原始相关度上() {
        // 三条原始相关度 0.8，一条 0.4
        List<FusedResult> hits = List.of(
                r(FusedResult.SOURCE_MEMORY, "高相关一", 0.8),
                r(FusedResult.SOURCE_MEMORY, "低相关", 0.4));

        List<FusedResult> strict = RrfFusion.fuse(hits, null, K, MEM_W, LAW_W, DEDUPE, 10, 0.5);
        assertEquals(1, strict.size(), "0.4 低于原始相关度下限 0.5，应当在融合前被剔除");
        assertEquals("高相关一", strict.get(0).getContent());

        // 融合分本身很小，但下限是针对原始相关度的，所以不该被误清空
        assertTrue(strict.get(0).getFusedScore() < 0.05,
                "融合分本来就很小（" + strict.get(0).getFusedScore() + "），不能用它当下限");
    }

    /**
     * 回归守门：若把 minScore 当融合分下限并按方案书的 0.6 配置，
     * 结果必然为空——这个用例把"理论最大值 < 0.6"钉死，防止有人改回去。
     */
    @Test
    void 融合分理论最大值远小于方案书给的0_6() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("记忆", "记忆二"), law("法条", "法条二"),
                K, MEM_W, LAW_W, DEDUPE, 10, 0.0);
        double max = out.stream().mapToDouble(FusedResult::getFusedScore).max().orElse(0);
        assertTrue(max < 0.02,
                "融合分最大也就约 0.0131，实际=" + max + "；所以 0.6 绝不能用作融合分下限");
    }

    @Test
    void 空输入安全() {
        assertTrue(RrfFusion.fuse(null, null, K, MEM_W, LAW_W, DEDUPE, 10, 0.0).isEmpty());
        assertTrue(RrfFusion.fuse(List.of(), List.of(), K, MEM_W, LAW_W, DEDUPE, 10, 0.0).isEmpty());
        assertEquals(1, RrfFusion.fuse(mem("只有记忆"), List.of(), K, MEM_W, LAW_W, DEDUPE, 10, 0.0).size());
    }

    /** minScore 设为 0 时不该截断任何东西——两个权重都是正数，融合分恒 > 0。 */
    @Test
    void minScore为零时不截断() {
        List<FusedResult> out = RrfFusion.fuse(
                mem("a"), law("b"), K, MEM_W, LAW_W, DEDUPE, 10, 0.0);
        assertEquals(2, out.size());
    }

    /** k 越大，名次之间的差距越小——这是平滑常数的意义。 */
    @Test
    void k越大名次差距越小() {
        List<FusedResult> small = RrfFusion.fuse(mem("a", "b"), null, 1, 1.0, 0.0, DEDUPE, 10, 0.0);
        List<FusedResult> large = RrfFusion.fuse(mem("a", "b"), null, 1000, 1.0, 0.0, DEDUPE, 10, 0.0);

        double gapSmall = small.get(0).getFusedScore() - small.get(1).getFusedScore();
        double gapLarge = large.get(0).getFusedScore() - large.get(1).getFusedScore();
        assertTrue(gapSmall > gapLarge * 100,
                "k=1 时第1/2名差距应远大于 k=1000 时（" + gapSmall + " vs " + gapLarge + "）");
    }
}
