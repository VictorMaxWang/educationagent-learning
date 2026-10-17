package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 用真正的短生命周期 Node 进程验证管道，不依赖实际生成包、模型或数据库。 */
class ClassroomNodeEngineTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();

    @Test void matchesLlmRequestAndResultIdsAcrossRealPipe() throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin});
            let job;
            rl.on('line',line=>{
              const msg=JSON.parse(line);
              if(msg.type==='job') {
                job=msg;
                if(msg.input.courseId!=='course-id'||msg.operation!=='outline') throw Error('wrong job');
                process.stdout.write(JSON.stringify({type:'llm.request',id:'call-1',messages:[{role:'user',content:'生成大纲'}]})+'\\n');
              } else if(msg.type==='llm.result') {
                if(msg.id!=='call-1'||msg.text!=='模型结果') throw Error('wrong response');
                process.stdout.write(JSON.stringify({type:'result',id:job.id,value:[{title:msg.text}]})+'\\n');
              }
            });
            """);
        ClassroomTutorModel tutor=mock(ClassroomTutorModel.class);
        when(tutor.complete(any())).thenReturn("模型结果");
        var bridge=new ClassroomNodeEngine(json,tutor,"node",script.toString(),5000);
        assertEquals("模型结果",bridge.outline(input(),new ClassroomCancellation()).get(0).path("title").asText());
        verify(tutor,times(1)).complete(any());
    }

    @Test void actualBridgePreservesDirectionAndQuizReviewOptions()throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin});
            rl.on('line',line=>{const m=JSON.parse(line);if(m.type==='job')process.stdout.write(JSON.stringify({type:'result',id:m.id,value:m.input})+'\\n');});
            """);
        var bridge=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",script.toString(),5000);
        ObjectNode course=input().put("direction","exploration");course.putObject("quizReview").put("enabled",true).put("maxRegenerationsPerQuestion",2);
        var actual=bridge.outline(course,new ClassroomCancellation());assertEquals("exploration",actual.path("direction").asText());
        assertEquals(2,actual.path("quizReview").path("maxRegenerationsPerQuestion").asInt());
    }

    @Test void invalidJsonAndWrongJobIdFailClosed() throws Exception {
        Path malformed=script("process.stdin.once('data',()=>process.stdout.write('not json\\n')); process.stdin.resume();");
        var bridge=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",malformed.toString(),5000);
        ClassroomException error=assertThrows(ClassroomException.class,()->bridge.outline(input(),new ClassroomCancellation()));
        assertEquals(502,error.status());
        Path wrong=script("process.stdin.once('data',()=>process.stdout.write(JSON.stringify({type:'result',id:'other-job',value:[]})+'\\n')); process.stdin.resume();");
        var other=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",wrong.toString(),5000);
        assertTrue(assertThrows(ClassroomException.class,()->other.outline(input(),new ClassroomCancellation())).getMessage().contains("不匹配"));
    }

    @Test void modelFailureIsReturnedToWorkerWithoutLeakingExceptionDetails() throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin}); let id;
            rl.on('line',line=>{const m=JSON.parse(line);
              if(m.type==='job'){id=m.id;process.stdout.write(JSON.stringify({type:'llm.request',id:'x',messages:[{role:'user',content:'x'}]})+'\\n');}
              else {process.stdout.write(JSON.stringify({type:'result',id,value:{error:m.error}})+'\\n');}
            });
            """);
        var tutor=mock(ClassroomTutorModel.class); when(tutor.complete(any())).thenThrow(new IllegalStateException("secret-token-example"));
        var bridge=new ClassroomNodeEngine(json,tutor,"node",script.toString(),5000);
        String error=bridge.outline(input(),new ClassroomCancellation()).path("error").asText();
        assertEquals("模型请求未完成，请重试",error); assertFalse(error.contains("secret"));
    }

    @Test void reviewPurposeIsValidatedAndForwardedWhilePlainRequestsKeepTheSingleArgumentCall() throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin}); let job;
            rl.on('line',line=>{const m=JSON.parse(line);
              if(m.type==='job'){job=m.id;
                process.stdout.write(JSON.stringify({type:'llm.request',id:'a',messages:[{role:'user',content:'出题'}]})+'\\n');
                process.stdout.write(JSON.stringify({type:'llm.request',id:'b',purpose:'quiz-review',messages:[{role:'user',content:'复核'}]})+'\\n');
              } else process.stdout.write(JSON.stringify({type:'result',id:job,value:[{title:m.text}]})+'\\n');
            });
            """);
        var tutor=mock(ClassroomTutorModel.class);
        when(tutor.complete(any())).thenReturn("生成模型");
        when(tutor.complete(any(),eq("quiz-review"))).thenReturn("复核模型");
        var bridge=new ClassroomNodeEngine(json,tutor,"node",script.toString(),5000);
        bridge.outline(input(),new ClassroomCancellation());
        // 不带用途仍走单参方法，既有替身与调用点不受影响。
        verify(tutor).complete(any());
        // 带用途必须转发到 Java，由它路由到 0 温度复核模型。
        verify(tutor).complete(any(),eq("quiz-review"));
    }

    @Test void unknownReviewPurposeFailsClosed() throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin});
            rl.on('line',()=>process.stdout.write(JSON.stringify({type:'llm.request',id:'x',purpose:'admin-debug',messages:[{role:'user',content:'x'}]})+'\\n'));
            """);
        var bridge=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",script.toString(),5000);
        ClassroomException error=assertThrows(ClassroomException.class,()->bridge.outline(input(),new ClassroomCancellation()));
        assertEquals(502,error.status());
        assertTrue(error.getMessage().contains("用途"));
    }

    @Test void severalModelRequestsInOneJobStayWithinTheGuard() throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin}); let job; let seen=0;
            rl.on('line',line=>{const m=JSON.parse(line);
              if(m.type==='job'){job=m.id; for(const id of ['a','b','c','d']) process.stdout.write(JSON.stringify({type:'llm.request',id,messages:[{role:'user',content:id}]})+'\\n');}
              else {seen++; if(seen===4) process.stdout.write(JSON.stringify({type:'result',id:job,value:[{title:m.text}]})+'\\n');}
            });
            """);
        var tutor=mock(ClassroomTutorModel.class); when(tutor.complete(any())).thenReturn("ok");
        var bridge=new ClassroomNodeEngine(json,tutor,"node",script.toString(),5000);
        assertEquals("ok",bridge.outline(input(),new ClassroomCancellation()).get(0).path("title").asText());
        // 出题、复核、重生成、重生成后复核合计最多 4 次，远低于 20 次上限：不需要放宽协议守卫。
        verify(tutor,times(4)).complete(any());
    }

    @Test void quizReviewOptionsReachTheGeneratorJob() throws Exception {
        Path script=script("""
            const rl=require('node:readline').createInterface({input:process.stdin}); let job;
            rl.on('line',line=>{const m=JSON.parse(line);
              if(m.type==='job'){job=m.id;process.stdout.write(JSON.stringify({type:'result',id:job,value:[{title:JSON.stringify(m.input.quizReview)}]})+'\\n');}
            });
            """);
        var bridge=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",script.toString(),5000);
        ObjectNode withReview=input(); withReview.putObject("quizReview").put("enabled",true).put("maxRegenerationsPerQuestion",2);
        String value=bridge.outline(withReview,new ClassroomCancellation()).get(0).path("title").asText();
        // 开关握在 Java 手里，关掉复核不需要重新构建生成器。
        assertTrue(value.contains("maxRegenerationsPerQuestion"),value);
    }

    @Test void timeoutEndsOnlyOwnedProcess() throws Exception {
        Path script=script("require('node:fs').writeFileSync(__filename+'.pid',String(process.pid));process.stdin.resume();");
        var bridge=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",script.toString(),1000);
        ClassroomException error=assertThrows(ClassroomException.class,()->bridge.outline(input(),new ClassroomCancellation()));
        assertEquals(504,error.status());
        long pid=Long.parseLong(Files.readString(Path.of(script+".pid")));
        assertTimeoutPreemptively(Duration.ofSeconds(3),()-> { while(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(20); });
    }

    @Test void cancellingBlockedWorkerReturnsPromptly() throws Exception {
        Path script=script("require('node:fs').writeFileSync(__filename+'.pid',String(process.pid));process.stdin.resume();");
        var bridge=new ClassroomNodeEngine(json,mock(ClassroomTutorModel.class),"node",script.toString(),30000);
        ClassroomCancellation token=new ClassroomCancellation();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> running=pool.submit(()->bridge.outline(input(),token));
            assertTimeoutPreemptively(Duration.ofSeconds(5),()-> { while(!Files.exists(Path.of(script+".pid"))) Thread.sleep(20); });
            token.cancel();
            ExecutionException failure=assertThrows(ExecutionException.class,()->running.get(3,TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class,failure.getCause());
            long pid=Long.parseLong(Files.readString(Path.of(script+".pid")));
            assertTimeoutPreemptively(Duration.ofSeconds(3),()-> { while(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(20); });
        }
    }

    @Test void healthLoadsDependenciesRatherThanOnlyCheckingWorkerPresence() throws Exception {
        var tutor = mock(ClassroomTutorModel.class);
        Path broken = script("require('educationagent-missing-test-module');");
        var invalid = new ClassroomNodeEngine(json, tutor, "node", broken.toString(), 5000);
        assertEquals(true, invalid.health().get("workerBuilt"));
        assertEquals(false, invalid.health().get("ready"));
        Path valid = script("process.stdout.write(JSON.stringify({type:'ready',engine:'educationagent'}));");
        var ready = new ClassroomNodeEngine(json, tutor, "node", valid.toString(), 5000);
        assertEquals(true, ready.health().get("ready"));
        verifyNoInteractions(tutor);
    }

    @Test void healthCachesForThirtySecondsAndReportsWhenTheRealCheckWasMade() throws Exception {
        Path valid = script("if(process.argv.includes('--check')){require('node:fs').appendFileSync(__filename+'.checks','x');process.stdout.write(JSON.stringify({type:'ready',engine:'educationagent'}));}");
        AtomicLong now = new AtomicLong();
        var bridge = new ClassroomNodeEngine(json, mock(ClassroomTutorModel.class), "node", valid.toString(), 5000, now::get, Clock.systemUTC());
        var first = bridge.health();
        assertEquals(true, first.get("ready"));
        assertEquals(false, first.get("cached"));
        assertNotNull(first.get("checkedAt"));
        var cached = bridge.health();
        assertEquals(true, cached.get("cached"));
        assertEquals(first.get("checkedAt"), cached.get("checkedAt"));
        assertEquals("x", Files.readString(Path.of(valid + ".checks")));
        now.set(TimeUnit.SECONDS.toNanos(30));
        assertEquals(false, bridge.health().get("cached"));
        assertEquals("xx", Files.readString(Path.of(valid + ".checks")));
    }

    @Test void parallelHealthRequestsShareOneRealProbe() throws Exception {
        Path valid = script("if(process.argv.includes('--check')){require('node:fs').appendFileSync(__filename+'.checks','x');Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,150);process.stdout.write(JSON.stringify({type:'ready',engine:'educationagent'}));}");
        var bridge = new ClassroomNodeEngine(json, mock(ClassroomTutorModel.class), "node", valid.toString(), 5000);
        CountDownLatch ready = new CountDownLatch(6), start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<java.util.Map<String,Object>>> calls = java.util.stream.IntStream.range(0,6).mapToObj(index -> pool.submit(() -> {
                ready.countDown(); start.await(); return bridge.health();
            })).toList();
            assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown();
            for (var call : calls) assertEquals(true, call.get(8, TimeUnit.SECONDS).get("ready"));
        }
        assertEquals("x", Files.readString(Path.of(valid + ".checks")));
    }

    @Test void aWorkerProtocolFailureInvalidatesCachedHealthWithoutAffectingOtherProcesses() throws Exception {
        Path worker = script("if(process.argv.includes('--check')){require('node:fs').appendFileSync(__filename+'.checks','x');process.stdout.write(JSON.stringify({type:'ready',engine:'educationagent'}));}else{process.stdin.once('data',()=>process.stdout.write('bad protocol\\n'));process.stdin.resume();}");
        var bridge = new ClassroomNodeEngine(json, mock(ClassroomTutorModel.class), "node", worker.toString(), 5000);
        assertEquals(true, bridge.health().get("ready"));
        assertThrows(ClassroomException.class, () -> bridge.outline(input(), new ClassroomCancellation()));
        assertEquals(false, bridge.health().get("cached"));
        assertEquals("xx", Files.readString(Path.of(worker + ".checks")));
    }

    @Test void remainingJobBudgetBoundsTheTutorCallAndIsReturnedToTheWorker() throws Exception {
        Path worker = script("""
            const rl=require('node:readline').createInterface({input:process.stdin});let job;
            rl.on('line',line=>{const msg=JSON.parse(line);
              if(msg.type==='job'){job=msg;if(!(msg.remainingMs>0&&msg.remainingMs<=5000))throw Error('missing budget');process.stdout.write(JSON.stringify({type:'llm.request',id:'budget-call',purpose:'quiz-review',timeoutMs:1000,messages:[{role:'user',content:'复核'}]})+'\\n');}
              else{if(!(msg.remainingMs>=0&&msg.remainingMs<=job.remainingMs))throw Error('budget extended');process.stdout.write(JSON.stringify({type:'result',id:job.id,value:[{title:msg.text}]})+'\\n');}
            });
            """);
        var tutor = mock(ClassroomTutorModel.class);
        when(tutor.complete(any(), eq("quiz-review"), anyLong(), any())).thenReturn("budgeted");
        var bridge = new ClassroomNodeEngine(json, tutor, "node", worker.toString(), 5000);
        assertEquals("budgeted", bridge.outline(input(), new ClassroomCancellation()).get(0).path("title").asText());
        var budget = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(tutor).complete(any(), eq("quiz-review"), budget.capture(), any());
        assertTrue(budget.getValue() > 0 && budget.getValue() <= 1000);
        verify(tutor, never()).complete(any());
    }

    @Test void invalidPerCallBudgetsFailClosedBeforeTheTutorIsInvoked() throws Exception {
        Path worker = script("const rl=require('node:readline').createInterface({input:process.stdin});rl.on('line',()=>process.stdout.write(JSON.stringify({type:'llm.request',id:'x',timeoutMs:999999999,messages:[{role:'user',content:'x'}]})+'\\n'));");
        var tutor = mock(ClassroomTutorModel.class);
        var bridge = new ClassroomNodeEngine(json, tutor, "node", worker.toString(), 5000);
        assertEquals(502, assertThrows(ClassroomException.class, () -> bridge.outline(input(), new ClassroomCancellation())).status());
        verifyNoInteractions(tutor);
    }

    private ObjectNode input() { return json.createObjectNode().put("id","course-id").put("topic","分数").put("grade",4); }
    private Path script(String contents) throws Exception {
        Path path=temp.resolve("worker-"+java.util.UUID.randomUUID()+".cjs"); Files.writeString(path,contents); return path;
    }
}
