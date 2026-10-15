package com.demo.fileconvert.engine;

import com.demo.fileconvert.exception.ConvertProcessException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 外部进程执行器 —— 统一处理：合并输出流 + 后台排空 + 超时销毁 + 退出码校验。
 * 安全设计：所有参数使用 {@link List} 直接传给 ProcessBuilder，杜绝 shell 注入。
 *【外部类依赖调用说明】
 *    自定义异常：{@link ConvertProcessException}（本项目自定义异常类）
 *    JDK原生类：ProcessBuilder、Process、BufferedReader、InputStreamReader、AtomicReference、TimeUnit、List 等JDK标准API
 *    无其他业务自定义类方法调用，仅抛出这一个自定义异常，没有调用其他外部类的业务方法
 */
public final class CommandRunner {

    // 私有构造方法，工具类禁止实例化
    private CommandRunner() {}

    /**
     * 进程执行结果记录体
     * @param exitCode 进程退出码，0=正常退出
     * @param output 进程标准输出+标准错误合并日志（最大缓存4096字符）
     */
    public record RunResult(int exitCode, String output) {}

    /**
     * 执行进程并等待完成。
     * @param cmd       完整命令（List 参数，无 shell）
     * @param timeoutMs 超时毫秒，超时强制 destroyForcibly
     * @param engineName 引擎名（日志/异常文案用），如 "LibreOffice"
     * @return 退出码 + 输出（截断）
     * @throws ConvertProcessException 超时、启动失败或退出码 != 0
     */
    public static RunResult run(List<String> cmd, long timeoutMs, String engineName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 后台线程排空输出流，避免管道缓冲区写满导致进程阻塞
            AtomicReference<String> output = new AtomicReference<>("");
            Thread drain = new Thread(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (sb.length() < 4096) sb.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                    // 进程被杀时流关闭属正常
                }
                output.set(sb.toString());
            }, "cmd-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new ConvertProcessException(engineName + " 转换超时（> " + timeoutMs + "ms），进程已被强制终止");
            }

            int exit = p.exitValue();
            if (exit != 0) {
                throw new ConvertProcessException(engineName + " 转换进程异常退出 exitCode=" + exit
                        + (output.get().isBlank() ? "" : " | " + truncate(output.get(), 200)));
            }
            return new RunResult(exit, output.get());
        } catch (ConvertProcessException e) {
            throw e;
        } catch (Exception e) {
            throw new ConvertProcessException(engineName + " 进程启动失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
