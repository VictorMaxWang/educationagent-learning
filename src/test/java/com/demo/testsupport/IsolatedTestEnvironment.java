package com.demo.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 测试启动前校验隔离资源，缺少显式配置就失败，绝不回退本机 root/3306 或默认 Redis。
 * <p>只修改本测试 ApplicationContext 的属性，不改进程全局配置、环境变量或用户目录。
 */
public final class IsolatedTestEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Path RUN_ROOT = Path.of("target", "test-isolated", UUID.randomUUID().toString()).toAbsolutePath().normalize();

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Map<String, Object> values = mysqlSettings(System.getenv());
        if (context.getEnvironment().getProperty("test.isolation.real-redis", Boolean.class, false)) {
            values.putAll(redisSettings(System.getenv()));
        }
        try {
            Files.createDirectories(RUN_ROOT);
            Path data = Files.createTempDirectory(RUN_ROOT, "context-");
            values.put("test.isolation.data-root", data.toString());
            values.put("memory.store.index-path", data.resolve("memory-index").toString());
            values.put("router.tfidf.store-path", data.resolve("intent-tfidf.json").toString());
            values.put("router.eval.log-path", data.resolve("router-eval.jsonl").toString());
            values.put("classroom.data-dir", data.resolve("classroom").toString());
            values.put("CLASSROOM_DATA_DIR", data.resolve("classroom").toString());
            values.put("chat.context.data-dir", data.resolve("chat-context").toString());
            values.put("file.convert.temp-root", data.resolve("file-convert").toString());
            values.put("file.convert.libreoffice.temp-dir", data.resolve("office-convert").toString());
            values.put("file.convert.im.temp-dir", data.resolve("image-convert").toString());
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建 target 下的隔离测试目录", ex);
        }
        // AiChatProperties 优先读大写键，必须覆盖宿主可能已有的真实模型环境变量。
        values.put("AI_CHAT_BASE_URL", "http://127.0.0.1:1/v1");
        values.put("AI_CHAT_API_KEY", "isolated-test-placeholder");
        values.put("AI_CHAT_MODEL", "isolated-test-model");
        values.put("AI_CHAT_ENABLE_THINKING", "false");
        values.put("CLASSROOM_IMAGE_ENABLED", "false");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-test-boundaries", values));
    }

    static Map<String, Object> mysqlSettings(Map<String, String> environment) {
        String url = required(environment, "TEST_MYSQL_URL");
        String user = required(environment, "TEST_MYSQL_USER");
        String password = requiredPresent(environment, "TEST_MYSQL_PASSWORD");
        URI endpoint;
        try {
            if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            endpoint = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("TEST_MYSQL_URL 必须是独立本机 MySQL 的 JDBC URL");
        }
        String database = endpoint.getPath() == null ? "" : endpoint.getPath().replaceFirst("^/", "");
        if (!isLoopback(endpoint.getHost()) || endpoint.getUserInfo() != null
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || endpoint.getPort() == 3306
                || !database.matches("(?i)(?:test(?:_[a-z0-9_]+)?|[a-z0-9_]+_test(?:_[a-z0-9_]+)?)")
                || "root".equalsIgnoreCase(user)) {
            throw new IllegalStateException("隔离测试仅允许 loopback 非3306端口、test命名数据库和非root专用用户");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.datasource.url", url);
        values.put("spring.datasource.username", user);
        values.put("spring.datasource.password", password);
        values.put("test.isolation.database", database);
        return values;
    }

    static Map<String, Object> redisSettings(Map<String, String> environment) {
        String host = required(environment, "TEST_REDIS_HOST");
        int port;
        try { port = Integer.parseInt(required(environment, "TEST_REDIS_PORT")); }
        catch (NumberFormatException ex) { throw new IllegalStateException("TEST_REDIS_PORT 必须为独立测试实例的端口"); }
        if (!isLoopback(host) || port < 1 || port > 65535 || port == 6379)
            throw new IllegalStateException("容量基准仅允许显式 loopback 非6379端口的独立 Redis");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.data.redis.host", host);
        values.put("spring.data.redis.port", port);
        values.put("spring.data.redis.password", environment.getOrDefault("TEST_REDIS_PASSWORD", ""));
        values.put("spring.data.redis.database", 0);
        return values;
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = requiredPresent(environment, name);
        if (value.isBlank()) throw new IllegalStateException("隔离测试需要显式设置 " + name + "，不会使用本机默认配置");
        return value;
    }

    private static String requiredPresent(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null) throw new IllegalStateException("隔离测试需要显式设置 " + name + "，不会使用本机默认配置");
        return value;
    }

    /** 每次测试 JVM 生成独立报告目录，不覆盖历史测量产物。 */
    public static Path reportPath(String name) throws IOException {
        if (!name.matches("[a-z-]+\\.txt")) throw new IllegalArgumentException("报告名只能使用简单文件名");
        Path reports = RUN_ROOT.resolve("capacity-reports");
        Files.createDirectories(reports);
        return reports.resolve(name);
    }
}
