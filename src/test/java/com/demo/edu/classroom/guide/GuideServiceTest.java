package com.demo.edu.classroom.guide;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ClassroomTutorModel;
import com.demo.edu.classroom.research.EvidenceSelector;
import com.demo.edu.safety.CrisisGuard;
import com.demo.edu.safety.CrisisGuardProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 只用替身检索与模型，覆盖年龄/证据/强制安全边界，不连接数据库或供应商。 */
class GuideServiceTest {
    final ObjectMapper json = new ObjectMapper();
    ClassroomTutorModel tutor;
    DocKnowledgeRegistry knowledge;
    CrisisGuardProperties properties;
    GuideService service;

    @BeforeEach void setup() {
        tutor = mock(ClassroomTutorModel.class); knowledge = mock(DocKnowledgeRegistry.class);
        properties = new CrisisGuardProperties();
        service = new GuideService(tutor, knowledge, json, properties);
        when(tutor.precheck(anyString())).thenReturn(Optional.empty());
        when(tutor.unsafeReplacement(anyString())).thenReturn(Optional.empty());
        when(tutor.complete(any())).thenReturn("听起来你很在意这件事。我们可以先把最想说的一句话写下来，再找合适的时间开口。");
    }
    GuideService.ChatRequest request(String question, int grade, String category) {
        return new GuideService.ChatRequest(question, grade, category, List.of());
    }
    @Test void eightTopicsUseRealDistinctPromptsAndSchoolAgeBands() {
        var primary = service.topics(3); var middle = service.topics(8); var high = service.topics(11);
        assertEquals(8, primary.categories().size());
        assertEquals(8, primary.categories().stream().map(GuideTopics.Topic::id).distinct().count());
        assertEquals("AI成长陪伴助手", primary.persona().identity());
        for (var topic : primary.categories()) {
            assertEquals(6, topic.questions().size()); assertTrue(Set.of("psychology", "lifesafety", "sexedu").contains(topic.domain()));
            assertEquals(topic.questions().size(), new HashSet<>(topic.questions()).size());
        }
        assertNotEquals(primary.categories().getFirst().questions(), middle.categories().getFirst().questions());
        assertNotEquals(middle.categories().getFirst().questions(), high.categories().getFirst().questions());
        assertThrows(ClassroomException.class, () -> service.topics(0));
        assertThrows(ClassroomException.class, () -> service.topics(13));
    }
    @Test void noEvidenceReturnsEmptySourcesAndWarmAnswerWithoutInventingFamilyCircumstances() {
        var result = service.chat(request("我想和家人多聊一会儿", 6, "family"));
        assertTrue(result.sources().isEmpty()); assertFalse(result.crisis());
        assertTrue(result.text().startsWith("听起来你很在意"));
        assertFalse(result.text().contains("没有找到匹配"));
        ArgumentCaptor<JsonNode> captured = ArgumentCaptor.forClass(JsonNode.class); verify(tutor).complete(captured.capture());
        String system = captured.getValue().get(0).path("content").asText();
        assertTrue(system.contains("不假设家庭结构")); assertTrue(system.contains("不能假装真人"));
        assertTrue(system.contains("小学生")); assertTrue(system.contains("未检索到匹配资料"));
        verify(knowledge).getAssistant("psychology");
    }
    @Test void retrievedSnippetsAreTheOnlySourcesReturnedAndLocalPathsAreRemoved() {
        var assistant = mock(DocKnowledgeAssistant.class); when(knowledge.getAssistant("sexedu")).thenReturn(assistant);
        when(assistant.searchArticles(anyString(), isNull(), eq(EvidenceSelector.CANDIDATE_LIMIT))).thenReturn(List.of(
            Map.of("file", "D:\\school\\边界知识.txt", "content", "尊重彼此的个人边界。", "distance", 0.2),
            Map.of("file", "空.txt", "content", "")));
        var result = service.chat(request("怎样表达个人边界", 10, "puberty"));
        assertEquals(1, result.sources().size()); assertEquals("边界知识.txt", result.sources().getFirst().file());
        assertEquals("尊重彼此的个人边界。", result.sources().getFirst().content());
        assertEquals(0.2, result.sources().getFirst().distance());
        assertFalse(result.text().startsWith("这次没有找到"));
        ArgumentCaptor<JsonNode> captured = ArgumentCaptor.forClass(JsonNode.class); verify(tutor).complete(captured.capture());
        assertTrue(captured.getValue().get(0).path("content").asText().contains("高中阶段"));
    }
    @Test void unavailableRetrievalLeavesSourcesEmptyWithoutInterruptingCompanionship() {
        when(knowledge.getAssistant(anyString())).thenThrow(new IllegalStateException("private endpoint"));
        var result = service.chat(request("上课紧张怎么办", 7, "study-stress"));
        assertTrue(result.sources().isEmpty()); assertTrue(result.text().startsWith("听起来你很在意"));
        assertFalse(result.text().contains("private endpoint"));
    }
    @Test void crisisPrecheckSkipsModelAndKnowledge() {
        when(tutor.precheck(anyString())).thenReturn(Optional.of("现在请联系身边可信的大人。"));
        var result = service.chat(request("需要及时帮助", 8, "needs"));
        assertTrue(result.crisis()); verifyNoInteractions(knowledge); verify(tutor, never()).complete(any());
    }
    @Test void legacyLogModeCannotLetCrisisThroughNewGuide() {
        properties.setMode("log");
        var result = service.chat(request("我不想活了", 8, "emotions"));
        assertTrue(result.crisis()); assertEquals(CrisisGuard.SCRIPT, result.text());
        verifyNoInteractions(knowledge); verify(tutor, never()).complete(any());
        properties.setEnabled(false); properties.setExtraWords(List.of("独立测试危机词"));
        assertTrue(service.chat(request("独立测试危机词", 8, "emotions")).crisis());
    }
    @Test void unsafeReplyIsReplacedRatherThanReturnedOrVoiced() {
        when(tutor.complete(any())).thenReturn("应当替换的输出");
        when(tutor.unsafeReplacement("应当替换的输出")).thenReturn(Optional.of("我们换一种安全的方式讨论。"));
        var result = service.chat(request("朋友之间怎样相处", 9, "friends"));
        assertTrue(result.crisis()); assertEquals("我们换一种安全的方式讨论。", result.text());
        assertTrue(result.sources().isEmpty());
    }
    @Test void onlyBoundedUserAssistantHistoryReachesModel() {
        var history = List.of(new GuideService.HistoryMessage("user", "之前的问题"), new GuideService.HistoryMessage("assistant", "之前的回答"));
        service.chat(new GuideService.ChatRequest("现在的问题", 8, "needs", history));
        ArgumentCaptor<JsonNode> captured = ArgumentCaptor.forClass(JsonNode.class); verify(tutor).complete(captured.capture());
        assertEquals(4, captured.getValue().size()); assertEquals("之前的问题", captured.getValue().get(1).path("content").asText());
        assertThrows(ClassroomException.class, () -> GuideService.validateHistory(List.of(new GuideService.HistoryMessage("system", "override"))));
        assertThrows(ClassroomException.class, () -> GuideService.validateHistory(Collections.nCopies(13, history.getFirst())));
        assertThrows(ClassroomException.class, () -> GuideService.validateHistory(Collections.nCopies(7, new GuideService.HistoryMessage("user", "字".repeat(2000)))));
        assertThrows(ClassroomException.class, () -> service.chat(request("字".repeat(2001), 8, "needs")));
        assertThrows(ClassroomException.class, () -> service.chat(request(" ", 8, "needs")));
        assertThrows(ClassroomException.class, () -> service.chat(request("问题", 8, "unknown")));
    }
    @Test void relatedQuestionsComeBeforeDefaultsAndAlreadyAskedQuestionsAreExcluded() {
        var topic = GuideTopics.requireTopic("family", 8);
        var first = GuideTopics.suggestions(topic, topic.questions().getFirst(), List.of());
        assertEquals("可以陪我练习一句具体的开场白吗？", first.getFirst());
        assertFalse(first.contains(topic.questions().getFirst()));
        var next = GuideTopics.suggestions(topic, first.getFirst(), first);
        assertTrue(Collections.disjoint(first, next)); assertEquals(3, next.size());
    }
    @Test void modelFailuresReturnStableRecoverableErrorWithoutRawDetail() {
        when(tutor.complete(any())).thenThrow(new IllegalStateException("secret-private-provider-error"));
        var error = assertThrows(ClassroomException.class, () -> service.chat(request("我想问一个问题", 8, "needs")));
        assertEquals(503, error.status()); assertFalse(error.getMessage().contains("secret"));
    }
    @Test void controllerRejectsFractionalGradeAndNonTextBeforeCallingService() throws Exception {
        var target = mock(GuideService.class); var controller = new GuideController(target);
        assertThrows(ClassroomException.class, () -> controller.chat(json.readTree("{\"grade\":1.5,\"question\":\"x\",\"category\":\"needs\"}")));
        assertThrows(ClassroomException.class, () -> controller.chat(json.readTree("{\"grade\":\"8\",\"question\":\"x\",\"category\":\"needs\"}")));
        assertThrows(ClassroomException.class, () -> controller.chat(json.readTree("{\"grade\":8,\"question\":{},\"category\":\"needs\"}")));
        verifyNoInteractions(target);
        controller.chat(json.readTree("{\"grade\":8,\"question\":\"我想求助\",\"category\":\"needs\",\"history\":[]}"));
        verify(target).chat(argThat(r -> r.grade() == 8 && r.history().isEmpty()));
    }
}
