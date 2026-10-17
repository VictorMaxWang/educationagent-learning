package com.demo;

import com.demo.session.cache.SessionCacheManager;
import com.demo.session.entity.SessionMessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import com.demo.testsupport.IsolatedSpringBootTest;
import com.demo.testsupport.IsolatedTestEnvironment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Redis 侧容量实测
 *
 * 会话消息列表（chat:messages:{id}）缓存的是**整份消息列表**，长会话会把它撑大。
 * 本测试测量：
 *   D1. Redis 服务端的内存上限与淘汰策略
 *   D2. chat:messages 单个会话的序列化体积与写入耗时（按消息条数递增）
 *   D3. chat:memory 上下文镜像的体积
 *   D4. 单会话在 Redis 中的真实内存占用（INFO memory 差值）
 *   D5. TTL 是否正确设置、evict 是否生效
 *
 * 默认跳过；显式设置 RUN_CAPACITY_BENCHMARKS=true、TEST_MYSQL_* 和 TEST_REDIS_HOST/PORT 后才可运行。
 * 报告写入 target/test-isolated 本轮专属目录，不覆盖历史容量报告。
 */
@IsolatedSpringBootTest
@TestPropertySource(properties = "test.isolation.real-redis=true")
@Tag("capacity-benchmark")
@EnabledIfEnvironmentVariable(named = "RUN_CAPACITY_BENCHMARKS", matches = "(?i)true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCapacityTest {

    private static final String PROBE = "PROBE_REDIS_";
    private static final List<String> REPORT = new ArrayList<>();

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SessionCacheManager cacheManager;

    // ==================================================================

    @Test
    @Order(1)
    void d1_Redis服务端配置() {
        banner("D1. Redis 服务端配置");

        Properties info = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) conn ->
                conn.serverCommands().info("memory"));

        if (info == null) {
            out("无法读取 INFO memory");
            return;
        }
        String maxMemory = String.valueOf(info.get("maxmemory"));
        long maxMem = parseLong(maxMemory);
        out("used_memory      : " + info.get("used_memory_human") + " (" + info.get("used_memory") + " bytes)");
        out("used_memory_peak : " + info.get("used_memory_peak_human"));
        out("maxmemory        : " + (maxMem == 0 ? "0（未设置上限，即不限制）" : maxMemory + " bytes"));
        out("maxmemory_policy : " + info.get("maxmemory_policy"));
        out();
        if (maxMem == 0) {
            out("★ 未设置 maxmemory —— Redis 可以一直吃内存直到耗尽机器可用内存，");
            out("  不会被 LRU 淘汰，也没有 OOM 保护。长会话堆积是被动发现而非主动拒绝。");
        } else {
            out("★ 已设置 maxmemory=" + maxMem + " bytes，策略=" + info.get("maxmemory_policy"));
        }
    }

    // ==================================================================

    @Test
    @Order(2)
    void d2_会话消息缓存体积() {
        banner("D2. chat:messages 会话消息缓存体积");

        // 单条用实测上限的一半（1 万中文字），模拟「用户经常发长文本」的场景
        int[] counts = {100, 500, 1000, 2000};
        int charPerMsg = 10_000;

        out("单条消息大小 : " + charPerMsg + " 中文字符 / " + (charPerMsg * 3) + " 字节");
        out();
        out(String.format("%-10s %-16s %-16s %-14s %-14s",
                "消息条数", "序列化后字节", "序列化后MB", "写入耗时ms", "单条均摊ms"));
        out("-".repeat(76));

        for (int n : counts) {
            List<SessionMessageEntity> msgs = buildMessages(PROBE + "s" + n, n, charPerMsg);

            long t0 = System.nanoTime();
            cacheManager.putMessages(PROBE + "s" + n, msgs);
            long costMs = (System.nanoTime() - t0) / 1_000_000;

            long bytes = redisStrLen("chat:messages:" + PROBE + "s" + n);
            out(String.format("%-10d %-16s %-16.2f %-14d %-14.2f",
                    n, String.format("%,d", bytes), bytes / 1024.0 / 1024.0,
                    costMs, (double) costMs / n));
        }
        out();
        out("★ 缓存的是整份消息列表，体积随条数**线性增长**，且每次 putMessages 都要全量重写。");
    }

    // ==================================================================

    @Test
    @Order(3)
    void d3_上下文镜像体积() {
        banner("D3. chat:memory 上下文镜像体积");

        // 与实际窗口一致：20 轮 = 40 条
        int rounds = 20;
        int charPerMsg = 21_845;   // A 测出的单条中文上限

        List<ChatMessage> ctx = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            ctx.add(new UserMessage("中".repeat(charPerMsg)));
            ctx.add(AiMessage.from("中".repeat(charPerMsg)));
        }

        long t0 = System.nanoTime();
        cacheManager.saveMemory(PROBE + "mem", ctx);
        long costMs = (System.nanoTime() - t0) / 1_000_000;

        long bytes = redisStrLen("chat:memory:" + PROBE + "mem");
        out("消息条数     : " + ctx.size() + " 条（20 轮窗口满载）");
        out("序列化字节   : " + String.format("%,d", bytes) + " bytes (" + String.format("%.2f", bytes / 1024.0 / 1024.0) + " MB)");
        out("写入耗时     : " + costMs + " ms");
        out("放大倍数     : " + String.format("%.2f", (double) bytes / (charPerMsg * 3L * ctx.size())) + "x（相对原文 UTF-8 字节）");
        out();
        out("★ chat:memory 与 chat:messages 是**两份独立副本**，同一个会话在 Redis 里存两遍。");
    }

    // ==================================================================

    @Test
    @Order(4)
    void d4_真实内存占用() {
        banner("D4. 单会话在 Redis 中的真实内存占用");

        long before = usedMemory();
        String sid = PROBE + "real";
        int n = 1000;

        cacheManager.putMessages(sid, buildMessages(sid, n, 1000));
        cacheManager.saveMemory(sid, List.of(new UserMessage("中".repeat(5000)), AiMessage.from("中".repeat(5000))));

        long after = usedMemory();
        long delta = after - before;

        out("灌入          : " + n + " 条消息（单条 1000 中文字）+ 上下文镜像");
        out("used_memory   : " + String.format("%,d", before) + " → " + String.format("%,d", after));
        out("实际增量      : " + String.format("%,d", delta) + " bytes (" + String.format("%.2f", delta / 1024.0 / 1024.0) + " MB)");
        out();
        out("★ 这是 Redis 视角的**净增长**（含 dict 开销、SDS 头、jemalloc 对齐），比 STRLEN 更接近真实成本。");
    }

    // ==================================================================

    @Test
    @Order(5)
    void d5_TTL与淘汰() {
        banner("D5. TTL 与淘汰行为");

        String sid = PROBE + "ttl";
        cacheManager.putMessages(sid, buildMessages(sid, 10, 100));
        cacheManager.saveMemory(sid, List.of(new UserMessage("hi")));

        Long msgTtl = redis.getExpire("chat:messages:" + sid);
        Long memTtl = redis.getExpire("chat:memory:" + sid);

        out("chat:messages:{id} TTL : " + fmtTtl(msgTtl) + "  (配置 app.cache.redis.ttl.chat-messages)");
        out("chat:memory:{id}   TTL : " + fmtTtl(memTtl) + "  (配置 app.cache.redis.ttl.chat-memory)");
        out();

        // 验证 evict 真的删除
        boolean existsBefore = Boolean.TRUE.equals(redis.hasKey("chat:messages:" + sid));
        cacheManager.evictMessages(sid);
        boolean existsAfter = Boolean.TRUE.equals(redis.hasKey("chat:messages:" + sid));
        out("evictMessages 前 key 存在 : " + existsBefore);
        out("evictMessages 后 key 存在 : " + existsAfter + (existsBefore && !existsAfter ? "  ✅ 删除生效" : "  ⚠ 未删除"));
        out();
        out("★ 闲置淘汰由守护线程 chat-session-cache-evict 每 max(1, 阈值/3) 分钟扫描一次，");
        out("  默认阈值 30min → 每 10 分钟扫一次，只清 L1/L2，MySQL 数据保留。");
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private List<SessionMessageEntity> buildMessages(String sid, int n, int chars) {
        String filler = "中".repeat(chars);
        List<SessionMessageEntity> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SessionMessageEntity m = new SessionMessageEntity();
            m.setMsgId(PROBE + "m" + i);
            m.setSessionId(sid);
            m.setRole(i % 2 == 0 ? "user" : "assistant");
            m.setContent(filler);
            m.setCreateTime(System.currentTimeMillis() + i);
            list.add(m);
        }
        return list;
    }

    /** 用 Redis STRLEN 拿序列化后的精确字节数 */
    private long redisStrLen(String key) {
        Long len = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) conn ->
                conn.stringCommands().strLen(key.getBytes(StandardCharsets.UTF_8)));
        return len == null ? -1 : len;
    }

    private long usedMemory() {
        Properties info = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) conn ->
                conn.serverCommands().info("memory"));
        return info == null ? -1 : parseLong(String.valueOf(info.get("used_memory")));
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    private static String fmtTtl(Long ttl) {
        if (ttl == null) return "无 key";
        if (ttl == -1) return "-1（永不过期）★";
        if (ttl == -2) return "-2（key 不存在）";
        return ttl + " 秒（" + String.format("%.1f", ttl / 60.0) + " 分钟）";
    }

    private static void banner(String title) {
        out("");
        out("========================================================");
        out("  " + title);
        out("========================================================");
    }

    private static void out(String line) {
        System.out.println(line);
        REPORT.add(line);
    }

    private static void out() { out(""); }

    private void cleanup() {
        for (String k : new String[]{"chat:messages:", "chat:session:", "chat:memory:"}) {
            java.util.Set<String> keys = redis.keys(k + PROBE + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        cleanup();
        out();
        out("探针 key 已清理（" + PROBE + " 前缀）");
        java.nio.file.Path p = IsolatedTestEnvironment.reportPath("redis-capacity-report.txt");
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.writeString(p, String.join(System.lineSeparator(), REPORT), StandardCharsets.UTF_8);
        System.out.println("[报告已写入] " + p.toAbsolutePath());
    }
}
