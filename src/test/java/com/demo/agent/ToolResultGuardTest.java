package com.demo.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果长度护栏测试。
 *
 * <p>防的是"一次网页抓取/大文件读取把整个对话打成 AI 服务不可用"——
 * 报错信息（{@code Range of input length should be [1, 1000000]}）
 * 完全看不出真实原因，所以必须有测试钉住。
 */
class ToolResultGuardTest {

    @Test
    void 短结果原样通过() {
        ToolResultGuard.Result r = ToolResultGuard.guard("图片已生成完成，已发送给你，请查收！");
        assertFalse(r.truncated());
        assertEquals("图片已生成完成，已发送给你，请查收！", r.text());
    }

    @Test
    void 恰好等于上限时不截断() {
        String exact = "x".repeat(ToolResultGuard.DEFAULT_MAX_CHARS);
        ToolResultGuard.Result r = ToolResultGuard.guard(exact);
        assertFalse(r.truncated(), "刚好等于上限不该被截断");
        assertEquals(ToolResultGuard.DEFAULT_MAX_CHARS, r.text().length());
    }

    @Test
    void 超长结果被截断并带标记() {
        String longText = "网页正文".repeat(2000); // 8000 字符
        ToolResultGuard.Result r = ToolResultGuard.guard(longText);

        assertTrue(r.truncated());
        assertEquals(longText.length(), r.originalLength());
        assertTrue(r.text().startsWith("网页正文"), "应当保留头部内容");
        assertTrue(r.text().contains("内容过长已截断"), "必须有明确标记，让模型知道信息不完整");
        assertTrue(r.text().contains(String.valueOf(longText.length())), "标记里应含原始长度，便于排查");
        assertTrue(r.text().length() < longText.length());
    }

    /** 截断后保留的内容恰好是前 N 个字符，之后紧跟截断标记（标记额外占的字符不算内容）。 */
    @Test
    void 保留内容不超过上限() {
        String longText = "a".repeat(10000);
        ToolResultGuard.Result r = ToolResultGuard.guard(longText, 100);

        // 标记以换行开头，这样模型能清楚看出内容到此为止
        assertTrue(r.text().startsWith("a".repeat(100) + "\n…[内容过长已截断"),
                "应当保留前 100 个字符后紧跟截断标记，实际开头=" + r.text().substring(0, 110));
    }

    @Test
    void 空与null安全() {
        assertEquals("", ToolResultGuard.guard(null).text());
        assertFalse(ToolResultGuard.guard(null).truncated());
        assertEquals(0, ToolResultGuard.guard(null).originalLength());

        ToolResultGuard.Result empty = ToolResultGuard.guard("");
        assertEquals("", empty.text());
        assertFalse(empty.truncated());
    }

    @Test
    void 非法上限回退到默认值() {
        String longText = "b".repeat(ToolResultGuard.DEFAULT_MAX_CHARS + 100);
        assertTrue(ToolResultGuard.guard(longText, 0).truncated());
        assertTrue(ToolResultGuard.guard(longText, -5).truncated());
        // 回退后应当按默认上限截断
        assertTrue(ToolResultGuard.guard(longText, 0).text().startsWith("b".repeat(ToolResultGuard.DEFAULT_MAX_CHARS)));
    }

    /** 多轮工具调用累积的场景：8 轮各 4000 字符仍在百万字符上限内。 */
    @Test
    void 八轮累积不会越界() {
        int total = 8 * ToolResultGuard.DEFAULT_MAX_CHARS;
        assertTrue(total < 100_000,
                "8 轮 × 4000 字符 = " + total + "，远低于 LLM 的 1,000,000 上限");
    }
}
