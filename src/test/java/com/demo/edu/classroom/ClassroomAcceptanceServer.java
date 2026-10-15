package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeAssistant;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.config.RestTemplateConfig;
import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.guide.GuideController;
import com.demo.edu.classroom.guide.GuideService;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.edu.mapper.EduCrisisDailyMapper;
import com.demo.edu.mapper.EduUserClassMapper;
import com.demo.edu.safety.*;
import com.demo.mapper.*;
import com.demo.service.TtsService;
import com.demo.service.preprocessing.AudioService;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 手动验收入口，仅存在于 test classpath；没有组件扫描，也不是 @SpringBootApplication。
 * 必须显式指定独立验收数据库，真实模型与腾讯语音凭据仍读取现有 application*.properties。
 * 不自动运行、不启动微信机器人，不注册任何原项目定时任务或知识索引器。
 * 成长问答和图片接口使用真实业务服务；知识检索明确为空，图片缓存仍限制在独立验收目录。
 */
public final class ClassroomAcceptanceServer {
    private ClassroomAcceptanceServer() {}
    private static final Set<String> TABLES = Set.of("quiz_paper","quiz_question","quiz_passage","quiz_answer",
            "quiz_knowledge_fav","quiz_question_fav","edu_user_class","edu_crisis_daily","edu_learning_course");
    private static final String H2_OPTIONS = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    public static void main(String[] args) {
        String url=Arrays.stream(args).filter(s -> s.startsWith("--spring.datasource.url="))
                .map(s -> s.substring("--spring.datasource.url=".length())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("必须通过 --spring.datasource.url 指定独立 educationagent_acceptance_<时间戳> 数据库"));
        String database=acceptanceDatabase(url);
        SpringApplication app=new SpringApplication(AcceptanceConfiguration.class);
        app.addInitializers(context -> {
            // 高于现有 profile 与 CLI 的保护项，防止误用产品数据目录或启动全局 schema/种子。
            Map<String,Object> guard=new LinkedHashMap<>();
            guard.put("spring.datasource.url",url);
            guard.put("spring.datasource.hikari.jdbc-url",url);
            if (url.startsWith("jdbc:h2:mem:")) {
                // 仅内存验收分支替换连接属性，避免 dev 的 MySQL 驱动或凭据被带入 H2。
                guard.put("spring.datasource.driver-class-name","org.h2.Driver");
                guard.put("spring.datasource.driverClassName","org.h2.Driver");
                guard.put("spring.datasource.username","sa");
                guard.put("spring.datasource.password","");
                guard.put("spring.datasource.hikari.username","sa");
                guard.put("spring.datasource.hikari.password","");
                guard.put("spring.datasource.hikari.driver-class-name","org.h2.Driver");
            }
            guard.put("spring.sql.init.mode","never");
            guard.put("spring.ai.mcp.client.enabled",false);
            guard.put("spring.data.redis.repositories.enabled",false);
            guard.put("management.health.redis.enabled",false);
            guard.put("classroom.data-dir",Path.of("target","classroom-acceptance",database).toAbsolutePath().normalize().toString());
            // 图片服务显式优先读取这个环境变量名，必须一起覆盖，不能让验收写入生产缓存目录。
            guard.put("CLASSROOM_DATA_DIR",guard.get("classroom.data-dir"));
            guard.put("server.address","127.0.0.1");
            guard.put("logging.level.com.zaxxer.hikari","WARN");
            guard.put("logging.level.org.mybatis","WARN");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("classroomAcceptanceIsolation",guard));
        });
        app.run(args);
    }

    /** 仅接受本机独立 MySQL 或固定参数的 H2 内存库；拒绝文件库、远程库、INIT 与连接脚本参数。 */
    static String acceptanceDatabase(String url) {
        try {
            if (url != null && url.startsWith("jdbc:h2:")) {
                var match=Pattern.compile("jdbc:h2:mem:(educationagent_acceptance_[0-9]{8}[_-]?[0-9]{6})"+Pattern.quote(H2_OPTIONS)).matcher(url);
                if (!match.matches()) throw new IllegalArgumentException();
                return match.group(1);
            }
            if(!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri=URI.create(url.substring(5));
            if(!Set.of("localhost","127.0.0.1").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getFragment()!=null)
                throw new IllegalArgumentException();
            String database=uri.getPath().substring(1);
            if(!database.matches("educationagent_acceptance_[0-9]{8}[_-]?[0-9]{6}")) throw new IllegalArgumentException();
            Set<String> params=Set.of("useunicode","characterencoding","servertimezone","usessl","allowpublickeyretrieval",
                    "createdatabaseifnotexist","connecttimeout","sockettimeout","useserverprepstmts");
            if(uri.getRawQuery()!=null) {
                for(String entry:uri.getRawQuery().split("&")) {
                    String key=entry.split("=",2)[0].toLowerCase(Locale.ROOT);
                    if(!params.contains(key)) throw new IllegalArgumentException();
                }
            }
            return database;
        } catch(RuntimeException invalid) {
            throw new IllegalArgumentException("验收入口只接受本机 MySQL 的独立验收库，或固定 MySQL 兼容参数的 H2 内存验收库；库名须为 educationagent_acceptance_YYYYMMDD_HHMMSS");
        }
    }

    /** 从产品 schema 选取必要建表语句。H2 仅去掉表级 ENGINE/字符集尾缀，不改列定义和约束。 */
    static String acceptanceSchemaSql(String schema, boolean h2) {
        StringBuilder selected=new StringBuilder();
        for(String table:new TreeSet<>(TABLES)) {
            var matcher=Pattern.compile("(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+"+Pattern.quote(table)+"\\s*\\([^;]*;").matcher(schema);
            if(!matcher.find()) throw new IllegalStateException("schema.sql 缺少必要课堂表："+table);
            String ddl=matcher.group();
            if(h2) {
                // 仅匹配本项目现有的固定表尾格式；未来出现其他 DDL 时失败并重新审查。
                Pattern tail=Pattern.compile("(?is)\\)\\s+ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4(?:\\s+COMMENT='[^']*')?\\s*;\\s*$");
                var tableTail=tail.matcher(ddl);
                if(!tableTail.find()) throw new IllegalStateException("课堂表尾格式发生变化，需检查 H2 适配："+table);
                ddl=tableTail.replaceFirst(");");
            }
            selected.append(ddl).append('\n');
        }
        return selected.toString();
    }

    @TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={RedisAutoConfiguration.class,RedisReactiveAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,MailSenderAutoConfiguration.class,TaskSchedulingAutoConfiguration.class},
            excludeName={"org.springframework.ai.mcp.client.autoconfigure.StdioTransportAutoConfiguration",
                    "org.springframework.ai.mcp.client.autoconfigure.SseWebFluxTransportAutoConfiguration",
                    "org.springframework.ai.mcp.client.autoconfigure.SseHttpClientTransportAutoConfiguration",
                    "org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration",
                    "org.springframework.ai.mcp.client.autoconfigure.McpToolCallbackAutoConfiguration"})
    @Import({ClassroomController.class,ClassroomNodeEngine.class,ClassroomTutorModel.class,ClassroomQuizAdapter.class,
            ClassroomVoiceService.class,ClassroomVoiceController.class,ClassroomWechatBridge.class,ClassroomWechatController.class,
            ClassroomMediaService.class,ClassroomMediaController.class,GuideController.class,
            FileService.class,QuizService.class,TtsService.class,AudioService.class,RestTemplateConfig.class,AiChatProperties.class,
            CrisisGuard.class,CrisisGuardProperties.class,CrisisCountRecorder.class,
            EduContentSafetyJudge.class,EduContentSafetyProperties.class})
    public static class AcceptanceConfiguration {
        @Bean static BeanFactoryPostProcessor verifyIsolationBeforeBeans(Environment environment) {
            // 即使有人绕过 main，直接导入此测试配置，也要在创建 DataSource 之前拒绝业务库。
            return factory -> acceptanceDatabase(environment.getRequiredProperty("spring.datasource.url"));
        }

        @Bean OpenAiChatModel deepseekChatModel(AiChatProperties aiChat) {
            if (!aiChat.hasApiKey()) throw new IllegalStateException("AI_CHAT_API_KEY 尚未配置");
            return aiChat.chatModelBuilder().temperature(0.3)
                    .maxRetries(0).timeout(Duration.ofSeconds(180)).logRequests(false).logResponses(false).build();
        }

        @Bean ClassroomCourseService classroomCourseService(ClassroomCourseMapper mapper,ObjectMapper json,ClassroomEngine engine,
                ClassroomTutorModel tutor,ClassroomQuizAdapter quizzes,FileService files,PlatformTransactionManager transactions) {
            return new ClassroomCourseService(mapper,json,engine,tutor,quizzes,files,noIndexRegistry(),
                    new TransactionTemplate(transactions),Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("classroom-acceptance-generation").daemon().factory()));
        }

        @Bean GuideService guideService(ClassroomTutorModel tutor,ObjectMapper json,CrisisGuardProperties crisisProperties) {
            // 手动装配真实服务，复用安全与模型 Bean，但不创建公共知识库注册中心的依赖链。
            return new GuideService(tutor,noIndexRegistry(),json,crisisProperties);
        }

        private static DocKnowledgeRegistry noIndexRegistry() {
            // 不是 Spring Bean：否则继承的 @Resource/@PostConstruct 会重新引入 Chroma 与索引任务。
            return new DocKnowledgeRegistry() {
                @Override public void init() { }
                @Override public DocKnowledgeAssistant getAssistant(String domainId) { return null; }
                @Override public Set<String> domainIds() { return Set.of(); }
                @Override public boolean reindex(String domainId) { return false; }
            };
        }

        @Bean InitializingBean acceptanceSchema(DataSource dataSource,@Value("${spring.datasource.url}") String url) {
            return () -> {
                String expected=acceptanceDatabase(url);
                try(var connection=dataSource.getConnection()) {
                    if(!expected.equals(connection.getCatalog())) throw new IllegalStateException("实际连接库名与独立验收库不一致，停止初始化");
                }
                String schema;
                try(var input=new ClassPathResource("schema.sql").getInputStream()) {
                    schema=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                }
                String selected=acceptanceSchemaSql(schema,url.startsWith("jdbc:h2:mem:"));
                ResourceDatabasePopulator population=new ResourceDatabasePopulator(new ByteArrayResource(selected.getBytes(StandardCharsets.UTF_8)));
                population.setContinueOnError(false); population.execute(dataSource);
            };
        }

        // 显式注册必要 Mapper，绝不 MapperScan 整个应用。
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<ClassroomCourseMapper> classroomCourseMapper(SqlSessionFactory f) { return mapper(ClassroomCourseMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<QuizPaperMapper> quizPaperMapper(SqlSessionFactory f) { return mapper(QuizPaperMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<QuizQuestionMapper> quizQuestionMapper(SqlSessionFactory f) { return mapper(QuizQuestionMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<QuizPassageMapper> quizPassageMapper(SqlSessionFactory f) { return mapper(QuizPassageMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<QuizAnswerMapper> quizAnswerMapper(SqlSessionFactory f) { return mapper(QuizAnswerMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<QuizKnowledgeFavMapper> quizKnowledgeFavMapper(SqlSessionFactory f) { return mapper(QuizKnowledgeFavMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<QuizQuestionFavMapper> quizQuestionFavMapper(SqlSessionFactory f) { return mapper(QuizQuestionFavMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<EduCrisisDailyMapper> eduCrisisDailyMapper(SqlSessionFactory f) { return mapper(EduCrisisDailyMapper.class,f); }
        @Bean @DependsOn("acceptanceSchema") MapperFactoryBean<EduUserClassMapper> eduUserClassMapper(SqlSessionFactory f) { return mapper(EduUserClassMapper.class,f); }

        private static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionFactory factory) {
            MapperFactoryBean<T> bean=new MapperFactoryBean<>(type); bean.setSqlSessionFactory(factory); return bean;
        }
    }
}
