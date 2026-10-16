package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.demo.study.quiz.QuizMutationGuard;
import com.demo.study.quiz.QuizService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 固定锁与事务开始顺序，而不把H2的快照实现等同于MySQL RR：建立一致性快照前必须已持有试卷锁。 */
class ClassroomAnswerRevisionConcurrencyTest {
    @Test void revisionCommittedBetweenPreReadAndPaperGuardStopsOldSubmission()throws Exception {
        var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:answer_revision_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(data);jdbc.execute("CREATE TABLE course_doc(id VARCHAR(64),document CLOB)");jdbc.execute("CREATE TABLE supersession(paper_id BIGINT PRIMARY KEY)");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(data));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        String id=UUID.randomUUID().toString();String document="{\"id\":\""+id+"\",\"status\":\"ready\",\"revision\":1,\"submissions\":{},\"scenes\":[{\"id\":\"s\",\"type\":\"quiz\",\"quizId\":7,\"content\":{\"questions\":[]}}]}";
        jdbc.update("INSERT INTO course_doc VALUES(?,?)",id,document);
        var mapper=mock(ClassroomCourseMapper.class);var adapter=mock(ClassroomQuizAdapter.class);var registry=mock(ClassroomQuizRevisionRegistry.class);
        var preRead=new CountDownLatch(1);var registered=new CountDownLatch(1);var reads=new AtomicInteger();
        when(mapper.find(id)).thenAnswer(call->{
            if(reads.incrementAndGet()==1) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"课程预读不能先建立RR事务快照");
                String result=jdbc.queryForObject("SELECT document FROM course_doc WHERE id=?",String.class,id);preRead.countDown();assertTrue(registered.await(5,TimeUnit.SECONDS));return result;
            }
            return jdbc.queryForObject("SELECT document FROM course_doc WHERE id=?",String.class,id);
        });
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"指针应在持有paper锁但开始作答事务之前检查");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM supersession WHERE paper_id=7",Integer.class)>0)throw new ClassroomException(409,"旧练习已修订");return null;
        }).when(registry).assertScorable(7L);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var service=new ClassroomCourseService(mapper,new ObjectMapper(),mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),adapter,mock(FileService.class),mock(DocKnowledgeRegistry.class),tx,workers);
            ReflectionTestUtils.setField(service,"quizRevisions",registry);
            var submission=workers.submit(()->service.answer(id,new ClassroomCourseService.AnswerRequest("s","request-1",List.of(new QuizService.AnswerSubmission(1,"A")))));
            try {
                assertTrue(preRead.await(5,TimeUnit.SECONDS));QuizMutationGuard.withPaper(7L,()->tx.execute(status->{jdbc.update("INSERT INTO supersession VALUES(7)");return true;}));
            }finally{registered.countDown();}
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->submission.get(5,TimeUnit.SECONDS));assertInstanceOf(ClassroomException.class,failure.getCause());assertEquals(409,((ClassroomException)failure.getCause()).status());
            verifyNoInteractions(adapter);verify(mapper,never()).save(anyString(),anyString());assertEquals(document,jdbc.queryForObject("SELECT document FROM course_doc WHERE id=?",String.class,id));
        }
    }
}
