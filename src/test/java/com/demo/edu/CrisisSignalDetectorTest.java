package com.demo.edu;

import com.demo.edu.safety.CrisisSignalDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危机词表测试。
 *
 * <p>词表是"未成年人自伤风险"的唯一自动防线，所以它必须能脱离 Spring 立刻跑——
 * 改一个词就能验证一次，而不是等启动整个应用。
 */
class CrisisSignalDetectorTest {

    /** 正例：真实求助里常见的说法，都要能命中。 */
    @Test
    @DisplayName("正例：明确的危机表达都能命中")
    void detectsExplicitSignals() {
        List<String> positives = List.of(
                "我不想活了",
                "我想死",
                "活着没意思",
                "感觉活不下去了",
                "我想自杀",
                "我想自残",
                "我割腕了",
                "我写了遗书",
                "我不想再醒过来",
                "真想一死了之",
                "我觉得世界没有我会更好",
                "没人会在乎我",
                "我想消失",
                "活着好累",
                "想结束生命",
                "我伤害自己"
        );
        for (String text : positives) {
            assertTrue(CrisisSignalDetector.hit(text, List.of()),
                    "应命中但未命中：" + text);
        }
    }

    /** 反例：日常口语与无关问题不能被误判成危机（误伤会让人直接关掉这个功能）。 */
    @Test
    @DisplayName("反例：口语夸张与无关内容不命中")
    void ignoresEverydayPhrases() {
        List<String> negatives = List.of(
                "今天天气怎么样",
                "这作业多到累死我了",
                "笑死我了",
                "这道题难死了",
                "《活着》这本书讲了什么",
                "死亡教育是什么意思",
                "我想了解脑死亡的判定标准",
                "今天体育课快跑死我了"
        );
        for (String text : negatives) {
            assertFalse(CrisisSignalDetector.hit(text, List.of()),
                    "不应命中但命中了：" + text);
        }
    }

    @Test
    @DisplayName("自定义词可运行时追加，且与内置词去重")
    void supportsExtraWords() {
        assertFalse(CrisisSignalDetector.hit("撑不下去了", List.of()));
        assertTrue(CrisisSignalDetector.hit("撑不下去了", List.of("撑不下去")));
        // 与内置词重复时不产生重复项
        List<String> hits = CrisisSignalDetector.hits("我想死", List.of("想死"));
        assertTrue(hits.contains("想死"));
        assertTrue(hits.size() <= 2, "去重后不该出现重复项：" + hits);
    }

    @Test
    @DisplayName("空值与空白安全")
    void handlesBlank() {
        assertFalse(CrisisSignalDetector.hit(null, null));
        assertFalse(CrisisSignalDetector.hit("   ", null));
        assertTrue(CrisisSignalDetector.signalCount() > 30, "词表规模异常，可能被误删");
    }
}
