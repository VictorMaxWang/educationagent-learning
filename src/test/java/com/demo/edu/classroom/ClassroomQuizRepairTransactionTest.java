package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.QuizAnswerMapper;
import com.demo.mapper.QuizQuestionMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizMutationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实SQL和事务覆盖写入回滚、首次作答提交边界；不以mock内存赋值代替持久层隔离证明。 */
class ClassroomQuizRepairTransactionTest {
    final ObjectMapper json=new ObjectMapper();final String id=UUID.randomUUID().toString();
    final ExecutorService workers=Executors.newFixedThreadPool(2);
    JdbcTemplate jdbc;TransactionTemplate tx;ClassroomCourseService courses;QuizQuestionMapper questions;
    String document;boolean unconfirmedUpdate;

    @BeforeEach void setup()throws Exception {
        var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:quiz_repair_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(data);jdbc.execute("CREATE TABLE edu_learning_course(id VARCHAR(64) PRIMARY KEY,document CLOB)");
        jdbc.execute("CREATE TABLE quiz_question(id BIGINT PRIMARY KEY,paper_id BIGINT,question_no INT,stem VARCHAR(2000),answer VARCHAR(200))");
        jdbc.execute("CREATE TABLE quiz_answer(question_id BIGINT,is_correct INT)");
        var manager=new DataSourceTransactionManager(data);tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var mapper=mock(ClassroomCourseMapper.class);questions=mock(QuizQuestionMapper.class);var answers=mock(QuizAnswerMapper.class);
        when(mapper.find(anyString())).thenAnswer(call->jdbc.queryForObject("SELECT document FROM edu_learning_course WHERE id=?",String.class,new Object[]{call.getArgument(0,String.class)}));
        when(mapper.save(anyString(),anyString())).thenAnswer(call->jdbc.update("UPDATE edu_learning_course SET document=? WHERE id=?",new Object[]{call.getArgument(1,String.class),call.getArgument(0,String.class)}));
        when(questions.selectOne(any())).thenAnswer(call->jdbc.queryForObject("SELECT * FROM quiz_question WHERE id=11 FOR UPDATE",(rs,no)->{
            var row=new QuizQuestionEntity();row.setId(rs.getLong("id"));row.setPaperId(rs.getLong("paper_id"));row.setQuestionNo(rs.getInt("question_no"));row.setStem(rs.getString("stem"));row.setAnswer(rs.getString("answer"));return row;
        }));
        when(questions.updateById(any(QuizQuestionEntity.class))).thenAnswer(call->{
            QuizQuestionEntity row=call.getArgument(0);int affected=jdbc.update("UPDATE quiz_question SET stem=?,answer=? WHERE id=?",row.getStem(),row.getAnswer(),row.getId());
            return unconfirmedUpdate?0:affected;
        });
        when(answers.selectCount(any())).thenAnswer(call->jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer WHERE question_id=11",Long.class));
        courses=new ClassroomCourseService(mapper,json,mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),mock(ClassroomQuizAdapter.class),mock(FileService.class),mock(DocKnowledgeRegistry.class),tx,workers);
        courses.setQuizQuestionMapper(questions);courses.setQuizAnswerMapper(answers);
        ObjectNode course=(ObjectNode)json.readTree("""
            {"title":"体积课","topic":"长方体体积","grade":5,"direction":"academic","domain":"general","subject":"数学","status":"ready","revision":3,
             "scenes":[{"id":"s","type":"quiz","title":"体积练习","quizId":7,"content":{"type":"quiz","questions":[
               {"id":"q","no":1,"type":"single","question":"20×8×3是多少？","options":[{"value":"A","label":"480"},{"value":"B","label":"240"}],"answer":["B"],"analysis":"原错误解析"}]},"actions":[]}],
             "progress":{"sceneIndex":0,"actionIndex":2},"messages":[]}
            """);
        course.put("id",id);document=course.toString();jdbc.update("INSERT INTO edu_learning_course VALUES(?,?)",id,document);
        jdbc.update("INSERT INTO quiz_question VALUES(11,7,1,'20×8×3是多少？','B')");
    }
    @AfterEach void close(){workers.shutdownNow();}

    @Test void unconfirmedRowUpdateRollsBackBothRealTables()throws Exception {
        unconfirmedUpdate=true;var plan=plan();String fingerprint=ClassroomCourseService.quizRepairFingerprint(courses.internalDocument(id));
        assertEquals(409,assertThrows(ClassroomException.class,()->courses.applyQuizRepairs(id,fingerprint,Map.of("s",plan))).status());
        assertEquals("B",jdbc.queryForObject("SELECT answer FROM quiz_question WHERE id=11",String.class));
        assertEquals(document,jdbc.queryForObject("SELECT document FROM edu_learning_course WHERE id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
    }

    @Test void firstAnswerLockStaysHeldAfterMethodReturnsUntilItsTransactionCommits()throws Exception {
        JsonNode plan=plan();String fingerprint=ClassroomCourseService.quizRepairFingerprint(courses.internalDocument(id));
        CountDownLatch methodReturned=new CountDownLatch(1),allowCommit=new CountDownLatch(1),repairStarted=new CountDownLatch(1);
        var grade=workers.submit(()->tx.execute(status->{
            QuizMutationGuard.withPaper(7L,()->{jdbc.update("INSERT INTO quiz_answer VALUES(11,0)");return true;});
            // 模拟@Transactional服务体已返回，但外层代理还没有完成commit。
            methodReturned.countDown();try{assertTrue(allowCommit.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}return true;
        }));
        assertTrue(methodReturned.await(5,TimeUnit.SECONDS));
        var repair=workers.submit(()->{repairStarted.countDown();return courses.applyQuizRepairs(id,fingerprint,Map.of("s",plan));});
        try {
            assertTrue(repairStarted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->repair.get(200,TimeUnit.MILLISECONDS));
            assertEquals("B",jdbc.queryForObject("SELECT answer FROM quiz_question WHERE id=11",String.class));
        }finally{allowCommit.countDown();}
        assertTrue(grade.get(5,TimeUnit.SECONDS));
        var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->repair.get(5,TimeUnit.SECONDS));assertInstanceOf(ClassroomException.class,failure.getCause());
        assertEquals(409,((ClassroomException)failure.getCause()).status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer",Integer.class));
        assertEquals(document,jdbc.queryForObject("SELECT document FROM edu_learning_course WHERE id=?",String.class,id));
    }

    private JsonNode plan()throws Exception {
        ObjectNode change=(ObjectNode)json.readTree("""
            {"no":1,"id":"q","action":"regenerate","verification":{"blindSolve":"agree","explanation":"consistent"},
             "after":{"type":"single","question":"20×8×3是多少？","options":[{"value":"A","label":"480"},{"value":"B","label":"240"}],"answer":["A"],"analysis":"20×8×3=480。"}}
            """);
        change.set("before",ClassroomCourseService.quizRepairQuestionSnapshot(courses.internalDocument(id).path("scenes").get(0).path("content").path("questions").get(0)));
        return json.createArrayNode().add(change);
    }
}
