package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** educationagent Node 生成器的 JSONL 桥，每个任务拥有独立进程和总超时。 */
@Component
public class ClassroomNodeEngine implements ClassroomEngine {
    private static final int MAX_LINE = 2_000_000;
    private final ObjectMapper json;
    private final ClassroomTutorModel tutor;
    private final String node;
    private final Path worker;
    private final long timeoutMs;

    public ClassroomNodeEngine(ObjectMapper json, ClassroomTutorModel tutor,
            @Value("${classroom.engine.node:node}") String node,
            @Value("${classroom.engine.path:course-engine/dist/worker.js}") String worker,
            @Value("${classroom.engine.timeout-ms:300000}") long timeoutMs) {
        this.json=json; this.tutor=tutor; this.node=node; this.worker=Path.of(worker).toAbsolutePath().normalize();
        this.timeoutMs=Math.max(100, timeoutMs);
    }

    @Override public JsonNode outline(ObjectNode input, ClassroomCancellation token) { return run("outline",input,null,token); }
    @Override public ObjectNode scene(ObjectNode input, JsonNode outline, ClassroomCancellation token) {
        JsonNode value=run("scene",input,outline,token);
        if (!value.isObject()) throw new ClassroomException(502,"生成器返回的页面格式无效");
        return (ObjectNode)value;
    }

    JsonNode run(String operation, ObjectNode course, JsonNode outline, ClassroomCancellation token) {
        token.check();
        if (!Files.isRegularFile(worker)) throw new ClassroomException(503,"课堂生成器尚未构建，请先运行 course-engine 的构建命令");
        Process process;
        try { process=new ProcessBuilder(node,worker.toString()).directory(Path.of("").toAbsolutePath().toFile()).start(); }
        catch(IOException e) { throw new ClassroomException(503,"无法启动课堂生成器，请检查 Node 和生成器路径"); }
        ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
        Future<JsonNode> exchange=null;
        try {
            token.attach(process);
            // 持续排空 stderr 防止子进程卡在管道；日志可能含模型正文，不返回给浏览器。
            io.submit(() -> { try(InputStream errors=process.getErrorStream()) { errors.transferTo(OutputStream.nullOutputStream()); } catch(IOException ignored) {} });
            String jobId=UUID.randomUUID().toString();
            ObjectNode job=json.createObjectNode().put("type","job").put("id",jobId).put("operation",operation);
            ObjectNode input=json.createObjectNode();
            for(String field:List.of("id","topic","grade","subject","domain","sourceText","sourceName","generationContext","outlines","mediaEnabled"))
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
            input.put("courseId",course.path("id").asText()); job.set("input",input);
            if(outline!=null) job.set("outline",outline.deepCopy());
            exchange=io.submit(() -> communicate(process,job,jobId,token));
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
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
        } finally {
            if(exchange!=null) exchange.cancel(true);
            process.destroyForcibly(); token.detach(process); io.shutdownNow();
        }
    }

    private JsonNode communicate(Process process,ObjectNode job,String jobId,ClassroomCancellation token) throws IOException {
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
                        ObjectNode reply=json.createObjectNode().put("type","llm.result").put("id",id);
                        try {
                            if(event.path("images").isArray() && !event.path("images").isEmpty()) throw new ClassroomException(502,"课堂首版不支持图片模型请求");
                            String text=tutor.complete(event.path("messages")); token.check(); reply.put("text",text);
                        } catch(CancellationException cancel) { throw cancel; }
                        catch(Exception unavailable) { reply.put("error","模型请求未完成，请重试"); }
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

    @Override public Map<String,Object> health() {
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
