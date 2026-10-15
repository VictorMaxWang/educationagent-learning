package com.demo.agent;

import com.demo.service.tools.SendEmailService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 邮件发送
 * Agent 根据用户语义自主调用，发送文本邮件
 * 用户说 "给xxx@xxx.com发个邮件说hello" → to=xxx@xxx.com, body=hello
 * 用户说 "发邮件给xx主题是yy内容是zz" → 分别提取三个参数
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendEmailTool {

    private final SendEmailService emailService;

    @Tool("发送电子邮件。当用户要求发送邮件、发邮件、写邮件时调用此工具。"
            + "需要三个参数：收件人邮箱(to)、邮件主题(subject)、邮件正文(body)。"
            + "用户可能一次全说，也可能只说部分，缺什么参数就问用户补充。"
            + "例如：'给admin@qq.com发邮件说你好' → to='admin@qq.com', subject='无主题', body='你好'。"
            + "'发邮件给xxx@xxx.com主题是会议通知内容是下午三点开会'"
            + " → to='xxx@xxx.com', subject='会议通知', body='下午三点开会'")
    public String sendEmail(
            @P("收件人邮箱地址，如：admin@qq.com、user@gmail.com") String to,
            @P("邮件主题标题，用户没提就用'无主题'") String subject,
            @P("邮件正文内容，用户说'说hello'则body='hello'") String body) {

        log.info("📧 Agent 调用邮件工具 → [{}] 主题：{}", to, subject);
        String result = emailService.sendEmail(to, subject, body);
        log.info("📧 邮件工具返回：{}", result);
        return result;
    }
}
