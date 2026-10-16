package com.demo.edu.classroom.study;

import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassroomStudyContextTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode course() throws Exception {
        return (ObjectNode)json.readTree("""
            {"id":"course-1","topic":"比较分数","title":"分数与菜园","grade":5,"subject":"数学","direction":"academic",
             "sourceName":"菜园观察.txt","sourceText":"3/7 < 5/7。\\n长6米、宽4米，面积24平方米。",
             "revision":1,"progress":{"sceneIndex":0,"actionIndex":0},
             "outlines":[{"id":"scene-1","description":"用面积解释分数"}],
             "scenes":[{"id":"scene-1","title":"观察菜园","type":"interactive","content":{"html":"<html><style>.x{}</style><body><h1>菜园面积</h1><p>改变长和宽</p><script>secretScript=123</script></body></html>"},"actions":[{"type":"speech","text":"面积等于长乘宽。"}]}]}
            """);
    }

    @Test void actualSourceSymbolsAndNamedAnchorsSurviveExtraction() throws Exception {
        var context=ClassroomStudyContext.from(course(),"scene-1");
        assertTrue(context.anchors().stream().anyMatch(a->a.kind().equals("reference")&&a.text().equals("3/7 < 5/7。")));
        assertTrue(context.anchors().stream().anyMatch(a->a.label().equals("菜园观察.txt")));
        assertTrue(context.visibleText().contains("24平方米"));
        assertFalse(context.visibleText().contains("secretScript"));
        assertFalse(context.visibleText().contains(".x{}"));
    }

    @Test void progressChatAndRuntimeChangesDoNotInvalidateSources() throws Exception {
        ObjectNode value=course();String before=ClassroomStudyContext.from(value,"scene-1").fingerprint();
        value.put("revision",19);value.withObject("/progress").put("actionIndex",4);
        value.putArray("messages").addObject().put("text","我知道了");
        value.putObject("runtime").putObject("scene-1").put("isPlaying",true);
        assertEquals(before,ClassroomStudyContext.from(value,"scene-1").fingerprint());
    }

    @Test void teachingSourceGradeOrContentChangeInvalidatesFingerprint() throws Exception {
        ObjectNode original=course();String before=ClassroomStudyContext.from(original,"scene-1").fingerprint();
        ObjectNode changed=original.deepCopy();changed.put("sourceText","长度是5米。");
        assertNotEquals(before,ClassroomStudyContext.from(changed,"scene-1").fingerprint());
        changed=original.deepCopy();changed.put("grade",6);
        assertNotEquals(before,ClassroomStudyContext.from(changed,"scene-1").fingerprint());
        changed=original.deepCopy();((ObjectNode)changed.path("scenes").get(0).path("content")).put("html","<p>新知识</p>");
        assertNotEquals(before,ClassroomStudyContext.from(changed,"scene-1").fingerprint());
    }

    @Test void canonicalKeyOrderIsStableButArrayOrderRemainsMeaningful() throws Exception {
        assertEquals(ClassroomStudyContext.hash(json.readTree("{\"a\":1,\"b\":2}")),ClassroomStudyContext.hash(json.readTree("{\"b\":2,\"a\":1}")));
        assertNotEquals(ClassroomStudyContext.hash(json.readTree("[1,2]")),ClassroomStudyContext.hash(json.readTree("[2,1]")));
    }

    @Test void requestCannotBorrowAnUnrelatedScene() throws Exception {
        ObjectNode value=course();
        assertEquals(404,assertThrows(ClassroomException.class,()->ClassroomStudyContext.from(value,"other-scene")).status());
        assertEquals("scene-1",ClassroomStudyContext.from(value,null).sceneId());
        value.putArray("scenes");
        assertEquals(404,assertThrows(ClassroomException.class,()->ClassroomStudyContext.from(value,null)).status());
    }

    @Test void chunksDoNotSplitSurrogatePairsOrDropText() throws Exception {
        ObjectNode value=course();String text="田".repeat(599)+"🌾"+"地".repeat(601);value.put("sourceText",text);
        var anchors=ClassroomStudyContext.from(value,"scene-1").anchors().stream().filter(a->a.kind().equals("reference")).toList();
        assertEquals(text,String.join("",anchors.stream().map(ClassroomStudyContext.Anchor::text).toList()));
        for(var anchor:anchors)assertFalse(Character.isHighSurrogate(anchor.text().charAt(anchor.text().length()-1)));
    }
    @Test void fullTwentyThousandCharacterContractIncludesFinalQualifyingSentence() throws Exception {
        ObjectNode value=course();String tail="最后限定：仅在长度单位一致时计算面积。";
        String source="田".repeat(20000-tail.length())+tail;value.put("sourceText",source);
        var context=ClassroomStudyContext.from(value,"scene-1");
        assertTrue(context.sourceComplete());assertTrue(context.visibleText().contains(tail));
        assertEquals(source,String.join("",context.anchors().stream().filter(a->a.kind().equals("reference")).map(ClassroomStudyContext.Anchor::text).toList()));
    }
    @Test void oversizedLegacySourceIsExplicitlyIncompleteAndNeverCutsEmojiInHalf() throws Exception {
        ObjectNode value=course();value.put("sourceText","田".repeat(19999)+"🌾末尾");
        var context=ClassroomStudyContext.from(value,"scene-1");assertFalse(context.sourceComplete());assertFalse(context.incompleteSources().isEmpty());
        String text=String.join("",context.anchors().stream().filter(a->a.kind().equals("reference")).map(ClassroomStudyContext.Anchor::text).toList());
        assertEquals(19999,text.length());assertFalse(Character.isHighSurrogate(text.charAt(text.length()-1)));
    }
}
