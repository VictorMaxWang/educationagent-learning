package com.demo.edu.classroom.recommendation;

import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.study.ClassroomStudyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoTeachingClassifierTest {
    final ObjectMapper json=new ObjectMapper();
    ClassroomStudyContext.Snapshot context(String title,String direction,List<String> purposes) {
        return new ClassroomStudyContext.Snapshot("course","scene","fp",title,title,10,"语文",direction,1,
                List.of(new ClassroomStudyContext.Anchor("goal-1","学习目标","体会沁园春长沙的朗诵节奏和情感。","objective")),"",purposes);
    }
    ObjectNode eligible(){ObjectNode v=json.createObjectNode().put("status","eligible").put("purpose","recitation").put("reason","听诵读示范，体会节奏")
            .put("publicTopic","沁园春长沙");v.putArray("goals").addObject().put("text","朗诵节奏").put("anchorId","goal-1").put("quote","朗诵节奏和情感");
        v.putArray("basis").addObject().put("anchorId","goal-1").put("quote","体会沁园春长沙的朗诵节奏和情感");return v;}
    @Test void realCourseAnchorAndPublicTopicAreRequired(){var c=context("请讲沁园春长沙","academic",List.of());assertDoesNotThrow(()->VideoTeachingClassifier.validate(c,eligible()));
        var invented=eligible();((ObjectNode)invented.path("basis").get(0)).put("quote","看过视频可提高成绩");assertThrows(IllegalArgumentException.class,()->VideoTeachingClassifier.validate(c,invented));}
    @Test void externalQueryCannotUseSourceTextOrGrade(){var c=context("沁园春长沙 高一","academic",List.of());var fromSource=eligible().put("publicTopic","朗诵节奏和情感");
        assertThrows(IllegalArgumentException.class,()->VideoTeachingClassifier.validate(c,fromSource));var grade=eligible().put("publicTopic","沁园春长沙 高一");
        assertThrows(IllegalArgumentException.class,()->VideoTeachingClassifier.validate(c,grade));}
    @Test void privateSpecificMistakesAndDebugNeverCallModel(){var ai=mock(ClassroomAiGateway.class);var classifier=new VideoTeachingClassifier(ai,json);
        for(String title:List.of("我最近很焦虑","帮我批改这道错题","帮我调试这段Python代码并追踪变量"))assertEquals("ineligible",classifier.classify(context(title,"academic",List.of())).status());
        assertEquals("ineligible",classifier.classify(context("身体与青春期","wellbeing",List.of())).status());verifyNoInteractions(ai);}
    @Test void quizSceneAndNoTopicRemainHidden(){assertNotNull(VideoTeachingClassifier.excludedReason(context("分数练习","academic",List.of("practice"))));
        assertNotNull(VideoTeachingClassifier.excludedReason(context("","academic",List.of())));}
    @Test void programmingConceptLessonIsNotTreatedAsDebugTaskByKeywordsAlone(){
        for(String title:List.of("Python循环流程图","Java递归概念","通过示例代码理解函数与列表","Java调用栈概念讲解",
                "Python循环的代码可视化入门","认识断点与调试原理","Python代码调试与变量追踪","认识错误信息：TypeError的含义"))
            assertNull(VideoTeachingClassifier.excludedReason(context(title,"academic",List.of("interactive_exploration"))),title);
        for(String title:List.of("Python循环这段代码报错如何修复","帮我debug这段Java递归代码","对当前程序做变量追踪","报错：NameError name i is not defined"))
            assertNotNull(VideoTeachingClassifier.excludedReason(context(title,"academic",List.of("interactive_exploration"))),title);}
    @Test void conceptWordsAndConcreteCurrentOperationsAreHandledAsPairs(){
        for(String[] pair:List.of(new String[]{"Python循环的代码可视化入门","把当前代码可视化并逐行执行"},
                new String[]{"认识断点与调试原理","给当前程序设置断点并调试"},
                new String[]{"认识变量追踪与调用栈的概念","对当前程序做变量追踪"},
                new String[]{"Java递归debug方法介绍","帮我调试这段Java递归代码"})){
            assertNull(VideoTeachingClassifier.excludedReason(context(pair[0],"academic",List.of("interactive_exploration"))),pair[0]);
            assertNotNull(VideoTeachingClassifier.excludedReason(context(pair[1],"academic",List.of("interactive_exploration"))),pair[1]);
        }}
    @Test void groundedProgrammingConceptCanPassEligibleContract(){
        var c=new ClassroomStudyContext.Snapshot("course","scene","fp","Python循环概念","Python循环概念",8,"信息技术","academic",1,
                List.of(new ClassroomStudyContext.Anchor("goal-1","学习目标","通过流程图理解Python循环的条件检查与重复执行。","objective")),"",List.of("interactive_exploration"));
        var value=eligible().put("purpose","concept").put("publicTopic","Python循环").put("reason","用流程图演示循环条件与重复执行");
        ((ObjectNode)value.path("goals").get(0)).put("text","循环条件与重复执行").put("quote","条件检查与重复执行");
        ((ObjectNode)value.path("basis").get(0)).put("quote","通过流程图理解Python循环的条件检查与重复执行");
        assertDoesNotThrow(()->VideoTeachingClassifier.validate(c,value));
        ((ObjectNode)value.path("basis").get(0)).put("quote","修复用户程序的具体报错");assertThrows(IllegalArgumentException.class,()->VideoTeachingClassifier.validate(c,value));}
    ClassroomStudyContext.Snapshot genericContext(String title,String topic){return new ClassroomStudyContext.Snapshot("course","scene","fp",title,topic,8,"通识","academic",1,
            List.of(new ClassroomStudyContext.Anchor("goal-1","目标","通过示范理解核心原理与步骤。","objective")),"",List.of());}
    ObjectNode genericOutput(String topic){var value=eligible().put("purpose","concept").put("publicTopic",topic).put("reason","用示范补充核心原理");
        ((ObjectNode)value.path("goals").get(0)).put("text","核心原理与步骤").put("quote","理解核心原理与步骤");
        ((ObjectNode)value.path("basis").get(0)).put("quote","通过示范理解核心原理与步骤");return value;}
    @Test void publicThemeAllowsOnlyPresentationDifferencesAndKeepsMeaningfulSymbols(){
        assertDoesNotThrow(()->VideoTeachingClassifier.validate(genericContext("《水循环·原理》","为我讲一讲水循环，原理"),genericOutput("水循环原理")));
        assertDoesNotThrow(()->VideoTeachingClassifier.validate(genericContext("PYTHON 循环入门","PYTHON 循环"),genericOutput("python循环")));
        assertDoesNotThrow(()->VideoTeachingClassifier.validate(genericContext("英语 th 发音：看口型，听声音","英语 TH 发音"),genericOutput("英语th")));
        assertDoesNotThrow(()->VideoTeachingClassifier.validate(genericContext("English th pronunciation","English th pronunciation"),genericOutput("English th")));
        for(String[] pair:List.of(new String[]{"C++函数入门","C#函数"},new String[]{"小数3.14的意义","小数314"},new String[]{"Python循环","th"},new String[]{"JavaScript函数","Java"})){
            var failure=assertThrows(ClassroomAiGateway.ContractViolation.class,()->VideoTeachingClassifier.validate(genericContext(pair[0],pair[0]),genericOutput(pair[1])));
            assertEquals(ClassroomAiGateway.ContractCode.PUBLIC_TOPIC_NOT_IN_THEME,failure.code());
        }}
    @Test void sourceOnlyWordsAndBannerSlogansHaveSafeRepairCodes(){
        var c=genericContext("水循环原理","水循环原理");var sourceOnly=assertThrows(ClassroomAiGateway.ContractViolation.class,()->VideoTeachingClassifier.validate(c,genericOutput("核心原理")));
        assertEquals(ClassroomAiGateway.ContractCode.PUBLIC_TOPIC_NOT_IN_THEME,sourceOnly.code());
        var banner=genericContext("英语 th 发音：看口型，听声音","英语 TH 发音");
        var broad=assertThrows(ClassroomAiGateway.ContractViolation.class,()->VideoTeachingClassifier.validate(banner,genericOutput(banner.title())));
        assertEquals(ClassroomAiGateway.ContractCode.PUBLIC_TOPIC_NOT_CORE,broad.code());
        var privateGrade=assertThrows(ClassroomAiGateway.ContractViolation.class,()->VideoTeachingClassifier.validate(genericContext("高 一数学","高 一数学"),genericOutput("高 一数学")));
        assertEquals(ClassroomAiGateway.ContractCode.PUBLIC_TOPIC_FORMAT,privateGrade.code());
        var inventedAnchor=genericOutput("水循环");((ObjectNode)inventedAnchor.path("basis").get(0)).put("quote","私密内容不在原文");
        assertEquals(ClassroomAiGateway.ContractCode.COURSE_ANCHOR_INVALID,assertThrows(ClassroomAiGateway.ContractViolation.class,()->VideoTeachingClassifier.validate(c,inventedAnchor)).code());}
    @Test void searchTopicUsesCoreAndDoesNotRepeatTeachingPurpose(){assertEquals("英语th",VideoTeachingClassifier.searchTopic("英语 TH 发音","pronunciation"));
        assertEquals("春江月夜",VideoTeachingClassifier.searchTopic("《春江·月夜》朗诵","recitation"));assertEquals("c++函数",VideoTeachingClassifier.searchTopic("C++ 函数概念","concept"));}
}
