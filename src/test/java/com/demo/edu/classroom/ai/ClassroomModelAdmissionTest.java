package com.demo.edu.classroom.ai;

import com.demo.edu.classroom.ClassroomCancellation;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomModelAdmissionTest {
    private ClassroomModelAdmission admission(int concurrent,int waiting,long waitMs){var p=new ClassroomModelAdmissionProperties();p.setMaxConcurrent(concurrent);p.setMaxWaiting(waiting);p.setMaxWait(Duration.ofMillis(waitMs));return new ClassroomModelAdmission(p);}
    @Test void preservesExistingVoiceCancellationIdentityWithoutChangingCallerThread(){
        try(var admission=admission(1,1,50)){com.demo.util.VoiceContext.setUserId("test-voice-owner");
            assertEquals("test-voice-owner",admission.execute(Duration.ofSeconds(1),null,budget->com.demo.util.VoiceContext.getUserId()));
            assertEquals("test-voice-owner",com.demo.util.VoiceContext.getUserId());
        }finally{com.demo.util.VoiceContext.clear();}
    }
    @Test void synchronousNestedCallReusesOneLeaseButAsyncWorkCannotInheritIt() {
        try(var admission=admission(1,0,40)) {
            assertEquals("nested",admission.execute(Duration.ofSeconds(2),null,outer->admission.execute(Duration.ofSeconds(1),null,inner->{
                assertEquals(1,admission.snapshot().active());assertEquals(0,admission.snapshot().waiting());assertTrue(inner.compareTo(outer)<0);
                try(var other=Executors.newVirtualThreadPerTaskExecutor()) {
                    assertEquals(429,other.submit(()->assertThrows(ClassroomModelAdmission.Rejected.class,()->admission.execute(Duration.ofSeconds(1),null,budget->"outside")).status()).get(1,TimeUnit.SECONDS));
                }catch(Exception error){throw new IllegalStateException(error);}
                return "nested";
            })));
        }
    }
    @Test void nestedDeadlineReturnsUnknownWithoutReleasingAnUnfinishedPhysicalCall() throws Exception {
        try(var admission=admission(1,0,40);var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            try {
                var pending=callers.submit(()->admission.execute(Duration.ofSeconds(2),null,outer->admission.execute(Duration.ofMillis(70),null,inner->{
                    entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return "late";
                })));
                assertTrue(entered.await(1,TimeUnit.SECONDS));var failure=assertThrows(ExecutionException.class,()->pending.get(600,TimeUnit.MILLISECONDS));
                assertInstanceOf(ClassroomModelAdmission.Rejected.class,failure.getCause());assertEquals(1,admission.snapshot().active());
                assertEquals(429,assertThrows(ClassroomModelAdmission.Rejected.class,()->admission.execute(Duration.ofSeconds(1),null,budget->"too-early")).status());
            }finally{release.countDown();}
        }
    }
    @Test void cancelledUncooperativeCallKeepsPermitUntilPhysicalExit() throws Exception {
        try(var admission=admission(1,0,50);var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),left=new CountDownLatch(1);var token=new ClassroomCancellation();
            Future<?> pending=callers.submit(()->assertThrows(CancellationException.class,()->admission.execute(Duration.ofSeconds(3),token,remaining->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}left.countDown();return "finished";})));
            assertTrue(entered.await(1,TimeUnit.SECONDS));token.cancel();pending.get(1,TimeUnit.SECONDS);
            var rejected=assertThrows(ClassroomModelAdmission.Rejected.class,()->admission.execute(Duration.ofSeconds(1),null,budget->"should-not-run"));
            assertEquals(429,rejected.status());assertEquals(1,admission.snapshot().active());release.countDown();assertTrue(left.await(1,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(admission.snapshot().active()!=0&&System.nanoTime()<deadline)Thread.onSpinWait();
            assertEquals("next",admission.execute(Duration.ofSeconds(1),null,budget->"next"));
        }
    }
    @Test void queueIsBoundedAndWaitConsumesTotalBudget() throws Exception {
        try(var admission=admission(1,1,500);var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            Future<?> first=callers.submit(()->admission.execute(Duration.ofSeconds(2),null,budget->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            Future<Duration> second=callers.submit(()->admission.execute(Duration.ofSeconds(1),null,budget->{calls.incrementAndGet();return budget;}));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(admission.snapshot().waiting()!=1&&System.nanoTime()<end)Thread.onSpinWait();
            assertEquals(429,assertThrows(ClassroomModelAdmission.Rejected.class,()->admission.execute(Duration.ofSeconds(1),null,budget->null)).status());
            Thread.sleep(60);release.countDown();first.get(1,TimeUnit.SECONDS);assertTrue(second.get(1,TimeUnit.SECONDS).toMillis()<990);assertEquals(1,calls.get());
        }
    }
    @Test void expiredQueuedCallNeverStartsAndNoAutomaticRetryOccurs() throws Exception {
        try(var admission=admission(1,1,40);var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            Future<?> first=callers.submit(()->admission.execute(Duration.ofSeconds(2),null,budget->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals("queue-timeout",assertThrows(ClassroomModelAdmission.Rejected.class,()->admission.execute(Duration.ofSeconds(1),null,budget->{calls.incrementAndGet();return null;})).reason());
            assertEquals(0,calls.get());assertEquals(0,admission.snapshot().waiting());release.countDown();first.get(1,TimeUnit.SECONDS);
        }
    }
}
