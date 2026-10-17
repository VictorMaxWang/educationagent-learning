package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** educationagent Node 生成器的 JSONL 桥，每个任务拥有独立进程和总超时。 */
@Component
public class ClassroomNodeEngine implements ClassroomEngine {
    private static final Logger log = LoggerFactory.getLogger(ClassroomNodeEngine.class);
    private static final int MAX_LINE = 2_000_000;
    private static final long HEALTH_CACHE_MS = 30_000;
    private static final long MODEL_MAX_MS = 180_000;
    /** 生成器可以声明的模型请求用途；不声明则走生成模型。用途是 Java 侧路由到复核模型的唯一凭据。 */
    private static final Set<String> PURPOSES = Set.of("quiz-review", "quiz-regenerate");
    private final ObjectMapper json;
    private final ClassroomTutorModel tutor;
    private final String node;
    private final Path worker;
    private final long timeoutMs;
    private final LongSupplier healthTime;
    private final Clock clock;
    private final Object healthMonitor = new Object();
    private final AtomicLong healthGeneration = new AtomicLong();
    private volatile HealthSnapshot healthSnapshot;

    @Autowired
    public ClassroomNodeEngine(ObjectMapper json, ClassroomTutorModel tutor,
            @Value("${classroom.engine.node:node}") String node,
            @Value("${classroom.engine.path:course-engine/dist/worker.js}") String worker,
            // 总期限与生产一致；worker在这份余量内决定是否继续复核/重出，不能逐次重置420秒。
            @Value("${classroom.engine.timeout-ms:420000}") long timeoutMs) {
        this(json, tutor, node, worker, timeoutMs, System::nanoTime, Clock.systemUTC());
    }

    ClassroomNodeEngine(ObjectMapper json, ClassroomTutorModel tutor, String node, String worker,
                        long timeoutMs, LongSupplier healthTime, Clock clock) {
        this.json=json; this.tutor=tutor; this.node=node; this.worker=Path.of(worker).toAbsolutePath().normalize();
        this.timeoutMs=Math.max(100, timeoutMs);
        this.healthTime=healthTime; this.clock=clock;
    }

    @Override public JsonNode outline(ObjectNode input, ClassroomCancellation token) { return run("outline",input,null,token); }
    @Override public ObjectNode scene(ObjectNode input, JsonNode outline, ClassroomCancellation token) {
        JsonNode value=run("scene",input,outline,token);
        if (!value.isObject()) { invalidateHealth(); throw new ClassroomException(502,"生成器返回的页面格式无效"); }
        return (ObjectNode)value;
    }

    JsonNode run(String operation, ObjectNode course, JsonNode outline, ClassroomCancellation token) {
        ObjectNode input=json.createObjectNode();
        for(String field:List.of("id","topic","grade","subject","direction","domain","sourceText","sourceName","generationContext","outlines","mediaEnabled","quizReview"))
            if(course.has(field)) input.set(field,course.get(field).deepCopy());
        // 后一页只需前页讲述，不反复传输画布、互动代码和内联字体。
        var previousScenes = input.putArray("scenes");
        for (JsonNode scene : course.path("scenes")) {
            var previous = previousScenes.addObject().put("id", scene.path("id").asText())
                    .put("order", scene.path("order").asInt());
            var speech = previous.putArray("actions");
            for (JsonNode action : scene.path("actions")) {
                if ("speech".equals(action.path("type").asText())) speech.add(action.deepCopy());
            }
        }
        input.put("courseId",course.path("id").asText());
        return runJob(operation,input,outline,token);
    }

    /**
     * 只读补审已有课程：input 由调用方给出（只含 courseId 与 questions）。
     * 不携带参考材料、不生成内容，也不写回任何数据。
     */
    @Override public JsonNode review(ObjectNode input, ClassroomCancellation token) { return runJob("review",input,null,token); }

    /** 只出修复方案，不落库：落库由 Java 在事务里同时改课件文档与题库行。 */
    @Override public JsonNode repair(ObjectNode input, ClassroomCancellation token) { return runJob("repair",input,null,token); }

    private JsonNode runJob(String operation, ObjectNode input, JsonNode outline, ClassroomCancellation token) {
        token.check();
        if (!Files.isRegularFile(worker)) { invalidateHealth(); throw new ClassroomException(503,"课堂生成器尚未构建，请先运行 course-engine 的构建命令"); }
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Process process;
        try { process=new ProcessBuilder(node,worker.toString()).directory(Path.of("").toAbsolutePath().toFile()).start(); }
        catch(IOException e) { invalidateHealth(); throw new ClassroomException(503,"无法启动课堂生成器，请检查 Node 和生成器路径"); }
        ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
        Future<JsonNode> exchange=null;
        try {
            token.attach(process);
            // 持续排空 stderr 防止子进程卡在管道；日志可能含模型正文，不返回给浏览器。
            io.submit(() -> { try(InputStream errors=process.getErrorStream()) { errors.transferTo(OutputStream.nullOutputStream()); } catch(IOException ignored) {} });
            String jobId=UUID.randomUUID().toString();
            ObjectNode job=json.createObjectNode().put("type","job").put("id",jobId).put("operation",operation);
            job.put("remainingMs", remainingMillis(deadline));
            job.set("input",input);
            if(outline!=null) job.set("outline",outline.deepCopy());
            exchange=io.submit(() -> communicate(process,job,jobId,token,deadline));
            while(true) {
                token.check();
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) throw new ClassroomException(504,"本页生成超时，请重试；已生成页面会保留");
                try { return exchange.get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining)+1,100),TimeUnit.MILLISECONDS); }
                catch(TimeoutException ignored) { /* 短轮询让取消不等待完整模型超时。 */ }
                catch(ExecutionException e) {
                    token.check();
                    if(e.getCause() instanceof ClassroomException classroom) throw classroom;
                    if(e.getCause() instanceof CancellationException cancelled) throw cancelled;
                    throw new ClassroomException(502,"课堂生成器通信失败，请重试");
                }
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("课堂生成已中断");
        } catch (ClassroomException failed) {
            // 取消不代表依赖损坏；启动、协议、意外退出或总超时才使下一次健康查询重新实测。
            invalidateHealth(); throw failed;
        } finally {
            if(exchange!=null) exchange.cancel(true);
            process.destroyForcibly(); token.detach(process); io.shutdownNow();
        }
    }

    private JsonNode communicate(Process process,ObjectNode job,String jobId,ClassroomCancellation token,long deadline) throws IOException {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8))) {
            write(writer,job);
            Set<String> calls=new HashSet<>(); int lines=0;
            String line;
            while((line=readLine(reader))!=null) {
                token.check();
                if(++lines>100) throw new ClassroomException(502,"生成器协议消息过多");
                JsonNode event;
                try { event=json.readTree(line); } catch(Exception e) { throw new ClassroomException(502,"生成器返回了非法 JSON"); }
                if(event==null || !event.isObject()) throw new ClassroomException(502,"生成器消息格式无效");
                String id=event.path("id").asText("");
                switch(event.path("type").asText()) {
                    case "llm.request" -> {
                        if(id.isBlank() || id.length()>100 || !calls.add(id) || calls.size()>20) throw new ClassroomException(502,"生成器模型请求标识无效或重复");
                        // 用途由生成器声明、Java 校验后路由；未知用途失败关闭，不放行到默认模型。
                        String purpose=event.path("purpose").asText("");
                        if(!purpose.isBlank() && !PURPOSES.contains(purpose)) throw new ClassroomException(502,"生成器模型请求用途无效");
                        JsonNode requestedTimeout=event.get("timeoutMs");
                        if(requestedTimeout!=null && (!requestedTimeout.isIntegralNumber() || !requestedTimeout.canConvertToLong()
                                || requestedTimeout.asLong()<=0 || requestedTimeout.asLong()>MODEL_MAX_MS))
                            throw new ClassroomException(502,"生成器模型请求预算无效");
                        ObjectNode reply=json.createObjectNode().put("type","llm.result").put("id",id);
                        try {
                            if(event.path("images").isArray() && !event.path("images").isEmpty()) throw new ClassroomException(502,"课堂首版不支持图片模型请求");
                            long remaining=remainingMillis(deadline);
                            if(remaining<=0) throw new ClassroomException(504,"本页生成预算已耗尽");
                            // 新worker明确给出单次预算；Tutor在实际网络入口处理许可和截止时间，
                            // 这里不能再拿一次准入许可。旧协议保留原重载，仍受外层总期限约束。
                            String text=requestedTimeout!=null
                                    ? tutor.complete(event.path("messages"),purpose,Math.min(remaining,requestedTimeout.asLong()),token)
                                    : purpose.isBlank() ? tutor.complete(event.path("messages"))
                                    : tutor.complete(event.path("messages"),purpose);
                            token.check(); reply.put("text",text);
                        } catch(CancellationException cancel) { throw cancel; }
                        catch(Exception unavailable) {
                            // 异常正文可能包含供应商响应或凭据；仅记录用途与异常类型，不反射原始消息。
                            log.warn("课堂模型请求未完成 purpose={} 类型={}",
                                    purpose.isBlank() ? "(生成)" : purpose,
                                    unavailable.getClass().getSimpleName());
                            reply.put("error","模型请求未完成，请重试");
                        }
                        reply.put("remainingMs", remainingMillis(deadline));
                        write(writer,reply);
                    }
                    case "result" -> {
                        if(!jobId.equals(id) || !event.has("value") || event.get("value").isNull()) throw new ClassroomException(502,"生成器结果与当前任务不匹配");
                        return event.get("value");
                    }
                    case "error" -> {
                        if(!jobId.equals(id)) throw new ClassroomException(502,"生成器错误与当前任务不匹配");
                        // Worker 细节可能夹带输入和路径，前台只显示稳定错误。
                        throw new ClassroomException(502,"课堂生成器未能完成本页，请检查模型响应后重试");
                    }
                    default -> throw new ClassroomException(502,"生成器返回了不支持的协议消息");
                }
            }
            throw new ClassroomException(502,"课堂生成器提前退出，未返回结果");
        }
    }

    private void write(BufferedWriter writer,JsonNode value) throws IOException { writer.write(json.writeValueAsString(value)); writer.newLine(); writer.flush(); }
    private static String readLine(Reader reader) throws IOException {
        StringBuilder line=new StringBuilder(); int c;
        while((c=reader.read())!=-1) {
            if(c=='\n') return line.toString();
            if(c!='\r') line.append((char)c);
            if(line.length()>MAX_LINE) throw new ClassroomException(502,"生成器单条响应过大");
        }
        return line.isEmpty()?null:line.toString();
    }

    private static long remainingMillis(long deadline) { return Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())); }

    private void invalidateHealth() { healthGeneration.incrementAndGet(); healthSnapshot=null; }

    private boolean fresh(HealthSnapshot snapshot,long now) {
        long age=snapshot==null ? Long.MAX_VALUE : now-snapshot.sampledAt();
        return snapshot!=null && snapshot.generation()==healthGeneration.get() && age>=0
                && age<TimeUnit.MILLISECONDS.toNanos(HEALTH_CACHE_MS);
    }

    @Override public Map<String,Object> health() {
        long now=healthTime.getAsLong();
        HealthSnapshot snapshot=healthSnapshot;
        if(fresh(snapshot,now)) return healthResult(snapshot,true,now);
        synchronized(healthMonitor) {
            now=healthTime.getAsLong(); snapshot=healthSnapshot;
            if(fresh(snapshot,now)) return healthResult(snapshot,true,now);
            long generation=healthGeneration.get();
            Map<String,Object> measured=probeHealth();
            snapshot=new HealthSnapshot(measured,healthTime.getAsLong(),generation,clock.instant().toString());
            // 任务失败可能与探测并发；旧一轮不能把失败刚清除的健康缓存重新发布。
            if(generation==healthGeneration.get()) healthSnapshot=snapshot;
            return healthResult(snapshot,false,snapshot.sampledAt());
        }
    }

    private Map<String,Object> healthResult(HealthSnapshot snapshot,boolean cached,long now) {
        Map<String,Object> result=new LinkedHashMap<>(snapshot.measured());
        result.put("checkedAt",snapshot.checkedAt()); result.put("cached",cached);
        result.put("cacheAgeMs",Math.max(0,TimeUnit.NANOSECONDS.toMillis(now-snapshot.sampledAt())));
        result.put("cacheTtlMs",HEALTH_CACHE_MS);
        return Collections.unmodifiableMap(result);
    }

    private record HealthSnapshot(Map<String,Object> measured,long sampledAt,long generation,String checkedAt) {}

    /** 首次、失效或超过30秒才运行真实Node与依赖探测；缓存命中不会创建子进程。 */
    private Map<String,Object> probeHealth() {
        boolean available=false; Process probe=null;
        try { probe=new ProcessBuilder(node,"--version").start(); available=probe.waitFor(2,TimeUnit.SECONDS)&&probe.exitValue()==0; }
        catch(Exception ignored) { if(ignored instanceof InterruptedException) Thread.currentThread().interrupt(); }
        finally { if(probe!=null && probe.isAlive()) probe.destroyForcibly(); }
        boolean built=Files.isRegularFile(worker);
        boolean dependenciesReady = false;
        if (available && built) {
            Process imports = null;
            try {
                imports = new ProcessBuilder(node, worker.toString(), "--check")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (imports.waitFor(5, TimeUnit.SECONDS) && imports.exitValue() == 0) {
                    JsonNode report = json.readTree(imports.getInputStream());
                    dependenciesReady = "ready".equals(report.path("type").asText())
                            && "educationagent".equals(report.path("engine").asText());
                }
            } catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally { if (imports != null && imports.isAlive()) imports.destroyForcibly(); }
        }
        return Map.of("nodeAvailable",available,"workerBuilt",built,"dependenciesReady",dependenciesReady,
                "ready",available&&built&&dependenciesReady,"timeoutMs",timeoutMs);
    }
}
