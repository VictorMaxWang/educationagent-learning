package com.demo.testsupport;

import com.demo.DemoApplication;
import com.demo.bot.BotManager;
import com.demo.chroma.LawKnowledgeIndexer;
import com.demo.chroma.base.EmbeddingService;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.chroma.eval.RagEvalJudge;
import com.demo.chroma.eval.RagEvalScheduleService;
import com.demo.chroma.eval.RagEvalService;
import com.demo.edu.classroom.ClassroomNodeEngine;
import com.demo.edu.classroom.ClassroomWechatBridge;
import com.demo.edu.classroom.ai.ClassroomAiGateway;
import com.demo.edu.classroom.recommendation.ClassroomRecommendationService;
import com.demo.fileconvert.ConvertCleanupScheduler;
import com.demo.mcp.McpToolBridge;
import com.demo.mcp.wecom.WecomRobotService;
import com.demo.pet.PetScheduleService;
import com.demo.service.tools.ReminderService;
import com.demo.service.tools.ReportScheduleService;
import com.demo.service.tools.SendEmailService;
import com.demo.service.xhs.CommentWatchService;
import com.demo.service.xhs.HotVideoReportService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 使用真实隔离 MySQL 和本地索引的上下文测试；外部 I/O、启动索引与主动推送在 Bean 边界替换。
 * <p>不能只新增 Primary 替身：原 Bean 的构造器和 PostConstruct 仍可能连接外网或读写用户文件。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(classes = DemoApplication.class, properties = {
        "spring.config.location=classpath:/application-test-isolated.properties"
})
@ActiveProfiles("test-isolated")
@ContextConfiguration(initializers = IsolatedTestEnvironment.class)
@Import(IsolatedTestConfiguration.class)
@MockitoBean(types = {
        EmbeddingService.class, LawKnowledgeIndexer.class, DocKnowledgeRegistry.class,
        ReportScheduleService.class, ReminderService.class, HotVideoReportService.class,
        RagEvalScheduleService.class, RagEvalService.class, RagEvalJudge.class, PetScheduleService.class,
        CommentWatchService.class, SendEmailService.class, McpToolBridge.class,
        BotManager.class, WecomRobotService.class, ClassroomWechatBridge.class,
        ConvertCleanupScheduler.class, ClassroomRecommendationService.class,
        ClassroomNodeEngine.class, ClassroomAiGateway.class
})
@MockitoBean(name = "deepseekChatModel", types = OpenAiChatModel.class)
@MockitoBean(name = "restTemplate", types = RestTemplate.class)
@MockitoBean(name = "visionRestTemplate", types = RestTemplate.class)
@MockitoBean(name = "llmRestTemplate", types = RestTemplate.class)
@MockitoBean(name = "mailSender", types = JavaMailSender.class)
@MockitoBean(name = "run", types = CommandLineRunner.class)
public @interface IsolatedSpringBootTest {
}
