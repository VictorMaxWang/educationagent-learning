package com.demo.edu.classroom;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.edu.classroom.study.ClassroomStudyStore;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.*;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 可选真实MySQL验收：必须由根任务显式提供TEST_MYSQL_NATIVE_*，绝不读取正式库配置。
 * 仅访问显式本机高位端口、名称以_test_native结尾且有本测试所有权标记的库；模型由本机替身提供。
 */
@EnabledIfEnvironmentVariable(named="TEST_MYSQL_NATIVE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class QuizMySqlIntegrationTest {
    private static final String OWNER="QuizMySqlIntegrationTest-20261017";
    private static final String MARKER="_quiz_native_fixture_owner";
    private static final List<String> TABLES=List.of("quiz_mistake_resolution","quiz_attempt_request","quiz_answer","quiz_question_fav",
            "quiz_knowledge_fav","quiz_passage","quiz_question","quiz_paper","edu_classroom_study_state","edu_learning_course");
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final Map<String,String> outcomes=new LinkedHashMap<>();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SqlSessionTemplate sql;
    private QuizService quiz;
    private ClassroomCourseService courses;
    private ClassroomCourseMapper courseMapper;
    private QuizQuestionMapper questionMapper;
    private QuizAnswerMapper answerMapper;
    private QuizPaperMapper paperMapper;
    private OpenAiChatModel model;

    @RegisterExtension final TestWatcher watcher=new TestWatcher() {
        @Override public void testSuccessful(ExtensionContext context){outcomes.put(context.getRequiredTestMethod().getName(),"PASS");}
        @Override public void testFailed(ExtensionContext context,Throwable error){outcomes.put(context.getRequiredTestMethod().getName(),"FAILED:"+error.getClass().getSimpleName());}
    };

    @BeforeAll void connectOnlyToOwnedNativeTestDatabase() throws Exception {
        String url=System.getenv("TEST_MYSQL_NATIVE_URL");
        if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("仅允许显式的本机MySQL测试连接");
        URI parsed=URI.create(url.substring("jdbc:".length()));
        String database=parsed.getPath()==null?"":parsed.getPath().replaceFirst("^/","");
        int port=parsed.getPort();
        if(parsed.getHost()==null||!Set.of("127.0.0.1","localhost","[::1]","::1").contains(parsed.getHost())||port<=1023||port>65535||port==3306||parsed.getUserInfo()!=null
                ||!database.matches("[a-zA-Z0-9_]+_test_native")||parsed.getFragment()!=null)
            throw new IllegalArgumentException("仅允许显式loopback高位端口及_test_native数据库，禁止3306");
        if(parsed.getRawQuery()!=null)for(String item:parsed.getRawQuery().split("&")) {
            String key=item.split("=",2)[0];
            if(!Set.of("useUnicode","characterEncoding","useSSL","allowPublicKeyRetrieval","serverTimezone","connectTimeout","socketTimeout").contains(key))
                throw new IllegalArgumentException("测试URL包含未允许的连接选项");
        }
        String user=System.getenv("TEST_MYSQL_NATIVE_USER"),password=System.getenv("TEST_MYSQL_NATIVE_PASSWORD");
        if(user==null||user.isBlank()||password==null||"root".equalsIgnoreCase(user.split("@",2)[0]))
            throw new IllegalArgumentException("必须使用独立的非root测试数据库凭据");
        var data=new DriverManagerDataSource(url,user,password);data.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc=new JdbcTemplate(data);
        assertEquals(database,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(port,jdbc.queryForObject("SELECT @@port",Integer.class),"禁止通过转发端口误连其他实例");
        String actualUser=jdbc.queryForObject("SELECT CURRENT_USER()",String.class);
        assertTrue(actualUser!=null&&!"root".equalsIgnoreCase(actualUser.split("@",2)[0]),"服务器实际认证用户也必须不是root");
        String version=jdbc.queryForObject("SELECT VERSION()",String.class);
        assertTrue(version!=null&&version.startsWith("8.0."),"本验收需要与生产同系列的MySQL8.0");
        report.put("database",database);report.put("mysqlVersion",version);report.put("modelMode","local-stub");report.put("paidModelCalls",0);report.put("nonRootTestUser",true);
        report.put("transactionIsolation",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        Set<String> existing=new HashSet<>(jdbc.queryForList("SHOW TABLES",String.class));
        if(existing.contains(MARKER))assertEquals(OWNER,jdbc.queryForObject("SELECT owner FROM "+MARKER+" WHERE id=1",String.class));
        else {
            // 有任何未知数据就拒绝认领。测试不能靠数据库后缀绕过夹具归属检查。
            assertTrue(TABLES.containsAll(existing),"无所有权标记且含未知表，拒绝认领");
            for(String table:TABLES)if(existing.contains(table))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
            jdbc.execute("CREATE TABLE "+MARKER+"(id INT PRIMARY KEY,owner VARCHAR(100) NOT NULL)");
            jdbc.update("INSERT INTO "+MARKER+" VALUES(1,?)",OWNER);
        }
        String schema=Files.readString(Path.of("src/main/resources/schema.sql"));
        String history=Files.readString(Path.of("deploy/sql/20261017-quiz-history.sql"));
        for(String table:TABLES) {
            String source=table.equals("quiz_mistake_resolution")||table.equals("quiz_attempt_request")?history:schema;
            var definition=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS\\s+"+Pattern.quote(table)+"\\s*\\(.*?;").matcher(source);
            if(!definition.find())throw new IllegalStateException("缺少生产表定义："+table);
            jdbc.execute(definition.group());
            if(table.equals("edu_classroom_study_state")) {
                // 可复跑的独占夹具按当前生产DDL补齐索引；这不代表正式库的迁移已执行。
                Set<String> indexes=new HashSet<>(jdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='edu_classroom_study_state'",String.class));
                var index=Pattern.compile("KEY\\s+(idx_study_[a-z_]+)\\s*\\([^\\r\\n]+\\)").matcher(definition.group());
                while(index.find())if(!indexes.contains(index.group(1)))jdbc.execute("ALTER TABLE edu_classroom_study_state ADD "+index.group());
            }
        }
        // 仅构造本测试拥有的隔离库：模拟旧表0900、新表继承unicode的生产升级组合，不能全用同一collation掩盖跨表比较错误。
        jdbc.execute("ALTER DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        Map<String,String> collations=Map.of("quiz_answer","utf8mb4_0900_ai_ci","quiz_attempt_request","utf8mb4_unicode_ci","quiz_mistake_resolution","utf8mb4_unicode_ci");
        for(var entry:collations.entrySet()) {
            String actual=jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='user_id'",String.class,entry.getKey());
            if(!entry.getValue().equals(actual))jdbc.execute("ALTER TABLE "+entry.getKey()+" CONVERT TO CHARACTER SET utf8mb4 COLLATE "+entry.getValue());
            assertEquals(entry.getValue(),jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='user_id'",String.class,entry.getKey()));
        }
        report.put("databaseCollation",jdbc.queryForObject("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=DATABASE()",String.class));
        report.put("quizUserIdCollations",collations);
        manager=new DataSourceTransactionManager(data);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.setLogImpl(NoLoggingImpl.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(data);factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());factory.setGlobalConfig(GlobalConfigUtils.defaults());factory.afterPropertiesSet();
        var sessionFactory=Objects.requireNonNull(factory.getObject());
        for(Class<?> mapper:List.of(QuizAnswerMapper.class,QuizQuestionMapper.class,QuizPaperMapper.class,QuizPassageMapper.class,
                QuizKnowledgeFavMapper.class,QuizQuestionFavMapper.class,ClassroomCourseMapper.class))sessionFactory.getConfiguration().addMapper(mapper);
        sql=new SqlSessionTemplate(sessionFactory);answerMapper=sql.getMapper(QuizAnswerMapper.class);questionMapper=sql.getMapper(QuizQuestionMapper.class);
        paperMapper=sql.getMapper(QuizPaperMapper.class);courseMapper=sql.getMapper(ClassroomCourseMapper.class);
    }

    @BeforeEach void resetOnlyOwnedFixtureTables() {
        assertEquals(OWNER,jdbc.queryForObject("SELECT owner FROM "+MARKER+" WHERE id=1",String.class));
        for(String table:TABLES)jdbc.update("DELETE FROM "+table);
        model=mock(OpenAiChatModel.class);quiz=new QuizService();quiz.setTransactionManager(manager);
        ((TransactionTemplate)ReflectionTestUtils.getField(quiz,"transactions")).setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ReflectionTestUtils.setField(quiz,"answerMapper",answerMapper);ReflectionTestUtils.setField(quiz,"questionMapper",questionMapper);
        ReflectionTestUtils.setField(quiz,"paperMapper",paperMapper);ReflectionTestUtils.setField(quiz,"passageMapper",sql.getMapper(QuizPassageMapper.class));
        ReflectionTestUtils.setField(quiz,"knowledgeFavMapper",sql.getMapper(QuizKnowledgeFavMapper.class));
        ReflectionTestUtils.setField(quiz,"questionFavMapper",sql.getMapper(QuizQuestionFavMapper.class));ReflectionTestUtils.setField(quiz,"deepseekChatModel",model);
        var adapter=new ClassroomQuizAdapter(courseMapper,quiz,json);
        courses=new ClassroomCourseService(courseMapper,json,mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),adapter,
                mock(FileService.class),mock(DocKnowledgeRegistry.class),manager);
        courses.setQuizQuestionMapper(questionMapper);courses.setQuizAnswerMapper(answerMapper);
        ((TransactionTemplate)ReflectionTestUtils.getField(courses,"transactions")).setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @AfterEach void closeCourseWorker(){if(courses!=null)courses.close();}

    @Test void nativeAppendOnlyResolutionIdempotencyAndPendingStatistics() {
        Lesson lesson=lesson("short_answer","比较基准相同");
        jdbc.update("INSERT INTO quiz_answer(user_id,paper_id,question_id,user_answer,is_correct,answered_at) VALUES('classroom',?,?,'旧错误答案',0,'2026-10-16 12:00:00')",lesson.paperId(),lesson.questionId());
        Map<String,Object> old=jdbc.queryForMap("SELECT * FROM quiz_answer WHERE paper_id=?",lesson.paperId());
        reply("{\"no\":1,\"correct\":null,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"尚不能确定\"}");
        var uncertain=quiz.mistakeRedo("classroom",lesson.paperId(),lesson.questionId(),"仍有疑问","native-unknown");
        assertNull(uncertain.correct());assertFalse(uncertain.removed());
        assertEquals(uncertain,quiz.mistakeRedo("classroom",lesson.paperId(),lesson.questionId(),"仍有疑问","native-unknown"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
        reply("{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"合理同义表达\"}");
        var solved=quiz.mistakeRedo("classroom",lesson.paperId(),lesson.questionId(),"两块地一样大","native-correct");
        assertTrue(solved.correct());assertTrue(solved.removed());assertTrue(quiz.mistakes("classroom").isEmpty());
        assertEquals(old,jdbc.queryForMap("SELECT * FROM quiz_answer WHERE id=?",old.get("id")));
        var stats=quiz.knowledgeAgg("classroom").getFirst();
        assertEquals(2,stats.confirmedCount());assertEquals(1,stats.pendingCount());assertEquals(50,stats.accuracy());
        assertEquals(solved,quiz.mistakeRedo("classroom",lesson.paperId(),lesson.questionId(),"两块地一样大","native-correct"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));verify(model,times(2)).chat(any(ChatRequest.class));
    }

    @Test void nativeMixedCollationMistakesBindEachUserColumnAndPreserveOriginalHistory() {
        Lesson lesson=lesson("fill","24");
        jdbc.update("INSERT INTO quiz_answer(user_id,paper_id,question_id,user_answer,is_correct,answered_at) VALUES('classroom',?,?,'旧错误答案',0,'2026-10-16 12:00:00'),('collation-other',?,?,'另一人的原答卷',0,'2026-10-16 12:00:00')",lesson.paperId(),lesson.questionId(),lesson.paperId(),lesson.questionId());
        Map<String,Object> old=jdbc.queryForMap("SELECT * FROM quiz_answer WHERE user_id='classroom'");
        var legacyFailure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList(
                "SELECT a.id FROM quiz_answer a WHERE a.user_id=? AND a.is_correct=0 AND NOT EXISTS(SELECT 1 FROM quiz_mistake_resolution r WHERE r.user_id=a.user_id AND r.question_id=a.question_id AND r.resolved=1)","classroom"));
        boolean collation1267=false;
        for(Throwable cause=legacyFailure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sqlError&&sqlError.getErrorCode()==1267)collation1267=true;
        assertTrue(collation1267,"夹具必须真实复现原跨collation列比较的1267，不能用统一排序规则绕过问题");
        assertEquals(1,quiz.mistakes("classroom").size());assertEquals(1,answerMapper.selectUnresolvedMistakes("collation-other").size());
        var solved=quiz.mistakeRedo("classroom",lesson.paperId(),lesson.questionId(),"24","native-mixed-collation");
        assertTrue(solved.correct());assertTrue(solved.removed());assertTrue(quiz.mistakes("classroom").isEmpty());
        assertEquals(1,answerMapper.selectUnresolvedMistakes("collation-other").size(),"别人的同题错题不能被移出");
        assertEquals(old,jdbc.queryForMap("SELECT * FROM quiz_answer WHERE id=?",old.get("id")));
        assertEquals(solved,quiz.mistakeRedo("classroom",lesson.paperId(),lesson.questionId(),"24","native-mixed-collation"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
        assertEquals(50,quiz.knowledgeAgg("classroom").getFirst().accuracy());verifyNoInteractions(model);
        report.put("mixedCollationRegression",Map.of("legacyErrorCode",1267,"fixedMistakeReads","PASS","originalAnswerUnchanged",true,"otherLearnerUnchanged",true));
    }

    @Test void nativeRequestSequencePreventsLateOldAnswerFromReopeningResolvedMistake() {
        Lesson lesson=lesson("fill","24");
        var older=quiz.prepareRedo("classroom",lesson.paperId(),lesson.questionId(),"11","native-older");
        var newer=quiz.prepareRedo("classroom",lesson.paperId(),lesson.questionId(),"24","native-newer");
        var oldResult=quiz.evaluateSubmission(older);var newResult=quiz.evaluateSubmission(newer);
        quiz.commitPrepared(newer,newResult);quiz.commitPrepared(older,oldResult);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));assertTrue(quiz.mistakes("classroom").isEmpty());
        assertEquals(newer.sequence(),jdbc.queryForObject("SELECT request_sequence FROM quiz_mistake_resolution WHERE question_id=?",Long.class,lesson.questionId()));
    }

    @Test void nativeRepeatableReadRejectsAnOriginalQuestionRepairWhileModelIsRunning() throws Exception {
        Lesson lesson=lesson("short_answer","比较基准相同");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        ChatResponse response=answerResponse("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"原题评阅结果\"}]");
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return response;});
        try(var workers=Executors.newSingleThreadExecutor()) {
            var grading=workers.submit(()->courses.answer(lesson.courseId(),new ClassroomCourseService.AnswerRequest("s","native-race",List.of(new QuizService.AnswerSubmission(1,"一样大")))));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try {
                ObjectNode before=courses.internalDocument(lesson.courseId());ObjectNode old=(ObjectNode)before.path("scenes").get(0).path("content").path("questions").get(0);
                ObjectNode changed=ClassroomCourseService.quizRepairQuestionSnapshot(old);changed.put("question","比较之前应先核对什么？").put("answer","整体与单位需要一致").put("analysis","先确认整体与单位再比较。");
                ObjectNode change=json.createObjectNode().put("no",1).put("id",old.path("id").asText()).put("action","regenerate");
                change.set("before",ClassroomCourseService.quizRepairQuestionSnapshot(old));change.set("after",changed);
                change.putObject("verification").put("blindSolve","agree").put("explanation","consistent");
                var applied=courses.applyQuizRepairs(lesson.courseId(),ClassroomCourseService.quizRepairFingerprint(before),Map.of("s",json.createArrayNode().add(change)));
                assertEquals(1,applied.path("applied").asInt());
            }finally{release.countDown();}
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->grading.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ClassroomException.class,failure.getCause());assertEquals(409,((ClassroomException)failure.getCause()).status());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
            assertEquals("整体与单位需要一致",questionMapper.selectById(lesson.questionId()).getAnswer());
        }finally{release.countDown();}
    }

    @Test void nativeReceiptFailureRollsBackAnswerAndResolutionThenSameFixedResultCanCommitOnce() {
        Lesson lesson=lesson("fill","24");
        var prepared=quiz.prepareSubmission("classroom",lesson.paperId(),List.of(new QuizService.AnswerSubmission(1,"24")),"native-rollback");
        var evaluated=quiz.evaluateSubmission(prepared);
        jdbc.execute("ALTER TABLE quiz_attempt_request ADD CONSTRAINT ck_native_receipt_failure CHECK (request_id<>'native-rollback' OR state<>'complete')");
        try {
            assertThrows(RuntimeException.class,()->quiz.commitPrepared(prepared,evaluated));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_mistake_resolution",Integer.class));
            assertEquals("evaluated",jdbc.queryForObject("SELECT state FROM quiz_attempt_request WHERE request_id='native-rollback'",String.class));
        }finally{jdbc.execute("ALTER TABLE quiz_attempt_request DROP CHECK ck_native_receipt_failure");}
        assertEquals(1,quiz.commitPrepared(prepared,evaluated).score());
        assertEquals(1,quiz.commitPrepared(prepared,evaluated).score());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
    }

    @Test void nativeSlowModelGetAndProgressEachTwentyCallsStayBelowFiveHundredMillisecondsP95() throws Exception {
        Lesson lesson=lesson("short_answer","比较基准相同");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        var response=answerResponse("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表达正确\"}]");
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());started.countDown();assertTrue(release.await(45,TimeUnit.SECONDS));return response;});
        try(var workers=Executors.newFixedThreadPool(2)) {
            var grading=workers.submit(()->courses.answer(lesson.courseId(),new ClassroomCourseService.AnswerRequest("s","native-slow",List.of(new QuizService.AnswerSubmission(1,"两块地一样大")))));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            List<Double> reads=new ArrayList<>(),progress=new ArrayList<>();
            try {
                var measures=workers.submit(()->{
                    for(int i=0;i<20;i++) {
                        long begin=System.nanoTime();courses.get(lesson.courseId());reads.add((System.nanoTime()-begin)/1_000_000.0);
                        begin=System.nanoTime();courses.saveProgress(lesson.courseId(),0,i%2);progress.add((System.nanoTime()-begin)/1_000_000.0);
                    }
                });
                measures.get(25,TimeUnit.SECONDS);
            }finally{release.countDown();}
            double getP95=p95(reads),progressP95=p95(progress);
            report.put("slowModelGet",Map.of("count",reads.size(),"p95Millis",getP95,"samplesMillis",reads));
            report.put("slowModelProgress",Map.of("count",progress.size(),"p95Millis",progressP95,"samplesMillis",progress));
            assertEquals(20,reads.size());assertEquals(20,progress.size());assertTrue(getP95<500,"GET p95必须低于500ms");assertTrue(progressP95<500,"progress p95必须低于500ms");
            assertEquals(1,grading.get(5,TimeUnit.SECONDS).score());assertEquals(1,courses.get(lesson.courseId()).path("progress").path("actionIndex").asInt());
        }finally{release.countDown();}
    }

    @Test void nativeCourseSummaryProjectsJsonTypesAndExcludesLargePrivateContent() throws Exception {
        Lesson lesson=lesson("fill","24");
        ObjectNode document=courses.internalDocument(lesson.courseId());
        document.putArray("messages").addObject().put("text","PRIVATE_BODY_SENTINEL"+"x".repeat(100_000));
        document.withArray("outlines").addObject().put("title","面积");
        courseMapper.save(lesson.courseId(),document.toString());
        List<String> summaries=courseMapper.listSummaries();assertEquals(1,summaries.size());
        String raw=summaries.getFirst();JsonNode summary=json.readTree(raw);
        assertEquals(lesson.courseId(),summary.path("id").asText());assertEquals("原课堂",summary.path("title").asText());
        assertTrue(summary.path("grade").isIntegralNumber());assertEquals(5,summary.path("grade").asInt());
        assertEquals(1,summary.path("sceneCount").asInt());assertEquals(1,summary.path("outlineCount").asInt());
        assertEquals(0,summary.path("revision").asInt());assertFalse(summary.has("scenes"));assertFalse(summary.has("messages"));
        assertFalse(raw.contains("PRIVATE_BODY_SENTINEL"));assertTrue(raw.length()<2000);
        report.put("courseSummaryProjection",Map.of("rows",summaries.size(),"responseCharacters",raw.length(),"fixturePrivateCharacters",100_000,"numericGrade",true));
    }

    @Test void nativeTenThousandLearningRowsUseBoundedCoveringWindowsBeforeReadingDocuments() {
        Lesson lesson=lesson("fill","24");
        Instant base=Instant.parse("2026-10-16T00:00:00Z");
        List<Integer> numbers=java.util.stream.IntStream.range(0,10_000).boxed().toList();
        new TransactionTemplate(manager).executeWithoutResult(status->jdbc.batchUpdate(
                "INSERT INTO edu_classroom_study_state(kind,entry_id,course_id,scene_id,document,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",numbers,500,
                (statement,i)->{
                    statement.setString(1,"learning_attempt");statement.setString(2,String.format(Locale.ROOT,"native-history-%05d",i));
                    // 十门课程各一千条、五页各二百条；避免把单课程全集的成本选择误当成缺索引。
                    statement.setString(3,i<1000?lesson.courseId():lesson.courseId()+"-"+(i/1000));statement.setString(4,"s-"+(i%5));
                    statement.setString(5,"{\"sceneId\":\"s-"+(i%5)+"\",\"attempt\":"+i+"}");
                    Timestamp time=Timestamp.from(base.plusSeconds(i));statement.setTimestamp(6,time);statement.setTimestamp(7,time);
                }));
        jdbc.execute("ANALYZE TABLE edu_classroom_study_state");
        // 直接解释Store实际使用的SQL；派生表键集有序且已限量，外层的小集合排序允许保留。
        String recentSql=(String)ReflectionTestUtils.getField(ClassroomStudyStore.class,"RECENT_DOCUMENTS_SQL");
        String sceneSql=(String)ReflectionTestUtils.getField(ClassroomStudyStore.class,"SCENE_DOCUMENTS_SQL");
        Object[] recentArgs={"learning_attempt",lesson.courseId(),20,"learning_attempt"};
        Object[] sceneArgs={"learning_attempt",lesson.courseId(),"s-4",30,"learning_attempt"};
        Object[] maxArgs={"learning_attempt",lesson.courseId(),200,"learning_attempt"};
        var recent=jdbc.queryForList("EXPLAIN "+recentSql,recentArgs);
        var scene=jdbc.queryForList("EXPLAIN "+sceneSql,sceneArgs);
        var maximum=jdbc.queryForList("EXPLAIN "+recentSql,maxArgs);
        Map<String,Object> plans=new LinkedHashMap<>();plans.put("fixtureRows",10_000);plans.put("courses",10);plans.put("scenesPerCourse",5);
        plans.put("recentWindow20",recent);plans.put("sceneWindow30",scene);plans.put("recentMaximum200",maximum);
        plans.put("recentAnalyze",jdbc.queryForList("EXPLAIN ANALYZE "+recentSql,String.class,recentArgs));
        plans.put("sceneAnalyze",jdbc.queryForList("EXPLAIN ANALYZE "+sceneSql,String.class,sceneArgs));
        plans.put("maximumAnalyze",jdbc.queryForList("EXPLAIN ANALYZE "+recentSql,String.class,maxArgs));
        report.put("studyHistoryIndexPlans",plans);
        assertCoveringWindow(recent,"idx_study_course_kind_recent",20,true);
        assertCoveringWindow(scene,"idx_study_course_kind_created",30,true);
        // 200条上限处MySQL可能选同课程覆盖索引并排序键；正文回表仍必须被LIMIT约束，不能据此强制某个索引。
        assertCoveringWindow(maximum,null,200,false);
        var store=new ClassroomStudyStore(jdbc,json);
        var recentRows=store.list("learning_attempt",lesson.courseId(),200);
        var sceneRows=store.listForScene("learning_attempt",lesson.courseId(),"s-4",30);
        assertEquals(200,recentRows.size());assertEquals(999,recentRows.getFirst().path("attempt").asInt());
        assertEquals(30,sceneRows.size());assertEquals(999,sceneRows.getFirst().path("attempt").asInt());
        assertTrue(sceneRows.stream().allMatch(row->row.path("sceneId").asText().equals("s-4")));
        for(int i=1;i<recentRows.size();i++)assertTrue(recentRows.get(i-1).path("attempt").asInt()>recentRows.get(i).path("attempt").asInt());
        List<Double> recentMillis=new ArrayList<>(),sceneMillis=new ArrayList<>(),maximumMillis=new ArrayList<>();
        for(int i=0;i<20;i++) {
            long start=System.nanoTime();assertEquals(20,store.list("learning_attempt",lesson.courseId(),20).size());recentMillis.add((System.nanoTime()-start)/1_000_000.0);
            start=System.nanoTime();assertEquals(30,store.listForScene("learning_attempt",lesson.courseId(),"s-4",30).size());sceneMillis.add((System.nanoTime()-start)/1_000_000.0);
            start=System.nanoTime();assertEquals(200,store.list("learning_attempt",lesson.courseId(),200).size());maximumMillis.add((System.nanoTime()-start)/1_000_000.0);
        }
        plans.put("recent20P95Millis",p95(recentMillis));plans.put("scene30P95Millis",p95(sceneMillis));plans.put("maximum200P95Millis",p95(maximumMillis));
        plans.put("recent20SamplesMillis",recentMillis);plans.put("scene30SamplesMillis",sceneMillis);plans.put("maximum200SamplesMillis",maximumMillis);
        assertTrue(p95(recentMillis)<500);assertTrue(p95(sceneMillis)<500);assertTrue(p95(maximumMillis)<500);
    }

    private static void assertCoveringWindow(List<Map<String,Object>> plan,String expectedIndex,int limit,boolean requireOrderedInput) {
        Map<String,Object> inner=plan.stream().filter(row->"DERIVED".equals(row.get("select_type"))).findFirst().orElseThrow();
        if(expectedIndex!=null)assertEquals(expectedIndex,inner.get("key"));
        assertTrue(String.valueOf(inner.get("key")).startsWith("idx_study_course_kind_"));
        assertTrue(Set.of("ref","range").contains(String.valueOf(inner.get("type"))));
        assertTrue(String.valueOf(inner.get("Extra")).contains("Using index"),"候选集合必须只读索引键");
        if(requireOrderedInput)assertFalse(String.valueOf(inner.get("Extra")).toLowerCase(Locale.ROOT).contains("filesort"));
        Map<String,Object> bounded=plan.stream().filter(row->String.valueOf(row.get("table")).startsWith("<derived")).findFirst().orElseThrow();
        assertTrue(((Number)bounded.get("rows")).intValue()<=limit,"正文join前必须已限量");
        Map<String,Object> bodies=plan.stream().filter(row->"body".equals(row.get("table"))).findFirst().orElseThrow();
        assertEquals("eq_ref",bodies.get("type"));assertEquals("PRIMARY",bodies.get("key"));assertEquals(1,((Number)bodies.get("rows")).intValue());
    }

    @AfterAll void reportWithoutCredentials() throws Exception {
        report.put("finishedAt",Instant.now().toString());report.put("tests",outcomes);
        Path directory=Path.of("target/online-hardening-20261017");Files.createDirectories(directory);
        Path output=directory.resolve("native-mysql-quiz-report-"+Instant.now().toString().replaceAll("[:.]","-")+".json");
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println("NATIVE_QUIZ_REPORT="+output.toAbsolutePath());
    }

    private record Lesson(String courseId,long paperId,long questionId) {}
    private Lesson lesson(String type,String answer) {
        String id=UUID.randomUUID().toString();var paper=new QuizPaperEntity();paper.setUserId("classroom");paper.setTitle("原课堂");
        paper.setFileType("classroom");paper.setSourceFileName("classroom:"+id+":s");paper.setQuestionCount(1);paper.setDeleted(0);paperMapper.insert(paper);
        var q=new QuizQuestionEntity();q.setPaperId(paper.getId());q.setQuestionNo(1);q.setQType(type.equals("short_answer")?"short":type);
        q.setStem("第1题");q.setOptions("[]");q.setAnswer(answer);q.setExplanation("依据参考要点评阅");q.setKnowledgePoint("面积");q.setHasAnswer(1);questionMapper.insert(q);
        assertNotNull(q.getId());assertNotNull(paper.getId());
        ObjectNode course=json.createObjectNode().put("id",id).put("title","原课堂").put("topic","面积").put("grade",5).put("subject","数学")
                .put("domain","general").put("direction","academic").put("status","ready").put("revision",0);
        course.putObject("submissions");course.putArray("outlines");course.putArray("messages");course.putObject("progress").put("sceneIndex",0).put("actionIndex",0);
        ObjectNode scene=course.putArray("scenes").addObject().put("id","s").put("type","quiz").put("title","练习").put("quizId",paper.getId());
        scene.putArray("actions").addObject().put("id","speech").put("type","speech").put("text","先独立思考");
        scene.putObject("content").put("type","quiz").putArray("questions").addObject().put("id","q").put("no",1)
                .put("type",type).put("question","第1题").put("answer",answer).put("analysis","依据参考要点评阅").putArray("options");
        courseMapper.save(id,course.toString());return new Lesson(id,paper.getId(),q.getId());
    }
    private ChatResponse answerResponse(String body){var response=mock(ChatResponse.class,RETURNS_DEEP_STUBS);when(response.aiMessage().text()).thenReturn(body);return response;}
    private void reply(String body){doReturn(answerResponse(body)).when(model).chat(any(ChatRequest.class));}
    private static double p95(List<Double> values){List<Double> sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get((int)Math.ceil(sorted.size()*0.95)-1);}
}
