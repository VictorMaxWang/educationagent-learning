package com.demo.edu.classroom.code;

import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 网站只管理有归属的任务；学生代码由独立 runner 执行，业务进程不编译、不执行源码。 */
@Service
public class CodeJobService {
    private static final Set<String> LANGUAGES = Set.of("javascript", "python", "cpp", "java");
    private static final Set<String> FINISHED = Set.of("completed", "error", "timeout", "cancelled");
    private static final int MAX_RESPONSE = 4_500_000;
    private static final long QUEUE_WAIT_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long RESULT_TTL_MILLIS = Duration.ofMinutes(15).toMillis();
    private final ObjectMapper json;
    private final ClassroomCourseService courses;
    private final URI endpoint;
    private final String token;
    private final boolean enabled;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), task -> { Thread t = new Thread(task, "classroom-code-queue"); t.setDaemon(true); return t; });
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private boolean closed;
    private static final class Job {
        final String owner, id;
        final long created;
        final ObjectNode request;
        final CompletableFuture<Void> settled = new CompletableFuture<>();
        Runnable task;
        boolean dispatched, cancelRequested;
        Long completedAt;
        ObjectNode view;
        Job(String owner, String id, ObjectNode request, ObjectNode view, long created) { this.owner=owner; this.id=id; this.request=request; this.view=view; this.created=created; }
    }

    @Autowired
    public CodeJobService(ObjectMapper json, ClassroomCourseService courses,
            @Value("${classroom.code.runner-url:http://127.0.0.1:8899}") String runner,
            @Value("${classroom.code.runner-token:}") String token,
            @Value("${classroom.code.enabled:false}") boolean enabled) {
        this(json, courses, runner, token, enabled, Clock.systemUTC());
    }

    /** 可控时钟只用于任务期限；HTTP、取消收尾仍使用真实超时，测试无需等待数分钟。 */
    CodeJobService(ObjectMapper json, ClassroomCourseService courses, String runner, String token, boolean enabled, Clock clock) {
        this.json=json; this.courses=courses; this.token=token; this.enabled=enabled;
        this.clock=Objects.requireNonNull(clock);
        this.endpoint=URI.create(runner);
        if (!"http".equals(endpoint.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())
                || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !Set.of("", "/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("代码运行服务须使用本机 HTTP 地址");
    }

    /** 每个浏览器最多两份未完成任务；全站串行排队，防止编译抢占课堂生成资源。 */
    public synchronized ObjectNode create(String owner, JsonNode body) {
        if (!enabled || token.length()<24 || closed) throw new ClassroomException(503, "代码课堂正在准备运行环境，请稍后再试");
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{43}")) throw bad("浏览器任务凭据无效，请刷新后重试");
        clean();
        if (body==null || !body.isObject() || !body.path("language").isTextual()
                || !LANGUAGES.contains(body.path("language").asText())) throw bad("请选择 JavaScript、Python、C++ 或 Java");
        String source=text(body,"code",20_000,false), stdin=text(body,"stdin",4_000,true);
        long pending=jobs.values().stream().filter(j->j.owner.equals(owner)&&!finished(j)).count();
        if (pending>=2) throw new ClassroomException(429,"你已有代码任务在等待，请先完成或停止它");
        String id=UUID.randomUUID().toString();
        long created=clock.millis();
        ObjectNode request=json.createObjectNode().put("id",id).put("language",body.path("language").asText()).put("code",source).put("stdin",stdin);
        ObjectNode view=json.createObjectNode().put("id",id).put("language",body.path("language").asText()).put("status","queued")
            .put("stdout","").put("stderr","").put("createdAt",Instant.ofEpochMilli(created).toString());
        view.putArray("trace"); view.putArray("diagnostics");
        Job job=new Job(owner,id,request,view,created); jobs.put(id,job);
        job.task=()->execute(job);
        try { executor.execute(job.task); }
        catch (RejectedExecutionException full) { jobs.remove(id); throw new ClassroomException(429,"代码课堂正在排队，请稍后再试"); }
        return view.deepCopy();
    }

    public synchronized ObjectNode get(String owner,String id) { clean(); return publicView(owned(owner,id)); }
    public ObjectNode cancel(String owner,String id) {
        Job job;
        boolean running;
        synchronized(this) {
            clean();
            job=owned(owner,id); if(finished(job)) return publicView(job);
            running=job.dispatched;
            job.cancelRequested=true;
            job.view.put("status","cancelled").put("reason","已停止本次运行"); markCompletedAt(job);
            if (!running && executor.remove(job.task)) job.settled.complete(null);
        }
        if(running) {
            stopRemote(id);
            // 容器正常停止会在 /run 响应里交回最后快照，给执行线程一次短暂的合并机会。
            try { job.settled.get(2,TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(ExecutionException | TimeoutException ignored) { /* 后续 GET 仍可取回稍迟返回的部分轨迹。 */ }
        }
        synchronized(this) { return publicView(job); }
    }

    private void execute(Job job) {
        try {
            while(true) {
                synchronized(this) { expireQueued(job,clock.millis()); if(finished(job) || closed) return; }
                if(!courses.hasActiveGeneration()) {
                    synchronized(this) {
                        // executor 队列中的时间也计入五分钟预算，派发前再核对一次。
                        expireQueued(job,clock.millis()); if(finished(job) || closed) return;
                        job.dispatched=true; job.view.put("status","running");
                    }
                    break;
                }
                Thread.sleep(100);
            }
            HttpRequest request=request("/run").timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(job.request.toString())).build();
            CompletableFuture<HttpResponse<byte[]>> exchange=http.sendAsync(request, info -> new LimitedBodySubscriber(MAX_RESPONSE));
            HttpResponse<byte[]> response;
            try { response=exchange.get(60,TimeUnit.SECONDS); }
            catch(InterruptedException | TimeoutException stopped) { exchange.cancel(true); throw stopped; }
            if(response.statusCode()!=200) { fail(job,"error",response.statusCode()==409?"运行服务正在整理上一项任务，请稍后再试":"代码运行环境暂不可用，请稍后重试"); return; }
            ObjectNode result=checkedResult(json.readTree(response.body()));
            synchronized(this) {
                if(jobs.get(job.id)!=job || job.settled.isDone()) return;
                // 白名单取值，不把 runner 的配置、宿主目录或内部任务字段转给浏览器。
                for(String key:List.of("status","stdout","stderr","diagnostics","trace","truncated","reason"))
                    if(result.has(key)) job.view.set(key,result.get(key));
                // 取消是不可逆的用户决定；即使完成响应迟到，也只补轨迹而不复活状态。
                if(job.cancelRequested) job.view.put("status","cancelled").put("reason","已停止本次运行").put("truncated",true);
                markCompletedAt(job);
            }
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(job,"cancelled","运行任务已停止"); }
        catch(TimeoutException timeout) { fail(job,"timeout","本次运行超过时间限制，已停止"); stopRemote(job.id); }
        catch(ExecutionException unavailable) {
            if(unavailable.getCause() instanceof java.net.http.HttpTimeoutException)
                fail(job,"timeout","本次运行超过时间限制，已停止");
            else fail(job,"error","运行环境暂时没有返回有效结果，请稍后重试");
            stopRemote(job.id);
        }
        catch(Exception unavailable) { fail(job,"error","运行环境暂时没有返回有效结果，请稍后重试"); stopRemote(job.id); }
        finally { job.settled.complete(null); }
    }
    private void stopRemote(String id) {
        // /run 与 DELETE 可能先后到达代理；短暂重试仅针对尚未登记的同一个任务。
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do {
            try {
                int status=http.send(request("/jobs/"+id).DELETE().timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
                if(status!=404 || System.nanoTime()>=deadline) return;
                synchronized(this) { Job job=jobs.get(id); if(job==null || job.settled.isDone()) return; }
                Thread.sleep(50);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            catch(Exception ignored) { return; }
        } while(System.nanoTime()<deadline);
    }
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(endpoint.resolve(path)).header("Content-Type","application/json").header("Authorization","Bearer "+token); }
    private synchronized void fail(Job job,String status,String reason) { if(!finished(job)) { job.view.put("status",status).put("reason",reason); markCompletedAt(job); } }
    private void markCompletedAt(Job job) { job.completedAt=clock.millis(); job.view.put("completedAt",Instant.ofEpochMilli(job.completedAt).toString()); }
    private Job owned(String owner,String id) { Job job=jobs.get(id); if(job==null||!job.owner.equals(owner)) throw new ClassroomException(404,"代码任务不存在或已过期"); return job; }
    /** 取消状态即时生效，最后快照可能稍后返回；客户端据此继续取尾部轨迹。 */
    private ObjectNode publicView(Job job) { return job.view.deepCopy().put("tracePending",job.cancelRequested&&!job.settled.isDone()); }
    private static boolean finished(Job job) { return FINISHED.contains(job.view.path("status").asText()); }
    /** 在状态锁内终止尚未派发的过期任务，不能影响正在运行或正在回收轨迹的任务。 */
    private void expireQueued(Job job,long now) {
        if(job.dispatched || finished(job) || now-job.created<QUEUE_WAIT_MILLIS) return;
        job.view.put("status","timeout").put("reason","等待运行的时间较长，请稍后重试"); markCompletedAt(job);
        executor.remove(job.task);
        job.settled.complete(null);
    }
    private void clean() {
        long now=clock.millis();
        for(Job job:jobs.values()) expireQueued(job,now);
        jobs.values().removeIf(j->finished(j)&&j.settled.isDone()&&j.completedAt!=null&&now-j.completedAt>=RESULT_TTL_MILLIS);
        int terminal=(int)jobs.values().stream().filter(j->finished(j)&&j.settled.isDone()).count();
        // 先创建的任务可能最后完成，容量淘汰不能再按插入顺序删除新结果。
        if(terminal>16) jobs.values().stream().filter(j->finished(j)&&j.settled.isDone())
            .sorted(Comparator.comparingLong((Job j)->j.completedAt).thenComparingLong(j->j.created))
            .limit(terminal-16L).map(j->j.id).toList().forEach(jobs::remove);
    }
    private static String text(JsonNode body,String key,int max,boolean optional) {
        JsonNode value=body.path(key); if(optional&&(value.isMissingNode()||value.isNull())) return "";
        if(!value.isTextual()||value.textValue().length()>max||(!optional&&value.textValue().isBlank())) throw bad(key.equals("code")?"代码须为1至20000字":"输入不能超过4000字");
        return value.textValue();
    }
    private static ClassroomException bad(String text) { return new ClassroomException(400,text); }
    @PreDestroy public void close() {
        List<String> running=new ArrayList<>();
        synchronized(this) {
            if(closed) return;
            closed=true;
            for(Job job:jobs.values()) if(!job.settled.isDone()) {
                job.cancelRequested=true;
                if(!finished(job)) { job.view.put("status","cancelled").put("reason","运行服务正在停止"); markCompletedAt(job); }
                if(job.dispatched) running.add(job.id); else job.settled.complete(null);
            }
        }
        executor.shutdownNow();
        // 网络清理必须在监视器外，否则执行线程无法完成状态合并，停机会互相等待。
        for(String id:running) stopRemote(id);
    }

    private ObjectNode checkedResult(JsonNode result) {
        if(result==null || !result.isObject() || !FINISHED.contains(result.path("status").asText())
                || !result.path("trace").isArray() || result.path("trace").size()>1000
                || !result.path("diagnostics").isArray() || result.path("diagnostics").size()>100)
            throw new IllegalArgumentException("invalid runner result");
        String stdout=output(result,"stdout"), stderr=output(result,"stderr");
        if(stdout.getBytes(StandardCharsets.UTF_8).length+stderr.getBytes(StandardCharsets.UTF_8).length>65536)
            throw new IllegalArgumentException("oversized runner output");
        ObjectNode clean=json.createObjectNode().put("status",result.path("status").asText()).put("stdout",stdout).put("stderr",stderr);
        var diagnostics=clean.putArray("diagnostics");
        for(JsonNode item:result.path("diagnostics")) {
            if(!item.isObject() || !item.path("line").isIntegralNumber() || !item.path("line").canConvertToInt() || item.path("line").intValue()<0 || item.path("line").intValue()>20000
                    || !item.path("message").isTextual() || item.path("message").textValue().length()>65536)
                throw new IllegalArgumentException("invalid runner diagnostic");
            diagnostics.addObject().put("line",item.path("line").intValue()).put("message",item.path("message").textValue());
        }
        var trace=clean.putArray("trace");
        for(JsonNode step:result.path("trace")) {
            if(!step.isObject() || !step.path("line").isIntegralNumber() || !step.path("line").canConvertToInt()
                    || step.path("line").intValue()<0 || step.path("line").intValue()>20000 || !step.path("frames").isArray()
                    || step.path("frames").size()>20 || !step.path("heap").isObject()
                    || !step.path("event").isTextual() || step.path("event").textValue().length()>100)
                throw new IllegalArgumentException("invalid runner step");
            ObjectNode target=trace.addObject().put("index",trace.size()-1).put("line",step.path("line").intValue())
                .put("event",step.path("event").textValue()).put("stdout",output(step,"stdout"));
            var frames=target.putArray("frames");
            for(JsonNode frame:step.path("frames")) {
                if(!frame.isObject() || !frame.path("name").isTextual() || frame.path("name").textValue().length()>1000 || !frame.path("locals").isObject())
                    throw new IllegalArgumentException("invalid runner frame");
                frames.addObject().put("name",frame.path("name").textValue()).set("locals",frame.path("locals"));
            }
            boundedValue(step.path("heap"),0);
            for(JsonNode frame:step.path("frames")) boundedValue(frame.path("locals"),0);
            target.set("heap",step.path("heap"));
        }
        if(result.has("truncated")) { if(!result.path("truncated").isBoolean()) throw new IllegalArgumentException("invalid truncation"); clean.set("truncated",result.path("truncated")); }
        if(result.has("reason")) {
            if(!result.path("reason").isTextual() || result.path("reason").textValue().length()>1000) throw new IllegalArgumentException("invalid reason");
            clean.set("reason",result.path("reason"));
        }
        return clean;
    }

    private static String output(JsonNode node,String key) {
        if(!node.path(key).isTextual() || node.path(key).textValue().length()>65536) throw new IllegalArgumentException("invalid runner output");
        return node.path(key).textValue();
    }
    private static void boundedValue(JsonNode value,int depth) {
        if(depth>20 || value.size()>2000 || (value.isTextual()&&value.textValue().length()>65536)) throw new IllegalArgumentException("oversized runner value");
        if(value.isContainerNode()) value.forEach(child->boundedValue(child,depth+1));
    }

    /** 按接收块限制响应，避免无 Content-Length 或慢速响应先把业务 JVM 的内存占满。 */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit=limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for(ByteBuffer chunk:chunks) {
                if(chunk.remaining()>limit-bytes.size()) { subscription.cancel(); result.completeExceptionally(new IOException("oversized runner result")); return; }
                byte[] block=new byte[chunk.remaining()]; chunk.get(block); bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
