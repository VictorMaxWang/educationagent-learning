package com.demo.study.quiz;

import com.demo.edu.classroom.ClassroomException;
import com.demo.edu.classroom.ClassroomQuizRevisionRegistry;
import com.demo.edu.classroom.ClassroomCancellation;
import com.demo.edu.classroom.ai.ClassroomModelAdmission;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.ArrayList;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 已确认接缝：公开prepare/evaluate/commit与redo/statistics，实际SQL历史、序列和回执可核查。 */
class QuizPreparedSubmissionTest {
    @Test void correctRedoAppendsAndSuccessfulRequestReplayDoesNotRegradeOrRewriteHistory() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        f.jdbc.update("INSERT INTO quiz_answer(user_id,paper_id,question_id,user_answer,is_correct,answered_at) VALUES('classroom',7,11,'11',0,'2026-10-16 12:00:00')");
        var before=f.history().getFirst();
        var success=f.service.mistakeRedo("classroom",7L,11L,"24","redo-1");
        assertTrue(success.correct());assertTrue(success.removed());assertEquals(before,f.history().getFirst());assertEquals(2,f.history().size());
        assertEquals(success,f.service.mistakeRedo("classroom",7L,11L,"24","redo-1"));assertEquals(2,f.history().size());
        assertTrue(f.service.mistakes("classroom").isEmpty());
        assertEquals(409,assertThrows(ClassroomException.class,()->f.service.mistakeRedo("classroom",7L,11L,"25","redo-1")).status());
        verifyNoInteractions(f.model);
    }

    @Test void laterCorrectRequestCannotBeReopenedByAnEarlierWrongEvaluationThatArrivesLate() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        var older=f.service.prepareRedo("classroom",7L,11L,"11","older");
        var newer=f.service.prepareRedo("classroom",7L,11L,"24","newer");
        var oldResult=f.service.evaluateSubmission(older);var newResult=f.service.evaluateSubmission(newer);
        assertTrue(newer.sequence()>older.sequence());
        f.service.commitPrepared(newer,newResult);f.service.commitPrepared(older,oldResult);
        assertEquals(2,f.history().size());assertEquals(1,((Number)f.history().get(0).get("is_correct")).intValue());
        assertEquals(0,((Number)f.history().get(1).get("is_correct")).intValue());
        assertTrue(f.service.mistakes("classroom").isEmpty());
        assertEquals(newer.sequence(),f.jdbc.queryForObject("SELECT request_sequence FROM quiz_mistake_resolution",Long.class));
        var actualNewError=f.service.mistakeRedo("classroom",7L,11L,"23","new-error");
        assertFalse(actualNewError.correct());assertFalse(actualNewError.removed());assertEquals(1,f.service.mistakes("classroom").size());
    }

    @Test void manuallyRemovingOnlyChangesResolutionAndOlderPendingResponseCannotUndoIt() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        f.service.mistakeRedo("classroom",7L,11L,"11","wrong-before");
        var before=f.history();var pending=f.service.prepareRedo("classroom",7L,11L,"23","pending-old");
        var result=f.service.evaluateSubmission(pending);
        f.service.mistakeRemove("classroom",11L);
        assertEquals(before,f.history());assertTrue(f.service.mistakes("classroom").isEmpty());
        f.service.commitPrepared(pending,result);
        assertEquals(before.getFirst(),f.history().getFirst());assertTrue(f.service.mistakes("classroom").isEmpty());
    }

    @Test void fixedQuestionFingerprintRejectsAChangedKeyAfterEvaluationWithoutAnyAnswerWrite() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        var prepared=f.service.prepareSubmission("classroom",7L,List.of(new QuizService.AnswerSubmission(1,"24")),"changed-key");
        var evaluated=f.service.evaluateSubmission(prepared);
        QuizMutationGuard.withPaper(7L,()->{f.jdbc.update("UPDATE quiz_question SET answer='25' WHERE id=11");return true;});
        assertEquals(409,assertThrows(ClassroomException.class,()->f.service.commitPrepared(prepared,evaluated)).status());
        assertTrue(f.history().isEmpty());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM quiz_mistake_resolution",Integer.class));
    }

    @Test void commitOnlyAcceptsTheExactDurablySavedEvaluation() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        var prepared=f.service.prepareSubmission("classroom",7L,List.of(new QuizService.AnswerSubmission(1,"24")),"fixed-evaluation");
        var valid=f.service.evaluateSubmission(prepared);
        var altered=new QuizService.EvaluatedSubmission(prepared.sequence(),prepared.questionFingerprint(),valid.evaluationId(),
                new QuizService.SubmitResult(7L,0,1,List.of(new QuizService.ResultItem(1,false,"25","24","被替换的结论"))));
        assertEquals(409,assertThrows(ClassroomException.class,()->f.service.commitPrepared(prepared,altered)).status());
        assertTrue(f.history().isEmpty());
        assertEquals(1,f.service.commitPrepared(prepared,valid).score());assertEquals(1,f.history().size());
    }

    @Test void successfulHistoryReceiptReplaysAfterSupersessionButNewRequestsStop() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        var submitted=List.of(new QuizService.AnswerSubmission(1,"24"));
        var first=f.service.submit("classroom",7L,submitted,"saved");
        var revisions=mock(ClassroomQuizRevisionRegistry.class);
        doThrow(new ClassroomException(409,"已修订")).when(revisions).assertScorable(7L);
        ReflectionTestUtils.setField(f.service,"quizRevisions",revisions);
        assertEquals(first,f.service.submit("classroom",7L,submitted,"saved"));assertEquals(1,f.history().size());
        assertEquals(409,assertThrows(ClassroomException.class,()->f.service.submit("classroom",7L,submitted,"fresh")).status());
        assertEquals(1,f.history().size());
    }

    @Test void unknownHistoryDoesNotReduceConfirmedAccuracyAndUnknownOnlyGroupHasNullAccuracy() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");f.question(12,2,"short","比较基准相同","面积");
        f.question(13,3,"short","示例说明","另一个知识点");
        var unknown=mock(ChatResponse.class,RETURNS_DEEP_STUBS);when(unknown.aiMessage().text()).thenReturn("[]");
        when(f.model.chat(any(ChatRequest.class))).thenReturn(unknown);
        f.service.submit("classroom",7L,List.of(new QuizService.AnswerSubmission(1,"24")),"known");
        var pending=f.service.submit("classroom",7L,List.of(new QuizService.AnswerSubmission(2,"一样大"),new QuizService.AnswerSubmission(3,"自己的说明")),"unknown");
        assertEquals(0,pending.confirmedCount());assertEquals(2,pending.pendingCount());assertEquals("pending",pending.status());
        var results=f.service.knowledgeAgg("classroom");
        var area=results.stream().filter(item->item.knowledgePoint().equals("面积")).findFirst().orElseThrow();
        assertEquals(100,area.accuracy());assertEquals(1,area.confirmedCount());assertEquals(1,area.pendingCount());assertEquals(2,area.questionCount());
        var other=results.stream().filter(item->item.knowledgePoint().equals("另一个知识点")).findFirst().orElseThrow();
        assertNull(other.accuracy());assertEquals("pending",other.status());assertEquals(1,other.pendingCount());
        assertTrue(f.service.mistakes("classroom").isEmpty());
    }

    @Test void unknownRedoIsAppendedOnceAndRequestReplayDoesNotResolveOrCallModelAgain() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        f.jdbc.update("INSERT INTO quiz_answer(user_id,paper_id,question_id,user_answer,is_correct) VALUES('classroom',7,11,'原错误答案',0)");
        var original=f.history().getFirst();
        var response=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(response.aiMessage().text()).thenReturn("{\"no\":1,\"correct\":null,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"尚不能确定\"}");
        when(f.model.chat(any(ChatRequest.class))).thenReturn(response);
        var first=f.service.mistakeRedo("classroom",7L,11L,"我还不确定","unknown-redo");
        assertNull(first.correct());assertFalse(first.removed());assertEquals(2,f.history().size());assertEquals(original,f.history().getFirst());
        assertEquals(-1,((Number)f.history().get(1).get("is_correct")).intValue());
        assertEquals(first,f.service.mistakeRedo("classroom",7L,11L,"我还不确定","unknown-redo"));
        assertEquals(2,f.history().size());assertEquals(1,f.service.mistakes("classroom").size());
        var stats=f.service.knowledgeAgg("classroom").getFirst();assertEquals(1,stats.confirmedCount());assertEquals(1,stats.pendingCount());
        assertEquals(0,stats.accuracy());verify(f.model,times(1)).chat(any(ChatRequest.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM quiz_mistake_resolution",Integer.class));
    }

    @Test void slowSemanticModelRunsOutsideTransactionsAndConcurrentSameRequestCallsShareOneEvaluation() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var reply=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(reply.aiMessage().text()).thenReturn("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表达正确\"}]");
        when(f.model.chat(any(ChatRequest.class))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return reply;
        });
        var answers=List.of(new QuizService.AnswerSubmission(1,"两块地一样大"));
        var a=f.service.prepareSubmission("classroom",7L,answers,"same");var b=f.service.prepareSubmission("classroom",7L,answers,"same");
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->f.service.evaluateSubmission(a));assertTrue(started.await(5,TimeUnit.SECONDS));
            var second=workers.submit(()->f.service.evaluateSubmission(b));
            // 评阅等待时另一个连接仍可读取题库，不存在持锁长事务。
            assertEquals("比较基准相同",f.jdbc.queryForObject("SELECT answer FROM quiz_question WHERE id=11",String.class));
            release.countDown();var one=first.get(5,TimeUnit.SECONDS);var two=second.get(5,TimeUnit.SECONDS);
            assertEquals(one.evaluationId(),two.evaluationId());
            f.service.commitPrepared(a,one);f.service.commitPrepared(b,two);
            assertEquals(1,f.history().size());verify(f.model,times(1)).chat(any(ChatRequest.class));
        } finally {release.countDown();}
    }

    @Test void failureSavingReceiptRollsBackTheAppendedAnswerAndResolutionTogether() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"fill","24","面积");
        var prepared=f.service.prepareSubmission("classroom",7L,List.of(new QuizService.AnswerSubmission(1,"24")),"receipt-failure");
        var evaluated=f.service.evaluateSubmission(prepared);
        doReturn(0).when(f.answers).finishAttemptRequest(anyLong(),anyString(),anyString(),anyString());
        assertEquals(409,assertThrows(ClassroomException.class,()->f.service.commitPrepared(prepared,evaluated)).status());
        assertTrue(f.history().isEmpty());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM quiz_mistake_resolution",Integer.class));
    }

    @Test void flightOwnerRereadsCommittedReceiptAfterLosingTheInitialReadRace() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var reply=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(reply.aiMessage().text()).thenReturn("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表达\"}]");
        when(f.model.chat(any(ChatRequest.class))).thenReturn(reply);
        var answers=List.of(new QuizService.AnswerSubmission(1,"两块地一样大"));
        var one=f.service.prepareSubmission("classroom",7L,answers,"race");var two=f.service.prepareSubmission("classroom",7L,answers,"race");
        var captured=new CountDownLatch(1);var resume=new CountDownLatch(1);var paused=new AtomicBoolean();
        doAnswer(call->{
            var rows=f.jdbc.queryForList("SELECT id,user_id AS userId,request_id AS requestId,paper_id AS paperId,operation,payload_fingerprint AS payloadFingerprint,"+
                    "question_fingerprint AS questionFingerprint,state,result_json AS resultJson,last_evaluation_id AS lastEvaluationId FROM quiz_attempt_request WHERE user_id=? AND request_id=?",
                    new Object[]{call.getArgument(0,String.class),call.getArgument(1,String.class)});
            var read=rows.isEmpty()?null:rows.getFirst();
            if(Thread.currentThread().getName().equals("late-evaluator")&&paused.compareAndSet(false,true)) {
                captured.countDown();assertTrue(resume.await(5,TimeUnit.SECONDS));
            }
            return read;
        }).when(f.answers).findAttemptRequest(anyString(),anyString());
        try(var workers=Executors.newSingleThreadExecutor(r->new Thread(r,"late-evaluator"))) {
            var late=workers.submit(()->f.service.evaluateSubmission(two));assertTrue(captured.await(5,TimeUnit.SECONDS));
            try { var evaluated=f.service.evaluateSubmission(one);f.service.commitPrepared(one,evaluated); }
            finally {resume.countDown();}
            assertEquals(1,late.get(5,TimeUnit.SECONDS).result().score());verify(f.model,times(1)).chat(any(ChatRequest.class));
            assertEquals(1,f.history().size());
        }finally{resume.countDown();}
    }

    @Test void completedEvaluationRemainsSharedUntilTheOuterCourseTransactionCommits() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var reply=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(reply.aiMessage().text()).thenReturn("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表达\"}]");
        when(f.model.chat(any(ChatRequest.class))).thenReturn(reply);
        var answers=List.of(new QuizService.AnswerSubmission(1,"两块地一样大"));
        var first=f.service.prepareSubmission("classroom",7L,answers,"outer");var duplicate=f.service.prepareSubmission("classroom",7L,answers,"outer");
        var evaluated=f.service.evaluateSubmission(first);var inside=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var workers=Executors.newSingleThreadExecutor()) {
            var transaction=workers.submit(()->QuizMutationGuard.withPaper(7L,()->new TransactionTemplate(f.manager).execute(status->{
                f.service.commitPrepared(first,evaluated);inside.countDown();
                try{assertTrue(finish.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return true;
            })));
            assertTrue(inside.await(5,TimeUnit.SECONDS));
            try {
                assertEquals("evaluated",f.jdbc.queryForObject("SELECT state FROM quiz_attempt_request WHERE request_id='outer'",String.class));
                var shared=f.service.evaluateSubmission(duplicate);assertEquals(evaluated.evaluationId(),shared.evaluationId());
                verify(f.model,times(1)).chat(any(ChatRequest.class));
            }finally{finish.countDown();}
            assertTrue(transaction.get(5,TimeUnit.SECONDS));assertEquals(1,f.history().size());
        }finally{finish.countDown();}
    }

    @Test void interruptedDurableModelClaimDoesNotIssueAnotherModelCallAfterRestart() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var answers=List.of(new QuizService.AnswerSubmission(1,"两块地一样大"));
        var prepared=f.service.prepareSubmission("classroom",7L,answers,"crashed");
        assertEquals(1,f.answers.claimAttemptEvaluation(prepared.sequence(),"potentially-sent"));
        var restarted=f.restartedService();var recovered=restarted.prepareSubmission("classroom",7L,answers,"crashed");
        var failure=assertThrows(ClassroomException.class,()->restarted.evaluateSubmission(recovered));
        assertEquals(409,failure.status());assertEquals("EVALUATION_UNKNOWN",failure.code());
        assertEquals("unknown",f.jdbc.queryForObject("SELECT state FROM quiz_attempt_request WHERE request_id='crashed'",String.class));
        assertTrue(f.history().isEmpty());verifyNoInteractions(f.model);
        assertEquals("EVALUATION_UNKNOWN",assertThrows(ClassroomException.class,()->restarted.submit("classroom",7L,answers,"crashed")).code());
        verifyNoInteractions(f.model);
    }

    @Test void fixedEvaluationSurvivesRestartBeforeGradeCommitWithoutAnotherModelCall() throws Exception {
        var f=new QuizPersistenceFixture();f.question(11,1,"short","比较基准相同","面积");
        var reply=mock(ChatResponse.class,RETURNS_DEEP_STUBS);
        when(reply.aiMessage().text()).thenReturn("[{\"no\":1,\"correct\":true,\"correctAnswer\":\"比较基准相同\",\"explanation\":\"同义表达\"}]");
        when(f.model.chat(any(ChatRequest.class))).thenReturn(reply);
        var answers=List.of(new QuizService.AnswerSubmission(1,"两块地一样大"));
        var prepared=f.service.prepareSubmission("classroom",7L,answers,"restart-ready");f.service.evaluateSubmission(prepared);
        assertTrue(f.history().isEmpty());assertEquals("evaluated",f.jdbc.queryForObject("SELECT state FROM quiz_attempt_request WHERE request_id='restart-ready'",String.class));
        assertEquals(1,f.restartedService().submit("classroom",7L,answers,"restart-ready").score());
        assertEquals(1,f.history().size());verify(f.model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void semanticBatchesShareOneDeadlineInsteadOfResettingFortyFiveSecondsForEachCall() throws Exception {
        var f=new QuizPersistenceFixture();List<QuizService.AnswerSubmission> answers=new ArrayList<>();
        for(int i=1;i<=11;i++){f.question(10+i,i,"short","参考表达","概念");answers.add(new QuizService.AnswerSubmission(i,"学生表达"));}
        var admission=mock(ClassroomModelAdmission.class);ReflectionTestUtils.setField(f.service,"modelAdmission",admission);
        List<Duration> budgets=new ArrayList<>();
        when(admission.execute(any(Duration.class),any(ClassroomCancellation.class),any())).thenAnswer(call->{
            Duration remaining=call.getArgument(0);budgets.add(remaining);
            if(budgets.size()==1)Thread.sleep(25);
            Function<Duration,ChatResponse> work=call.getArgument(2);return work.apply(remaining);
        });
        var batch=new AtomicInteger();
        when(f.model.chat(any(ChatRequest.class))).thenAnswer(call->{
            int number=batch.getAndIncrement();List<String> items=new ArrayList<>();
            for(int no=number*10+1;no<=Math.min(11,(number+1)*10);no++)items.add("{\"no\":"+no+",\"correct\":true,\"correctAnswer\":\"参考表达\",\"explanation\":\"同义表达\"}");
            var result=mock(ChatResponse.class,RETURNS_DEEP_STUBS);when(result.aiMessage().text()).thenReturn("["+String.join(",",items)+"]");return result;
        });
        var evaluated=f.service.evaluateSubmission(f.service.prepareSubmission("classroom",7L,answers,"batch-deadline"));
        assertEquals(11,evaluated.result().score());assertEquals(2,budgets.size());
        assertTrue(budgets.get(0).compareTo(Duration.ofSeconds(45))<=0);
        assertTrue(budgets.get(1).toNanos()<budgets.get(0).toNanos()-Duration.ofMillis(10).toNanos(),"后批只能获得同一次45秒截止时间的剩余预算");
    }
}
