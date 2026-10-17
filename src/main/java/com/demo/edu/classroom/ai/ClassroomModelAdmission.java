package com.demo.edu.classroom.ai;

import com.demo.edu.classroom.ClassroomCancellation;
import com.demo.edu.classroom.ClassroomException;
import com.demo.util.VoiceContext;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 仅包住一次真实模型调用的准入层。等待计入调用预算，队列有界；没有自动重试。
 * 调用方超时不等于底层SDK已退出：许可由实际工作线程finally释放，避免取消后突破并发上限。
 */
@Component
public final class ClassroomModelAdmission implements AutoCloseable {
    private final Semaphore permits;
    private final int concurrentLimit,waitingLimit;
    private final long maxWaitNanos;
    private final AtomicInteger waiting=new AtomicInteger();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Thread> running=ConcurrentHashMap.newKeySet();
    // 普通ThreadLocal不向异步子线程继承：只允许同一物理调用线程中的同步重入复用许可。
    private final ThreadLocal<Lease> currentLease=new ThreadLocal<>();
    private final ScheduledExecutorService nestedDeadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"classroom-model-deadlines");thread.setDaemon(true);return thread;});
    private record Lease(Lease parent,long deadline,ClassroomCancellation cancellation,CompletableFuture<?> result,AtomicBoolean abandoned,Thread thread){}
    public record Snapshot(int active,int waiting,int maxConcurrent,int maxWaiting,long maxWaitMillis,boolean closed){}
    public static final class Rejected extends ClassroomException {
        private final String reason;
        public Rejected(int status,String reason,String message){super(status,message);this.reason=reason;}
        public String reason(){return reason;}
    }
    public ClassroomModelAdmission(ClassroomModelAdmissionProperties properties) {
        if(properties.getMaxConcurrent()<1||properties.getMaxConcurrent()>32||properties.getMaxWaiting()<0||properties.getMaxWaiting()>256
                ||properties.getMaxWait()==null||properties.getMaxWait().isNegative()||properties.getMaxWait().isZero()
                ||properties.getMaxWait().compareTo(Duration.ofSeconds(60))>0)throw new IllegalArgumentException("课堂模型准入配置无效");
        concurrentLimit=properties.getMaxConcurrent();waitingLimit=properties.getMaxWaiting();maxWaitNanos=properties.getMaxWait().toNanos();
        permits=new Semaphore(concurrentLimit,true);
    }

    public <T>T execute(Duration budget,ClassroomCancellation cancellation,Function<Duration,T> work) {
        if(budget==null||budget.isZero()||budget.isNegative()||budget.compareTo(Duration.ofHours(1))>0||work==null)
            throw new ClassroomException(400,"模型调用预算无效");
        long deadline=System.nanoTime()+budget.toNanos();
        Lease inherited=currentLease.get();
        if(inherited!=null&&inherited.thread()==Thread.currentThread())return nested(inherited,deadline,cancellation,work);
        acquire(deadline,cancellation);
        CompletableFuture<T> result=new CompletableFuture<>();
        AtomicBoolean abandoned=new AtomicBoolean();
        String voiceUser=VoiceContext.getUserId();
        Thread worker=Thread.ofVirtual().name("classroom-model-call").unstarted(()->{
            try {
                if(voiceUser!=null)VoiceContext.setUserId(voiceUser);
                currentLease.set(new Lease(null,deadline,cancellation,result,abandoned,Thread.currentThread()));
                check(cancellation);
                if(abandoned.get())throw new CancellationException("课堂模型调用已取消");
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw timeout();
                result.complete(work.apply(Duration.ofNanos(remaining)));
            } catch(Throwable failure){result.completeExceptionally(failure);}
            finally { currentLease.remove();VoiceContext.clear();running.remove(Thread.currentThread());permits.release(); }
        });
        running.add(worker);
        try { worker.start(); }
        catch(RuntimeException|Error failure){running.remove(worker);permits.release();throw failure;}
        try {
            while(true) {
                check(cancellation);
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw timeout();
                try{return result.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(40)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException pending){/* 同一个调用等待，不重发模型请求。 */}
            }
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("课堂模型调用已取消");}
        catch(ExecutionException failed){
            Throwable cause=failed.getCause();
            if(cause instanceof RuntimeException runtime)throw runtime;
            if(cause instanceof Error error)throw error;
            throw new ClassroomException(502,"课堂模型调用未完成");
        } finally {
            if(!result.isDone()){abandoned.set(true);worker.interrupt();}
        }
    }
    private <T>T nested(Lease parent,long requestedDeadline,ClassroomCancellation cancellation,Function<Duration,T> work) {
        check(cancellation);checkLease(parent);
        Lease scope=new Lease(parent,Math.min(parent.deadline(),requestedDeadline),cancellation,parent.result(),parent.abandoned(),parent.thread());
        long remaining=scope.deadline()-System.nanoTime();if(remaining<=0)throw timeout();
        AtomicBoolean open=new AtomicBoolean(true);
        ScheduledFuture<?> timer=nestedDeadlines.scheduleWithFixedDelay(()->{
            RuntimeException failure=null;
            if(cancelled(scope))failure=new CancellationException("课堂模型调用已取消");
            else if(System.nanoTime()>=scope.deadline())failure=timeout();
            if(failure!=null&&open.compareAndSet(true,false)){scope.abandoned().set(true);scope.result().completeExceptionally(failure);scope.thread().interrupt();}
        },Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(40)),TimeUnit.MILLISECONDS.toNanos(40),TimeUnit.NANOSECONDS);
        currentLease.set(scope);
        try {T value=work.apply(Duration.ofNanos(remaining));checkLease(scope);return value;}
        finally {open.set(false);currentLease.set(parent);timer.cancel(false);}
    }
    private static boolean cancelled(Lease scope) {
        if(scope.abandoned().get())return true;
        for(Lease current=scope;current!=null;current=current.parent())if(current.cancellation()!=null&&current.cancellation().isCancelled())return true;
        return false;
    }
    private void checkLease(Lease scope){check(null);if(cancelled(scope))throw new CancellationException("课堂模型调用已取消");if(System.nanoTime()>=scope.deadline())throw timeout();}
    private void acquire(long deadline,ClassroomCancellation cancellation) {
        check(cancellation);
        try {
            if(permits.tryAcquire(0,TimeUnit.NANOSECONDS))return;
            int count=waiting.incrementAndGet();
            if(count>waitingLimit){waiting.decrementAndGet();throw new Rejected(429,"queue-full","课堂模型正在处理其他请求，请稍后手动重试");}
            long waitDeadline=Math.min(deadline,System.nanoTime()+maxWaitNanos);
            try {
                while(true){
                    check(cancellation);long remaining=waitDeadline-System.nanoTime();
                    if(remaining<=0)throw new Rejected(429,"queue-timeout","等待模型服务的时间较长，请稍后手动重试");
                    if(permits.tryAcquire(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(40)),TimeUnit.NANOSECONDS))return;
                }
            } finally {waiting.decrementAndGet();}
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("课堂模型调用已取消");}
    }
    private void check(ClassroomCancellation cancellation) {
        if(closed.get())throw new Rejected(503,"closed","课堂模型服务正在停止");
        if(Thread.currentThread().isInterrupted()||cancellation!=null&&cancellation.isCancelled())throw new CancellationException("课堂模型调用已取消");
    }
    private static Rejected timeout(){return new Rejected(504,"deadline","课堂模型响应超时，结果尚未确认，请稍后手动重试");}
    public Snapshot snapshot(){return new Snapshot(concurrentLimit-permits.availablePermits(),waiting.get(),concurrentLimit,waitingLimit,TimeUnit.NANOSECONDS.toMillis(maxWaitNanos),closed.get());}
    @Override @PreDestroy public void close(){if(closed.compareAndSet(false,true)){running.forEach(Thread::interrupt);nestedDeadlines.shutdownNow();}}
}
