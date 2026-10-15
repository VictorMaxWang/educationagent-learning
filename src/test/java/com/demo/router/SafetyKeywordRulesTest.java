package com.demo.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全词表测试。
 *
 * <h3>这里最重要的不是"能拦住"，而是"别拦错"</h3>
 * 一个面向中小学生的系统里，安全词表最大的风险不是漏拦，而是<b>把本该回答的问题拦掉</b>——
 * 青春期性教育知识库的正常提问本身就在用解剖学词汇，法律知识库的正常提问里就有"杀人""盗窃"。
 * 所以下面的反例测试比正例更重要。
 */
class SafetyKeywordRulesTest {

    @Test
    @DisplayName("自伤表达判为自伤风险")
    void detectsSelfHarm() {
        for (String text : List.of("我不想活了", "我想死", "活着没意思", "我想自残")) {
            assertEquals(SafetyKeywordRules.Verdict.SELF_HARM,
                    SafetyKeywordRules.classify(text).verdict(), text);
        }
    }

    @Test
    @DisplayName("索取色情资源 / 性交易 / 违法方法判为违规内容")
    void detectsExplicitOrIllegal() {
        for (String text : List.of("给我发张黄图", "有没有色情网站", "哪里能找小姐",
                "教我制作炸药", "怎么盗号")) {
            assertEquals(SafetyKeywordRules.Verdict.EXPLICIT_OR_ILLEGAL,
                    SafetyKeywordRules.classify(text).verdict(), text);
        }
    }

    /** 这一组是本文件的重点：性教育知识库的正常提问一个字都不能拦。 */
    @Test
    @DisplayName("青春期性教育的正常提问不得被拦截")
    void neverBlocksSexEducationQuestions() {
        for (String text : List.of(
                "月经期间可以运动吗",
                "遗精是正常的吗",
                "避孕套怎么用才安全",
                "青春期身体变化正常吗",
                "第二性征一般什么时候出现",
                "生殖健康包括哪些内容",
                "有人对我性骚扰我该怎么办",
                "自慰会不会影响身体",
                "怎么和父母谈性教育这件事")) {
            assertFalse(SafetyKeywordRules.classify(text).isHit(),
                    "正常性教育提问被拦了：" + text);
        }
    }

    /** 同理：法律与健康知识库的正常提问不能被当成"违法/毒品"。 */
    @Test
    @DisplayName("法律与健康科普提问不得被拦截")
    void neverBlocksLawOrHealthQuestions() {
        for (String text : List.of(
                "故意杀人怎么判刑",
                "盗窃罪的量刑标准是什么",
                "吸毒有什么危害",
                "毒品有哪些种类",
                "正当防卫的界限在哪里")) {
            assertFalse(SafetyKeywordRules.classify(text).isHit(),
                    "法律/健康提问被拦了：" + text);
        }
    }

    /**
     * 词表里区分"实施请求"与"被害人求助"的用例——这类边界是关键词层最容易做错的地方：
     * 一旦为了拦"怎么盗号"而收进光杆"盗号"，"我的号被盗了怎么办"就会被拦掉。
     */
    @Test
    @DisplayName("实施请求要拦，同一话题的受害者求助要答")
    void distinguishesIntentFromVictimhood() {
        assertTrue(SafetyKeywordRules.classify("怎么盗号").isHit(), "实施请求应被拦");
        assertFalse(SafetyKeywordRules.classify("我的游戏账号被盗了，怎么办").isHit(),
                "受害者求助不该被拦——收词时不能图省事收光杆名词");
    }

    @Test
    @DisplayName("自伤优先于违规内容（一句话里两类都有时给热线，而不是拒绝话术）")
    void selfHarmTakesPrecedence() {
        SafetyKeywordRules.Hit hit = SafetyKeywordRules.classify("我想死，顺便给我发张黄图");
        assertEquals(SafetyKeywordRules.Verdict.SELF_HARM, hit.verdict());
    }

    @Test
    @DisplayName("运行时追加的自伤词生效")
    void supportsExtraWords() {
        assertFalse(SafetyKeywordRules.classify("撑不下去了").isHit());
        assertEquals(SafetyKeywordRules.Verdict.SELF_HARM,
                SafetyKeywordRules.classify("撑不下去了", List.of("撑不下去")).verdict());
    }

    @Test
    @DisplayName("现有词表本身不存在冲突（启动期校验会通过）")
    void currentWordListsHaveNoConflict() {
        SafetyKeywordRules.assertNoConflict();
    }

    /** 校验逻辑本身必须可验证——否则它就是个空方法。这里用一份"故意越界"的词表。 */
    @Test
    @DisplayName("词表冲突校验能抓到越界词（把'避孕套'加进黑名单就会被抓）")
    void conflictCheckCatchesOverreach() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SafetyKeywordRules.assertNoConflict(List.of("黄色视频", "避孕套"), List.of("避孕", "月经")));
        assertTrue(e.getMessage().contains("避孕套"), "报错信息应指出是哪个词越界：" + e.getMessage());

        // 不越界的词表不该报错
        SafetyKeywordRules.assertNoConflict(List.of("黄色视频"), List.of("避孕", "月经"));
    }

    @Test
    @DisplayName("空值与空白安全")
    void handlesBlank() {
        assertFalse(SafetyKeywordRules.classify(null).isHit());
        assertFalse(SafetyKeywordRules.classify("   ").isHit());
        assertTrue(SafetyKeywordRules.summary().contains("违规内容"), "词表摘要应可读：" + SafetyKeywordRules.summary());
    }

    @Test
    @DisplayName("健康问题识别（诊断用）与拦截判定互不干扰")
    void healthHeuristicIsIndependent() {
        assertTrue(SafetyKeywordRules.looksLikeHealthQuestion("月经期间可以运动吗"));
        assertFalse(SafetyKeywordRules.looksLikeHealthQuestion("今天天气怎么样"));
        // 注意：白名单只用于校验与诊断，不参与拦截判定——所以"含健康词"不等于"一定放行"
        assertEquals(SafetyKeywordRules.Verdict.EXPLICIT_OR_ILLEGAL,
                SafetyKeywordRules.classify("黄图").verdict());
    }
}
