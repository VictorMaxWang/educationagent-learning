package com.demo.chroma;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 法律检索过滤策略测试。
 *
 * <p>核心用例复刻方案书里记录的真实召回：
 * query = 「正当防卫怎么判」，三条距离 0.4397 / 0.5096 / 0.5120，
 * 第 1 条是正当防卫相关，第 2、3 条讲的是"监护人指定""堆放物倒塌"。
 * 旧阈值 1.2 对这三条毫无约束力（全过），必须靠相对 cutoff 切掉后两条。
 */
class LawRetrievalPolicyTest {

    /** 构造一条召回结果。 */
    private static Map<String, Object> hit(double distance, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("distance", distance);
        m.put("content", content);
        m.put("metadata", Map.of("source", "civil_code.txt"));
        return m;
    }

    /** 足够长的假条文，避免被"过短"规则误杀。 */
    private static String longText(String tag) {
        return tag + "：本条是关于相关权利义务的规定，当事人应当依法行使权利、履行义务，不得损害他人合法权益。";
    }

    @Test
    void 相对cutoff切掉无关的后两条() {
        List<Map<String, Object>> hits = List.of(
                hit(0.4397, longText("正当防卫条文")),
                hit(0.5096, longText("监护人指定条文")),
                hit(0.5120, longText("堆放物倒塌条文")));

        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 1.08, 10, true);

        assertEquals(1, d.kept().size(), "只应保留最近的那一条，实际=" + d.kept().size());
        assertEquals(0.4397, LawRetrievalPolicy.distance(d.kept().get(0)), 1e-9);
        assertTrue(d.log().stream().anyMatch(l -> l.contains("超相对 cutoff")));
    }

    /** 距离接近时应当都保留——相对 cutoff 不能把真正相关的并列条款切掉。 */
    @Test
    void 距离接近的相关条款都保留() {
        List<Map<String, Object>> hits = List.of(
                hit(0.4000, longText("条文A")),
                hit(0.4100, longText("条文B")));

        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 1.08, 10, true);
        assertEquals(2, d.kept().size(), "1.08 倍以内应当视为同等相关");
    }

    @Test
    void 超绝对阈值的一律丢弃() {
        List<Map<String, Object>> hits = List.of(
                hit(0.5000, longText("相关条文")),
                hit(1.0500, longText("不相关条文")));

        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 1.08, 10, true);
        assertEquals(1, d.kept().size());
        assertTrue(d.log().stream().anyMatch(l -> l.contains("超绝对阈值")));
    }

    @Test
    void 空条文与过短条文被丢弃() {
        List<Map<String, Object>> hits = new ArrayList<>();
        hits.add(hit(0.30, "   "));
        hits.add(hit(0.31, "1. 。"));
        hits.add(hit(0.32, longText("正常条文")));

        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 1.08, 10, true);
        assertEquals(1, d.kept().size());
        assertTrue(d.log().stream().anyMatch(l -> l.contains("内容为空")));
        assertTrue(d.log().stream().anyMatch(l -> l.contains("内容过短")));
    }

    /**
     * 条数下限保护：即使调用方把 cutoff 系数配成小于 1（理论上会把 top1 也切掉），
     * 也必须至少返回一条，而不是让用户看到"暂无法解答"。
     */
    @Test
    void 条数下限保护至少保留一条() {
        List<Map<String, Object>> hits = List.of(
                hit(0.4000, longText("条文A")),
                hit(0.9000, longText("条文B")));

        // ratio=0.5 → cutoff = 0.2，两条都超；但 ratio 会被夹到 1.0，所以 top1(0.4) 仍存活
        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 0.5, 10, true);
        assertEquals(1, d.kept().size(), "ratio<1 应当被夹到 1，保证 top1 存活");
        assertEquals(0.4000, LawRetrievalPolicy.distance(d.kept().get(0)), 1e-9);
    }

    @Test
    void 距离缺失视为很远从而被过滤() {
        Map<String, Object> noDistance = new LinkedHashMap<>();
        noDistance.put("content", longText("没有距离字段"));
        List<Map<String, Object>> hits = List.of(hit(0.40, longText("正常条文")), noDistance);

        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 1.08, 10, true);
        assertEquals(1, d.kept().size());
    }

    @Test
    void 空召回安全返回() {
        assertEquals("no-hits", LawRetrievalPolicy.filter(List.of(), 0.85, 1.08, 10, true).state());
        assertEquals("no-hits", LawRetrievalPolicy.filter(null, 0.85, 1.08, 10, true).state());
    }

    @Test
    void 全部被阈值过滤时状态可识别() {
        List<Map<String, Object>> hits = List.of(hit(1.50, longText("太远")));
        LawRetrievalPolicy.Decision d = LawRetrievalPolicy.filter(hits, 0.85, 1.08, 10, true);
        assertEquals("all-filtered-by-threshold", d.state());
        assertTrue(d.kept().isEmpty());
    }
}
