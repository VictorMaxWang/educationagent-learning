package com.demo.service.tools;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务
 * 使用 Spring Boot Mail + SMTP 协议发送邮件
 * 支持配置多邮箱账号，按需切换发件人
 */
@Slf4j
@Service
public class SendEmailService {

    @Value("${spring.mail.username}")
    private String defaultFrom;

    @Resource
    private JavaMailSender mailSender;

    /**
     * 发送普通文本邮件
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param body    邮件正文
     * @return 发送结果
     */
    public String sendEmail(String to, String subject, String body) {
        // 校验邮箱配置
        if (defaultFrom == null || defaultFrom.isBlank() || defaultFrom.startsWith("${")) {
            return "⚠️ 邮件服务未配置发件人邮箱，请在 application-dev.properties 中设置 spring.mail.username";
        }

        // 校验收件人
        if (to == null || to.isBlank() || !to.matches("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$")) {
            return "收件人邮箱格式不正确，请检查后重试~";
        }

        // 校验主题
        if (subject == null || subject.isBlank()) {
            subject = "无主题";
        }

        // 校验正文
        if (body == null || body.isBlank()) {
            return "邮件正文不能为空，请补充内容后重试~";
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(defaultFrom);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            log.info("📧 邮件发送成功 → [{}] 主题：{}", to, subject);
            return "✅ 邮件已成功发送到 " + to + "，请查收~";
        } catch (Exception e) {
            log.error("📧 邮件发送失败 → [{}]：{}", to, e.getMessage());
            return "⚠️ 邮件发送失败：" + e.getMessage();
        }
    }
}
