package com.demo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 代码运行接口 — {@code POST /api/code/run}。
 * <p>
 * <ul>
 *   <li><b>python</b>：ProcessBuilder 子进程执行 {@code python -c &lt;code&gt;}</li>
 *   <li><b>c</b>：写临时 .c → {@code gcc} 编译 → 运行可执行文件（需 gcc 在 PATH，如 MinGW-w64）</li>
 *   <li><b>html / javascript</b>：前端 iframe 渲染，本接口不执行</li>
 * </ul>
 * 统一带超时、输出截断、后台读流防管道阻塞、临时文件清理。
 */
@Slf4j
@RestController
@RequestMapping("/api/code")
public class CodeController {

    private static final int MAX_CODE_LEN = 8000;
    private static final int MAX_OUTPUT = 20000;
    private static final long RUN_TIMEOUT_SECONDS = 10;
    private static final long COMPILE_TIMEOUT_SECONDS = 15;

    /** gcc 完整路径（application.properties 的 code.gcc-path），留空则回退系统 PATH 的 gcc */
    @Value("${code.gcc-path:}")
    private String gccPath;

    /** Python 完整路径（application.properties 的 code.python-path），留空则回退系统 PATH 的 python */
    @Value("${code.python-path:}")
    private String pythonPath;

    /** body: {language:"python"|"c"|"html"|"javascript", code:"..."} */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            out.put("code", 400);
            out.put("msg", "请求体为空");
            return out;
        }
        String language = str(body.get("language"));
        String code = str(body.get("code"));
        if (code.isBlank()) {
            out.put("code", 400);
            out.put("msg", "代码不能为空");
            return out;
        }
        if (code.length() > MAX_CODE_LEN) {
            out.put("code", 400);
            out.put("msg", "代码过长（最多 " + MAX_CODE_LEN + " 字符）");
            return out;
        }

        long start = System.currentTimeMillis();
        switch (language) {
            case "python":
                return runPython(code, start);
            case "c":
                return runC(code, start);
            default:
                // html / javascript：前端 iframe 直接渲染，无需后端
                out.put("code", 200);
                out.put("output", "");
                out.put("msg", "前端渲染");
                out.put("durationMs", elapsed(start));
                return out;
        }
    }

    // ======================== Python ========================

    private Map<String, Object> runPython(String code, long start) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path dir = null;
        try {
            // 写成临时 .py 文件再执行，绕开 Windows ProcessBuilder 命令行对双引号的错误转义
            // （python -c <code> 时参数内嵌 " 会被剥离，导致含引号的代码报语法错误）
            dir = Files.createTempDirectory("coder_py_");
            Path src = dir.resolve("main.py");
            Files.writeString(src, code, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(resolvePython(), src.toString());
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true); // stderr 并入 stdout，统一展示
            pb.environment().put("PYTHONIOENCODING", "utf-8"); // 强制 UTF-8 输出，避免中文乱码
            Process p = pb.start();
            String output = drainAsync(p).get(RUN_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);

            boolean finished = p.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                out.put("code", 500);
                out.put("msg", "执行超时（" + RUN_TIMEOUT_SECONDS + "s）");
                out.put("durationMs", elapsed(start));
                return out;
            }
            out.put("code", 200);
            out.put("output", truncate(output));
            out.put("exitCode", p.exitValue());
            out.put("durationMs", elapsed(start));
            return out;
        } catch (Exception e) {
            // python 不存在时 ProcessBuilder.start() 抛 IOException，给友好提示
            if (e instanceof IOException || (e.getCause() instanceof IOException)) {
                out.put("code", 500);
                out.put("msg", "未找到 python 解释器。请配置 code.python-path 指定完整路径，或加入 PATH。\n详情：" + e.getMessage());
                out.put("durationMs", elapsed(start));
                return out;
            }
            return execError(e, start);
        } finally {
            deleteDir(dir);
        }
    }

    // ======================== C（gcc 编译 + 运行） ========================

    private Map<String, Object> runC(String code, long start) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("coder_c_");
            Path src = dir.resolve("main.c");
            Path exe = dir.resolve("main.exe");
            Files.writeString(src, code, StandardCharsets.UTF_8);

            // 1. 编译：gcc main.c -o main.exe（优先配置的完整路径，避免依赖系统 PATH）
            ProcessBuilder cpb = new ProcessBuilder(resolveGcc(), src.toString(), "-o", exe.toString(),
                    "-std=c11", "-O2", "-Wall");
            cpb.redirectErrorStream(true);
            Process cp = cpb.start();
            CompletableFuture<String> compileOut = drainAsync(cp);
            if (!cp.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cp.destroyForcibly();
                out.put("code", 500);
                out.put("msg", "编译超时（" + COMPILE_TIMEOUT_SECONDS + "s）");
                out.put("durationMs", elapsed(start));
                return out;
            }
            String compileMsg = compileOut.get(2, TimeUnit.SECONDS);
            if (cp.exitValue() != 0) {
                out.put("code", 500);
                out.put("msg", "编译失败：\n" + truncate(compileMsg));
                out.put("durationMs", elapsed(start));
                return out;
            }

            // 2. 运行
            ProcessBuilder rpb = new ProcessBuilder(exe.toString());
            rpb.directory(dir.toFile());
            rpb.redirectErrorStream(true);
            Process rp = rpb.start();
            CompletableFuture<String> runOut = drainAsync(rp);
            if (!rp.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                rp.destroyForcibly();
                out.put("code", 500);
                out.put("msg", "运行超时（" + RUN_TIMEOUT_SECONDS + "s）");
                out.put("durationMs", elapsed(start));
                return out;
            }
            out.put("code", 200);
            out.put("output", truncate(runOut.get(2, TimeUnit.SECONDS)));
            out.put("exitCode", rp.exitValue());
            out.put("durationMs", elapsed(start));
            return out;
        } catch (Exception e) {
            // gcc 不存在时 ProcessBuilder.start() 抛 IOException，给友好提示
            if (e instanceof IOException || (e.getCause() instanceof IOException)) {
                out.put("code", 500);
                out.put("msg", "未找到 gcc 编译器。请安装 MinGW-w64 并在配置 code.gcc-path 中指定完整路径（如 D:/driver/mingw64/bin/gcc.exe），或加入 PATH。\n详情：" + e.getMessage());
                out.put("durationMs", elapsed(start));
                return out;
            }
            return execError(e, start);
        } finally {
            deleteDir(dir);
        }
    }

    // ======================== 工具方法 ========================

    /** 解析 gcc 可执行文件路径：配置的完整路径存在则用之，否则回退 PATH 的 gcc */
    private String resolveGcc() {
        if (gccPath != null && !gccPath.isBlank()) {
            java.io.File f = new java.io.File(gccPath);
            if (f.exists()) return f.getAbsolutePath();
            log.warn("配置的 gcc 路径不存在: {}，回退 PATH", gccPath);
        }
        return "gcc";
    }

    /** 解析 python 可执行文件路径：配置的完整路径存在则用之，否则回退 PATH 的 python */
    private String resolvePython() {
        if (pythonPath != null && !pythonPath.isBlank()) {
            java.io.File f = new java.io.File(pythonPath);
            if (f.exists()) return f.getAbsolutePath();
            log.warn("配置的 python 路径不存在: {}，回退 PATH", pythonPath);
        }
        return "python";
    }

    /** 后台线程读流，返回读完后即完成的 future */
    private static CompletableFuture<String> drainAsync(Process p) {
        return CompletableFuture.supplyAsync(() -> drain(p.getInputStream()));
    }

    private static String drain(InputStream is) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取输出失败：" + e.getMessage();
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_OUTPUT ? s : s.substring(0, MAX_OUTPUT) + "\n...[输出截断]";
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static Map<String, Object> execError(Exception e, long start) {
        Map<String, Object> out = new LinkedHashMap<>();
        log.warn("代码执行失败: {}", e.getMessage());
        out.put("code", 500);
        out.put("msg", "执行失败：" + e.getMessage());
        out.put("durationMs", elapsed(start));
        return out;
    }

    private static void deleteDir(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String str(Object o) {
        if (o == null) return "";
        return String.valueOf(o).trim();
    }
}
