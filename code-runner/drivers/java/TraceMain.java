import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Java 单文件课堂调试器。JDI 读取真实栈与对象，不改写学生源码，也不调用其方法。
 * 编译: javac --release 21 --add-modules jdk.jdi TraceMain.java
 */
public final class TraceMain {
    private final Path job;
    private final String mainClass;
    private final Map<String, Object> request;
    private final List<Object> trace = new ArrayList<>();
    private final List<Object> diagnostics = new ArrayList<>();
    private final int maxSteps;
    private final int maxOutput;
    private final int maxTrace;
    private final long deadline;
    private final Set<Long> steppingThreads = new HashSet<>();
    private final Set<String> breakpointLocations = new HashSet<>();
    private long traceBytes;
    private long checkpointAt;
    private int checkpointSteps;
    private int checkpointOutput;
    private String reason;
    private boolean runtimeError;
    private VirtualMachine vm;
    private Process process;
    private CapturedOutput stdout;
    private CapturedOutput stderr;

    private TraceMain(Path job, String mainClass, Map<String, Object> request) {
        this.job = job;
        this.mainClass = mainClass;
        this.request = request;
        Map<String, Object> limits = asMap(request.get("limits"));
        maxSteps = bounded(limits.get("steps"), 1000, 1, 1000);
        maxOutput = bounded(limits.get("outputBytes"), 65536, 1024, 65536);
        maxTrace = bounded(limits.get("traceBytes"), 4194304, 4096, 4194304);
        int seconds = bounded(limits.get("seconds"), 15, 1, 15);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    /** 参数只接受任务目录和单个类名；路径、主类均通过 JDI 参数传递，不执行 shell。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("用法: TraceMain <任务目录> <主类名>");
        }
        Path job = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String, Object> request = asMap(Json.parse(Files.readString(job.resolve("request.json"), StandardCharsets.UTF_8)));
        TraceMain driver = new TraceMain(job, args[1], request);
        Map<String, Object> result;
        try {
            result = driver.execute();
        } catch (Throwable error) {
            driver.reason = "driver_error";
            driver.diagnostics.add(map("line", 0, "message", safeMessage(error)));
            driver.stopTarget();
            result = driver.result();
        }
        // 父进程只能读到完整 JSON，避免超时恰逢写文件时把半份结果当成有效轨迹。
        writeAtomic(job.resolve("result.json"), result);
    }

    private Map<String, Object> execute() throws Exception {
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("main").setValue(mainClass);
        options.get("suspend").setValue("true");
        String classPath = job.resolve("classes").toString();
        // Windows 的 stdout 原生编码不一定跟随 file.encoding；管道统一为 UTF-8，生产 Linux 同样适用。
        options.get("options").setValue("-Xmx192m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp \"" + classPath + "\"");
        vm = connector.launch(options);
        process = vm.process();
        OutputBudget outputBudget = new OutputBudget(maxOutput);
        stdout = new CapturedOutput(process.getInputStream(), outputBudget);
        stderr = new CapturedOutput(process.getErrorStream(), outputBudget);
        stdout.start();
        stderr.start();
        process.getOutputStream().write(String.valueOf(request.getOrDefault("stdin", "")).getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        EventRequestManager events = vm.eventRequestManager();
        ClassPrepareRequest prepare = events.createClassPrepareRequest();
        for (String pattern : List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*")) prepare.addClassExclusionFilter(pattern);
        if (vm.canUseSourceNameFilters()) prepare.addSourceNameFilter(mainClass + ".java");
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
        MethodEntryRequest entry = events.createMethodEntryRequest();
        entry.addClassFilter(mainClass + "*");
        entry.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        entry.enable();
        MethodExitRequest exit = events.createMethodExitRequest();
        exit.addClassFilter(mainClass + "*");
        exit.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        exit.enable();
        // 只将未捕获异常计为程序失败；被学生程序正常处理的异常仍可继续运行。
        ExceptionRequest exceptions = events.createExceptionRequest(null, false, true);
        exceptions.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        exceptions.enable();

        boolean finished = false;
        try {
            while (!finished && reason == null) {
                if (System.nanoTime() >= deadline) { reason = "execution_timeout"; break; }
                if (stdout.truncated() || stderr.truncated()) { reason = "output_limit"; break; }
                EventSet set = vm.eventQueue().remove(50);
                if (set == null) { checkpointIfNeeded(); continue; }
                try {
                    for (Event event : set) {
                        if (reason != null) break;
                        if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                            finished = true;
                        } else if (event instanceof ClassPrepareEvent prepared) {
                            addSourceBreakpoints(prepared.referenceType());
                        } else if (event instanceof MethodEntryEvent method && isUser(method.location())) {
                            if (!steppingThreads.isEmpty() && !steppingThreads.contains(method.thread().uniqueID())) {
                                reason = "unsupported_concurrency";
                                diagnostics.add(map("line", method.location().lineNumber(), "message", "当前课堂仅支持单线程程序。"));
                                break;
                            }
                            steppingThreads.add(method.thread().uniqueID());
                            snapshot(method.thread(), method.location(), "call");
                        } else if (event instanceof MethodExitEvent method && isUser(method.location())) {
                            snapshot(method.thread(), method.location(), "return");
                        } else if (event instanceof BreakpointEvent step && isUser(step.location())) {
                            snapshot(step.thread(), step.location(), "line");
                        } else if (event instanceof ExceptionEvent error) {
                            runtimeError = true;
                            Location location = firstUserLocation(error.thread(), error.location());
                            diagnostics.add(map("line", Math.max(location.lineNumber(), 0), "message", exceptionMessage(error.exception())));
                            if (isUser(location)) snapshot(error.thread(), location, "exception");
                        }
                    }
                } finally {
                    // 终结事件也可能与挂起事件一起到达；仍需释放该事件集，避免 JVM 等待调试器。
                    if (reason == null) {
                        try { set.resume(); } catch (VMDisconnectedException ignored) { }
                    }
                }
            }
        } catch (VMDisconnectedException ignored) {
            finished = true;
        } finally {
            if (reason != null || !finished) stopTarget();
            else {
                // 学生程序已正常终结时只断开本次调试连接，让 JDWP 完成自身收尾，不主动设置退出码。
                try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
                // VMDeath 先于操作系统进程退出。单 CPU 容器下 250ms 不足以完成 JVM 收尾，
                // 不能把调试器为收尾发出的 137 误报成学生代码失败；宽限仍计入原执行 deadline。
                long remaining = Math.max(0, deadline - System.nanoTime());
                long grace = Math.min(TimeUnit.SECONDS.toNanos(2), remaining);
                boolean exited = !process.isAlive() || (grace > 0 && process.waitFor(grace, TimeUnit.NANOSECONDS));
                if (!exited) {
                    reason = "execution_timeout";
                    diagnostics.add(map("line", 0, "message", "程序结束后 JVM 未在剩余执行时间内退出，已停止并保留执行轨迹。"));
                    stopTarget();
                }
            }
            stdout.await();
            stderr.await();
        }
        if (reason == null && (stdout.truncated() || stderr.truncated())) reason = "output_limit";
        if (reason == null && (runtimeError || process.exitValue() != 0)) {
            reason = "runtime_error";
            if (diagnostics.isEmpty()) diagnostics.add(map("line", 0, "message", "程序退出码: " + process.exitValue()));
        }
        return result();
    }

    private void addSourceBreakpoints(ReferenceType type) {
        try {
            if (!type.sourceName().equals(mainClass + ".java")) return;
            // 单纯 STEP_LINE 不会在“同一行无限循环”再次暂停。给真实行表位置设断点，
            // 每次循环重入都保留一次快照；仍只观察原始字节码，不插桩、不改写源码。
            for (Location location : type.allLineLocations()) {
                String key = type.name() + ":" + location.method().name() + ":" + location.method().signature() + ":" + location.codeIndex();
                if (!breakpointLocations.add(key)) continue;
                BreakpointRequest breakpoint = vm.eventRequestManager().createBreakpointRequest(location);
                breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                breakpoint.enable();
            }
        } catch (AbsentInformationException ignored) {
            // 方法入口仍能提供可用位置；缺失变量信息会在快照中明确显示。
        }
    }

    private boolean isUser(Location location) {
        try { return location.sourceName().equals(mainClass + ".java"); }
        catch (AbsentInformationException ignored) { return false; }
    }

    private Location firstUserLocation(ThreadReference thread, Location fallback) {
        try { for (StackFrame frame : thread.frames()) if (isUser(frame.location())) return frame.location(); }
        catch (IncompatibleThreadStateException ignored) { }
        return fallback;
    }

    private String exceptionMessage(ObjectReference exception) {
        String message = exception.referenceType().name();
        try {
            Field detail = exception.referenceType().fieldByName("detailMessage");
            Value value = detail == null ? null : exception.getValue(detail);
            if (value instanceof StringReference text && !text.value().isEmpty()) message += ": " + text.value();
        } catch (RuntimeException ignored) { }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private void snapshot(ThreadReference thread, Location location, String event) throws InterruptedException, IOException {
        if (location.lineNumber() <= 0) return;
        if (!steppingThreads.isEmpty() && !steppingThreads.contains(thread.uniqueID())) {
            reason = "unsupported_concurrency";
            diagnostics.add(map("line", location.lineNumber(), "message", "当前课堂仅支持单线程程序。"));
            return;
        }
        steppingThreads.add(thread.uniqueID());
        if (trace.size() >= maxSteps) { reason = "step_limit"; return; }
        if (System.nanoTime() >= deadline) { reason = "execution_timeout"; return; }
        Map<String, Object> heap = new LinkedHashMap<>();
        List<Object> frames = new ArrayList<>();
        try {
            for (StackFrame frame : thread.frames()) {
                if (!isUser(frame.location())) continue;
                if (frames.size() >= 20) break;
                Map<String, Object> locals = new LinkedHashMap<>();
                try {
                    List<LocalVariable> variables = frame.visibleVariables();
                    for (LocalVariable variable : variables.subList(0, Math.min(80, variables.size()))) {
                        try { locals.put(variable.name(), value(frame.getValue(variable), heap, 0)); }
                        catch (RuntimeException failure) { locals.put(variable.name(), unavailable("变量不可读取")); }
                    }
                } catch (AbsentInformationException failure) {
                    locals.put("<调试信息>", unavailable("此方法没有局部变量调试信息"));
                }
                ObjectReference self = frame.thisObject();
                if (self != null) locals.put("this", value(self, heap, 0));
                frames.add(map("name", frame.location().declaringType().name() + "." + frame.location().method().name(), "locals", locals));
            }
        } catch (IncompatibleThreadStateException failure) {
            frames.add(map("name", "<不可读取>", "locals", map("<栈帧>", unavailable("线程未暂停"))));
        }
        // stdout 与调试事件使用不同管道，短暂让出 CPU 以收齐本次输出。
        Thread.sleep(1);
        Map<String, Object> item = map("index", trace.size(), "line", Math.max(location.lineNumber(), 0),
            "event", event, "frames", frames, "heap", heap, "stdout", stdout.text());
        int bytes = Json.stringify(item).getBytes(StandardCharsets.UTF_8).length;
        if (traceBytes + bytes > maxTrace) { reason = "trace_limit"; return; }
        traceBytes += bytes;
        trace.add(item);
        checkpointIfNeeded();
    }

    private void checkpointIfNeeded() throws IOException {
        int outputLength = (stdout == null ? 0 : stdout.size()) + (stderr == null ? 0 : stderr.size());
        long now = System.nanoTime();
        boolean changed = trace.size() != checkpointSteps || outputLength != checkpointOutput;
        if (trace.isEmpty() || !changed) return;
        if (checkpointAt == 0 || trace.size() - checkpointSteps >= 25 || now - checkpointAt >= TimeUnit.MILLISECONDS.toNanos(200)) {
            // 宿主取消会终止本调试器，因此只依赖已落盘的完整快照，不依赖信号回调。
            writeAtomic(job.resolve("checkpoint.json"), result());
            checkpointAt = now;
            checkpointSteps = trace.size();
            checkpointOutput = outputLength;
        }
    }

    private static void writeAtomic(Path destination, Object data) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, Json.stringify(data), StandardCharsets.UTF_8);
        try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    private Object value(Value value, Map<String, Object> heap, int depth) {
        if (value == null) return null;
        if (value instanceof BooleanValue v) return v.value();
        if (value instanceof CharValue v) return String.valueOf(v.value());
        if (value instanceof ByteValue v) return v.value();
        if (value instanceof ShortValue v) return v.value();
        if (value instanceof IntegerValue v) return v.value();
        if (value instanceof LongValue v) return v.value();
        if (value instanceof FloatValue v) return Float.isFinite(v.value()) ? v.value() : unavailable(v.toString());
        if (value instanceof DoubleValue v) return Double.isFinite(v.value()) ? v.value() : unavailable(v.toString());
        if (value instanceof StringReference text) {
            String actual = text.value();
            return actual.length() <= 500 ? actual : actual.substring(0, 500) + "…";
        }
        if (!(value instanceof ObjectReference reference)) return unavailable("此值类型尚不支持");
        String identity = "java:" + reference.uniqueID();
        if (heap.containsKey(identity)) return map("$ref", identity);
        Map<String, Object> details = map("type", reference.referenceType().name());
        heap.put(identity, details);
        if (depth >= 3 || heap.size() > 128) {
            details.put("$unavailable", depth >= 3 ? "最大展开深度" : "最大对象数量");
            return map("$ref", identity);
        }
        try {
            if (reference instanceof ArrayReference array) {
                int count = Math.min(50, array.length());
                List<Object> items = new ArrayList<>();
                for (Value child : array.getValues(0, count)) items.add(value(child, heap, depth + 1));
                details.put("items", items);
                details.put("length", array.length());
                details.put("truncated", array.length() > count);
            } else {
                Map<String, Object> properties = new LinkedHashMap<>();
                List<Field> fields = reference.referenceType().allFields().stream().filter(field -> !field.isStatic() && !field.isSynthetic()).toList();
                for (Field field : fields.subList(0, Math.min(40, fields.size()))) {
                    String name = properties.containsKey(field.name()) ? field.declaringType().name() + "." + field.name() : field.name();
                    try { properties.put(name, value(reference.getValue(field), heap, depth + 1)); }
                    catch (RuntimeException ignored) { properties.put(name, unavailable("字段不可读取")); }
                }
                details.put("properties", properties);
                details.put("truncated", fields.size() > 40);
            }
        } catch (RuntimeException failure) { details.put("$unavailable", "对象已不可读取"); }
        return map("$ref", identity);
    }

    private void stopTarget() {
        // 只终止本调试器启动的 JVM；不枚举、不结束容器或宿主的其他 Java 进程。
        if (vm != null) {
            try { vm.exit(137); } catch (RuntimeException ignored) { }
        }
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try { process.waitFor(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private Map<String, Object> result() {
        boolean truncated = List.of("step_limit", "trace_limit", "output_limit", "execution_timeout").contains(reason == null ? "" : reason);
        String status = reason == null ? "completed" : reason.equals("execution_timeout") ? "timeout" : "error";
        Map<String, Object> result = map("status", status, "stdout", stdout == null ? "" : stdout.text(),
            "stderr", stderr == null ? "" : stderr.text(), "diagnostics", diagnostics, "trace", trace, "truncated", truncated);
        if (reason != null) result.put("reason", reason);
        return result;
    }

    private static Map<String, Object> unavailable(String message) { return map("$unavailable", message); }
    private static int bounded(Object input, int fallback, int min, int max) {
        if (!(input instanceof Number number)) return fallback;
        return Math.max(min, Math.min(max, number.intValue()));
    }
    private static String safeMessage(Throwable error) {
        String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return text.substring(0, Math.min(2000, text.length()));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    /** 两条输出流共用字节预算，避免各自限制 64KiB 后实际泄漏为 128KiB。 */
    private static final class OutputBudget {
        private final int limit;
        private int used;
        private volatile boolean truncated;
        private OutputBudget(int limit) { this.limit = limit; }
        private synchronized int allocate(int count) {
            int accepted = Math.min(count, limit - used);
            used += accepted;
            if (accepted < count) truncated = true;
            return accepted;
        }
    }

    /** 限制管道内存；即使学生持续输出也继续排空管道，避免读写两端互锁。 */
    private static final class CapturedOutput extends Thread {
        private final InputStream stream;
        private final OutputBudget budget;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private CapturedOutput(InputStream stream, OutputBudget budget) {
            this.stream = stream;
            this.budget = budget;
            setDaemon(true);
        }
        @Override public void run() {
            byte[] chunk = new byte[4096];
            try {
                int count;
                while ((count = stream.read(chunk)) >= 0) {
                    int accepted = budget.allocate(count);
                    synchronized (data) {
                        data.write(chunk, 0, accepted);
                    }
                }
            } catch (IOException ignored) { }
        }
        private boolean truncated() { return budget.truncated; }
        private int size() { synchronized (data) { return data.size(); } }
        private String text() {
            synchronized (data) {
                // 字节预算可能截在中文字符中间；忽略未完成的码点，避免替换符反而扩大字节数。
                try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(data.toByteArray())).toString(); }
                catch (java.nio.charset.CharacterCodingException impossible) { return ""; }
            }
        }
        private void await() throws InterruptedException { join(500); }
    }

    /** 无外部依赖的 JSON 编解码器；只处理 runner 契约所需的 JSON 类型。 */
    private static final class Json {
        private final String text;
        private int index;
        private Json(String text) { this.text = text; }
        private static Object parse(String text) {
            Json parser = new Json(text);
            Object result = parser.read(0);
            parser.space();
            if (parser.index != text.length()) throw new IllegalArgumentException("JSON 尾部有无效内容");
            return result;
        }
        private void space() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
        private char take() { if (index >= text.length()) throw new IllegalArgumentException("JSON 意外结束"); return text.charAt(index++); }
        private Object read(int depth) {
            if (depth > 32) throw new IllegalArgumentException("JSON 嵌套过深");
            space();
            char next = take();
            if (next == '"') return string();
            if (next == '{') {
                Map<String, Object> result = new LinkedHashMap<>();
                space();
                if (index < text.length() && text.charAt(index) == '}') { index++; return result; }
                while (true) {
                    space();
                    if (take() != '"') throw new IllegalArgumentException("JSON 对象缺少键");
                    String key = string();
                    space();
                    if (take() != ':') throw new IllegalArgumentException("JSON 对象缺少冒号");
                    result.put(key, read(depth + 1));
                    space();
                    char separator = take();
                    if (separator == '}') return result;
                    if (separator != ',') throw new IllegalArgumentException("JSON 对象分隔符错误");
                }
            }
            if (next == '[') {
                List<Object> result = new ArrayList<>();
                space();
                if (index < text.length() && text.charAt(index) == ']') { index++; return result; }
                while (true) {
                    result.add(read(depth + 1));
                    space();
                    char separator = take();
                    if (separator == ']') return result;
                    if (separator != ',') throw new IllegalArgumentException("JSON 数组分隔符错误");
                }
            }
            for (String literal : List.of("true", "false", "null")) {
                if (next == literal.charAt(0) && text.startsWith(literal, index - 1)) {
                    index += literal.length() - 1;
                    return literal.equals("null") ? null : literal.equals("true");
                }
            }
            int start = index - 1;
            while (index < text.length() && "0123456789.eE+-".indexOf(text.charAt(index)) >= 0) index++;
            String number = text.substring(start, index);
            try { return number.contains(".") || number.contains("e") || number.contains("E") ? Double.valueOf(number) : Long.valueOf(number); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("JSON 数字错误"); }
        }
        private String string() {
            StringBuilder result = new StringBuilder();
            while (true) {
                char next = take();
                if (next == '"') return result.toString();
                if (next == '\\') {
                    char escape = take();
                    switch (escape) {
                        case '"', '\\', '/' -> result.append(escape);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> { int point = Integer.parseInt(text.substring(index, index + 4), 16); index += 4; result.append((char) point); }
                        default -> throw new IllegalArgumentException("JSON 转义错误");
                    }
                } else {
                    if (next < 32) throw new IllegalArgumentException("JSON 字符串包含控制字符");
                    result.append(next);
                }
            }
        }
        private static String stringify(Object value) { StringBuilder result = new StringBuilder(); write(value, result); return result.toString(); }
        private static void write(Object value, StringBuilder result) {
            if (value == null) result.append("null");
            else if (value instanceof Boolean || value instanceof Number) result.append(value);
            else if (value instanceof Map<?, ?> object) {
                result.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : object.entrySet()) {
                    if (!first) result.append(','); first = false;
                    quote(String.valueOf(entry.getKey()), result); result.append(':'); write(entry.getValue(), result);
                }
                result.append('}');
            } else if (value instanceof Iterable<?> array) {
                result.append('[');
                boolean first = true;
                for (Object item : array) { if (!first) result.append(','); first = false; write(item, result); }
                result.append(']');
            } else quote(String.valueOf(value), result);
        }
        private static void quote(String value, StringBuilder result) {
            result.append('"');
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                switch (character) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    case '\b' -> result.append("\\b");
                    case '\f' -> result.append("\\f");
                    default -> { if (character < 32) result.append(String.format("\\u%04x", (int) character)); else result.append(character); }
                }
            }
            result.append('"');
        }
    }
}
