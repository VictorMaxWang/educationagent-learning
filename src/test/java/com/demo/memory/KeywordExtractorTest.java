package com.demo.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地关键词抽取测试（零依赖、零 token，不碰任何模型）。
 *
 * <p>它是"无词典切分"，所以这里断言的是**性质**（能切出内容、噪声被滤掉、打分有序），
 * 而不是逐字比对某个理想分词结果——后者会把测试变成对停用字表的死記。
 */
class KeywordExtractorTest {

    @Test
    void 从句子中切出内容片段() {
        List<String> kw = KeywordExtractor.extract("我最近压力有点大", 5);
        assertFalse(kw.isEmpty(), "这句话有明确内容，不该抽不出关键词");
        assertTrue(kw.get(0).contains("压力"),
                "最长的内容片段应当排第一，实际拿到：" + kw);
    }

    @Test
    void 多重内容片段都能抽到() {
        List<String> kw = KeywordExtractor.extract("我下周要搬家", 5);
        assertTrue(kw.contains("下周"), "实际：" + kw);
        assertTrue(kw.contains("搬家"), "实际：" + kw);
    }

    @Test
    void 纯功能字抽不出关键词() {
        assertTrue(KeywordExtractor.extract("的了呢吧", 5).isEmpty());
        assertTrue(KeywordExtractor.extract("嗯嗯好的", 5).isEmpty(),
                "全是应答词，说明没有可记的内容");
    }

    @Test
    void 空输入安全() {
        assertTrue(KeywordExtractor.extract(null, 5).isEmpty());
        assertTrue(KeywordExtractor.extract("", 5).isEmpty());
        assertTrue(KeywordExtractor.extract("   ", 5).isEmpty());
        assertTrue(KeywordExtractor.extract("，。！？", 5).isEmpty(),
                "只有标点，归一化后为空");
    }

    @Test
    void 遵守条数上限() {
        String msg = "旅行规划 行程安排 酒店预订 机票查询 景点推荐 美食攻略";
        assertEquals(2, KeywordExtractor.extract(msg, 2).size());
        assertTrue(KeywordExtractor.extract(msg, 2).size() <= 2);
    }

    @Test
    void 上限为零时用默认值() {
        String msg = "旅行规划 行程安排 酒店预订";
        assertEquals(KeywordExtractor.extract(msg, KeywordExtractor.DEFAULT_MAX),
                KeywordExtractor.extract(msg, 0));
    }

    @Test
    void 长片段优先于短片段() {
        // 无词典切分下"更长"≈"更具体"，这是打分的主要依据
        List<String> kw = KeywordExtractor.extract("房屋租赁合同纠纷仲裁", 1);
        assertEquals(1, kw.size());
        assertTrue(kw.get(0).length() >= 4, "应当优先给长片段，实际：" + kw);
    }

    @Test
    void 词频参与打分() {
        // 同一片段出现两次，应当排在只出现一次的同长度片段之前
        List<String> repeated = KeywordExtractor.extract("搬家 搬家 吃饭", 5);
        List<String> once = KeywordExtractor.extract("搬家 吃饭 睡觉", 5);
        assertTrue(repeated.indexOf("搬家") <= once.indexOf("搬家"),
                "重复出现的片段不该被降权。repeated=" + repeated + " once=" + once);
    }

    @Test
    void 逗号拼接形式() {
        // 只有一个关键词时不会有逗号，所以断言"等于 join(list)"而不是"包含逗号"
        assertEquals(String.join(",", KeywordExtractor.extract("我下周要搬家", 5)),
                KeywordExtractor.extractJoined("我下周要搬家", 5));
        assertFalse(KeywordExtractor.extractJoined(null, 5).contains(","));
    }

    @Test
    void 超长片段被截断() {
        // 单个关键词不该长到变成半句话
        String longRun = "一二三四五六七八九十十一十二";
        List<String> kw = KeywordExtractor.extract(longRun, 1);
        assertEquals(1, kw.size());
        assertTrue(kw.get(0).length() <= 8, "关键词长度应当被截断，实际：" + kw.get(0));
    }
}
