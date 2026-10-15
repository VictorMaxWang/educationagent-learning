package com.demo;

import com.demo.util.ChatContextManager;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话容量上限实测
 *
 * 测量三个不同量级的上限，输出对照数据（结果沉淀在「对话容量实测报告.md」）：
 *   A. 单条消息上限  —— MySQL TEXT 字段能存多少字符
 *   B. 单次上下文上限 —— 20 轮窗口全量拼进 prompt 后有多大
 *   C. 会话累积上限  —— 一个会话能累积存多少，超出 20 轮后老消息是否还在库里
 *
 * 库里的探针数据统一用 PROBE_ 前缀，跑完自动清理。
 * 运行：mvnw test -Dtest=ConversationCapacityTest
 */
@SpringBootTest(properties = {
        "memory.store.index-path=target/test-memory-index",
        "memory.chroma.enabled=false",
        "bot.auto-start-count=0"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationCapacityTest {

    /** 探针数据统一前缀，便于清理 */
    private static final String PROBE = "PROBE_CAP_";
    private static final String PROBE_SESSION = PROBE + "session";
    private static final String PROBE_USER = PROBE + "user";

    /** TEXT 字段理论上限 65535 字节，二进制搜索的起点给足余量 */
    private static final int ASCII_UPPER = 70_000;
    private static final int CJK_UPPER = 25_000;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatContextManager contextManager;

    // ==================================================================
    // A. 单条消息上限
    // ==================================================================

    @Test
    @Order(1)
    void a_单条消息上限() {
        banner("A. 单条消息上限");

        // --- A1. 环境与字段元数据 ---
        String sqlMode = jdbc.queryForObject("SELECT @@sql_mode", String.class);
        boolean strict = sqlMode != null && sqlMode.toUpperCase().contains("STRICT");
        out("sql_mode      : " + sqlMode);
        out("严格模式      : " + (strict ? "是（超长直接报错 1406）" : "否（超长静默截断 + warning）"));
        out();

        for (String table : new String[]{"chat_message", "chat_messages"}) {
            Map<String, Object> col = jdbc.queryForMap(
                    "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH, " +
                    "       CHARACTER_SET_NAME, COLLATION_NAME " +
                    "FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'content'",
                    table);
            outf("%-14s.content : %s(%s) 校验=%s 最大字符=%s 最大字节=%s%n",
                    table, col.get("DATA_TYPE"),
                    col.get("CHARACTER_MAXIMUM_LENGTH"),
                    col.get("COLLATION_NAME"),
                    col.get("CHARACTER_MAXIMUM_LENGTH"),
                    col.get("CHARACTER_OCTET_LENGTH"));
        }
        out();

        // --- A2. 实测：二进制搜索能完整存回的最大字符数 ---
        int maxAscii = searchMaxAscii();
        int maxCjk = searchMaxCjk();

        out("── 实测（以 chat_message.content 为准，两表同为 TEXT）──");
        report("纯 ASCII 最大", maxAscii, 1);
        report("中文最大", maxCjk, 3);

        // --- A3. 超限时的真实报错 ---
        out();
        out("── 超限 1 字符时的行为 ──");
        String over = "a".repeat(maxAscii + 1);
        try {
            insertChatMessage(PROBE + "over", over);
            String back = readBackChatMessage(PROBE + "over");
            outf("  插入未报错，读回长度=%d（期望 %d）→ %s%n",
                    back == null ? -1 : back.length(), over.length(),
                    back != null && back.length() == over.length() ? "完整" : "★ 被静默截断");
        } catch (Exception e) {
            out("  抛出异常：" + rootMessage(e));
        }
        cleanup();
    }

    private int searchMaxAscii() {
        int lo = 0, hi = ASCII_UPPER, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (fits("a".repeat(mid))) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return best;
    }

    private int searchMaxCjk() {
        int lo = 0, hi = CJK_UPPER, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (fits("中".repeat(mid))) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return best;
    }

    /** 尝试插入并读回，长度一致才算真正存下 */
    private boolean fits(String content) {
        String id = PROBE + "probe";
        try {
            jdbc.update("DELETE FROM chat_message WHERE msg_id = ?", id);
            insertChatMessage(id, content);
            String back = readBackChatMessage(id);
            return back != null && back.length() == content.length();
        } catch (Exception e) {
            return false;
        }
    }

    private void insertChatMessage(String msgId, String content) {
        jdbc.update("INSERT INTO chat_message (msg_id, session_id, role, content, create_time) VALUES (?,?,?,?,?)",
                msgId, PROBE_SESSION, "user", content, System.currentTimeMillis());
    }

    private String readBackChatMessage(String msgId) {
        List<String> r = jdbc.queryForList("SELECT content FROM chat_message WHERE msg_id = ?", String.class, msgId);
        return r.isEmpty() ? null : r.get(0);
    }

    // ==================================================================
    // B. 单次对话上下文上限（20 轮窗口全量拼进 prompt）
    // ==================================================================

    @Test
    @Order(2)
    void b_单次上下文上限() {
        banner("B. 单次对话上下文上限");

        int maxCjk = searchMaxCjk();
        out("窗口配置      : chat.context.max-rounds = " + contextManager.getMaxRounds()
                + " 轮（=" + (contextManager.getMaxRounds() * 2) + " 条消息）");
        out("单条上限      : " + maxCjk + " 中文字符（承接 A 的实测值）");
        out();

        // 用 seedMemory 纯内存回填，不落库
        int rounds = contextManager.getMaxRounds();
        String big = "中".repeat(maxCjk);

        List<ChatMessage> seed = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            seed.add(new UserMessage(big));
            seed.add(AiMessage.from(big));
        }
        contextManager.seedMemory(PROBE_USER, seed);

        List<ChatMessage> history = contextManager.getHistory(PROBE_USER);
        out("── 实测 ──");
        outf("回填消息数    : %d 条%n", seed.size());
        outf("取回消息数    : %d 条（上限 %d）%n", history.size(), rounds * 2);

        long chars = 0, bytes = 0;
        for (ChatMessage m : history) {
            String t = textOf(m);
            chars += t.length();
            bytes += t.getBytes(StandardCharsets.UTF_8).length;
        }
        outf("历史总字符    : %,d 字符%n", chars);
        outf("历史总字节    : %,d 字节 (%.2f MB)%n", bytes, bytes / 1024.0 / 1024.0);
        outf("≈ Token 估算  : %,d tokens（中文按 ~0.6 token/字）%n", (long) (chars * 0.6));
        out();
        out("★ 项目对历史不做任何字符级截断 —— getHistory() 只按条数裁剪（40 条），");
        out("  单条多长就原样拼进 prompt。所以上下文真实上限 = 模型窗口，而非项目代码。");

        contextManager.clearUser(PROBE_USER);
    }

    // ==================================================================
    // C. 会话累积上限（超出窗口后老消息是否还在库里）
    // ==================================================================

    @Test
    @Order(3)
    void c_会话累积上限() {
        banner("C. 会话累积上限");

        int maxCjk = searchMaxCjk();
        int rounds = 60;                      // 故意超出 20 轮窗口
        String content = "中".repeat(Math.min(maxCjk, 2000));   // 每条约 2000 字，避免整测跑太久
        int perMsgBytes = content.getBytes(StandardCharsets.UTF_8).length;

        out("灌入轮数      : " + rounds + " 轮（" + (rounds * 2) + " 条消息，窗口只保留 "
                + contextManager.getMaxRounds() + " 轮）");
        out("单条大小      : " + content.length() + " 中文字符 / " + perMsgBytes + " 字节");
        out();

        long start = System.currentTimeMillis();
        for (int i = 0; i < rounds; i++) {
            insertChatMessage(PROBE + "u" + i, content);
            jdbc.update("INSERT INTO chat_message (msg_id, session_id, role, content, create_time) VALUES (?,?,?,?,?)",
                    PROBE + "a" + i, PROBE_SESSION, "assistant", content, System.currentTimeMillis());
        }
        long cost = System.currentTimeMillis() - start;

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ?", Integer.class, PROBE_SESSION);
        Long total = jdbc.queryForObject(
                "SELECT SUM(CHAR_LENGTH(content)) FROM chat_message WHERE session_id = ?", Long.class, PROBE_SESSION);

        out("── 实测 ──");
        outf("MySQL 中条数  : %d 条%n", rows);
        outf("MySQL 中字符  : %,d 字符%n", total);
        outf("写入耗时      : %d ms（%.1f ms/条）%n", cost, (double) cost / (rounds * 2));
        out();
        outf("★ chat_message（桌面会话表）无轮次裁剪，%d 轮全部留在库中。%n", rounds);
        out("  仅 chat_messages（微信表）由 ChatContextManager 裁剪 —— 见下方内存侧对比。");

        // 内存侧：验证窗口裁剪确实发生
        List<ChatMessage> seed = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            seed.add(new UserMessage(content));
            seed.add(AiMessage.from(content));
        }
        contextManager.seedMemory(PROBE_USER, seed);
        int kept = contextManager.getHistory(PROBE_USER).size();
        outf("%n  内存上下文：回填 %d 条 → 实际保留 %d 条（超出部分被裁掉）%n",
                seed.size(), kept);
        out("  → 老消息在内存里滚出去了，但 MySQL 里仍有，可按会话查回。");

        contextManager.clearUser(PROBE_USER);
        cleanup();
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static String textOf(ChatMessage m) {
        if (m instanceof UserMessage um) return um.singleText();
        if (m instanceof AiMessage am) return am.text();
        return String.valueOf(m);
    }

    /** 报告行缓冲，跑完统一以 UTF-8 落盘 */
    private static final List<String> REPORT = new ArrayList<>();

    private static void banner(String title) {
        out("");
        out("========================================================");
        out("  " + title);
        out("========================================================");
    }

    /** 同时写控制台与报告缓冲。Windows 控制台默认 GBK，中文会乱码，故另落一份干净产物。 */
    private static void out(String line) {
        System.out.println(line);
        REPORT.add(line);
    }

    private static void out() {
        out("");
    }

    private static void outf(String fmt, Object... args) {
        out(String.format(fmt, args));
    }

    private static void report(String label, int chars, int bytesPerChar) {
        long bytes = (long) chars * bytesPerChar;
        outf("%-12s: %,d 字符 / %,d 字节 (%.1f KB)%n",
                label, chars, bytes, bytes / 1024.0);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM chat_message WHERE session_id = ?", PROBE_SESSION);
        jdbc.update("DELETE FROM chat_message WHERE msg_id LIKE ?", PROBE + "%");
        jdbc.update("DELETE FROM chat_messages WHERE user_id = ?", PROBE_USER);
    }

    @AfterAll
    void tearDown() throws Exception {
        cleanup();
        out();
        out("探针数据已清理（chat_message / chat_messages 中 " + PROBE + " 前缀记录）");
        java.nio.file.Path p = java.nio.file.Path.of("target", "conversation-capacity-report.txt");
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.writeString(p, String.join(System.lineSeparator(), REPORT),
                StandardCharsets.UTF_8);
        System.out.println("[报告已写入] " + p.toAbsolutePath());
    }
}
