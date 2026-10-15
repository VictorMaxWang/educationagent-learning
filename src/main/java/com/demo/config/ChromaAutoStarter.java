package com.demo.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Chroma 向量数据库自动启动器
 * 项目启动时自动拉起 Chroma 进程，项目关闭时自动终止
 */
@Slf4j
@Component
public class ChromaAutoStarter {

    @Value("${memory.chroma.enabled:false}")
    private boolean chromaEnabled;

    @Value("${memory.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    private Process chromaProcess;

    @PostConstruct
    public void startChroma() {
        if (!chromaEnabled) {
            log.info("📴 Chroma 未启用，跳过自动启动");
            return;
        }

        // 先检查是否已经在运行
        int port = extractPort();
        if (isPortAlive(port)) {
            log.info("✅ Chroma 已在运行（端口={}）", port);
            return;
        }

        log.info("🚀 自动启动 Chroma 向量数据库（端口={}）...", port);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "chroma", "run", "--host", "localhost", "--port", String.valueOf(port));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            chromaProcess = pb.start();

            boolean ready = waitForPort(port, 20);
            if (ready) {
                log.info("✅ Chroma 已就绪（端口={}，PID={}）", port, chromaProcess.pid());
            } else if (!chromaProcess.isAlive()) {
                // 子进程秒退的典型原因是端口已被占用（已有实例在跑），把它说清楚，
                // 别让「启动超时」这种模糊文案把人引到错误方向
                log.warn("⚠ Chroma 子进程已退出（端口 {} 可能已被占用）。若已有实例在跑可忽略；"
                        + "否则请手动执行 chroma run --port {}", port, port);
            } else {
                log.warn("⚠ Chroma 启动超时（端口={}）", port);
            }
        } catch (IOException e) {
            log.warn("⚠ 自动启动 Chroma 失败：{}。请手动执行 chroma run --port {}", e.getMessage(), port);
        }
    }

    @PreDestroy
    public void stopChroma() {
        if (chromaProcess != null && chromaProcess.isAlive()) {
            log.info("🛑 关闭 Chroma（PID={}）...", chromaProcess.pid());
            chromaProcess.destroy();
            try {
                chromaProcess.waitFor();
                log.info("✅ Chroma 已关闭");
            } catch (InterruptedException e) {
                chromaProcess.destroyForcibly();
            }
        }
    }

    private int extractPort() {
        try {
            return Integer.parseInt(chromaUrl.replaceAll(".*:(\\d+).*", "$1"));
        } catch (Exception e) {
            return 8000;
        }
    }

    /**
     * 探测端口是否有服务在监听。
     *
     * <p>不能用 {@code new Socket("localhost", port)}：它只尝试 DNS 解析出的第一个地址，
     * 而 Windows 上 localhost 常先解析到 IPv6 {@code ::1}，若对方只绑了 IPv4
     * （{@code chroma run --host localhost} 就可能如此），探测会失败 —— 于是明明服务在跑
     * 却误报「启动超时」。{@code RestTemplate} 会做多地址回退所以业务调用不受影响，
     * 只有这个裸 Socket 探针会踩坑。这里显式把两个回环地址都试一遍。</p>
     */
    private boolean isPortAlive(int port) {
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            try (var s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port), 500);
                return true;
            } catch (Exception ignored) {
                // 换下一个回环地址
            }
        }
        return false;
    }

    private boolean waitForPort(int port, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            if (isPortAlive(port)) return true;
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        return false;
    }
}
