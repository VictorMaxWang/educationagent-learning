package com.demo.edu.classroom.research;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EvidenceSelectorTest {
    @Test void repeatedChunksDoNotUseUpTheThreeSnippetBudget() {
        var hits=List.<Map<String,Object>>of(Map.of("file","a.pdf","content","整体大小必须相同。"),Map.of("file","a.pdf","content","整体大小 必须相同！"),Map.of("file","b.pdf","content","面积可以用相同的小格子计数。"),Map.of("file","c.pdf","content","比较分数时需要相同单位。"));
        var selected=EvidenceSelector.select(hits,3);
        assertEquals(3,selected.size());assertEquals(3,selected.stream().map(m->m.get("file")).distinct().count());
        assertEquals("整体大小必须相同。",selected.getFirst().get("content"));
    }
    @Test void sourceCoverageDoesNotReplaceTheHighestRankedRelevantSnippet() {
        var result=EvidenceSelector.select(List.of(Map.of("content","最相关的主题证据","file","D:\\private\\a.pdf"),Map.of("content","相邻但互补的第二条证据","file","a.pdf"),Map.of("content","不同书中的补充内容","file","b.pdf")),2);
        assertEquals("最相关的主题证据",result.getFirst().get("content"));assertEquals("b.pdf",result.get(1).get("file"));
        assertEquals("a.pdf",EvidenceSelector.displayFile("D:\\private\\a.pdf"));
    }
    @Test void emptyOrMalformedEvidenceDoesNotInventSources() {
        var bad=new ArrayList<Map<String,Object>>();bad.add(null);bad.add(Map.of("content",""));bad.add(Map.of("content",123));
        assertTrue(EvidenceSelector.select(bad,3).isEmpty());assertTrue(EvidenceSelector.select(null,3).isEmpty());
    }
    @Test void explicitQuestionTargetsGuideTheAnswerWithoutInferringPersonalTraits() {
        var directive=QuestionIntentSupport.directive("为什么这样，如何核对，和另一种方法有什么区别？");
        assertTrue(directive.contains("一般原理"));assertTrue(directive.contains("可执行步骤"));assertTrue(directive.contains("比较对象"));assertTrue(directive.contains("不编造引用"));assertTrue(directive.contains("不作臆测"));
    }
}
