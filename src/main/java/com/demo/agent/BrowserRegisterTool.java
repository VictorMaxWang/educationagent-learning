package com.demo.agent;

import com.demo.service.tools.browser.BrowserRegisterService;
import com.demo.service.tools.browser.BrowserRegisterService.RegisterResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LangChain4j Agent 工具 —— 模拟账号注册表单提交。
 * 完整模拟用户注册全流程：多字段依次人工填写 → 前置合规校验 → 模拟真人核对停顿 →
 * 勾选用户协议 → 点击注册提交 → 监听提交结果。
 *
 * 提交前自动校验：
 * - 手机号位数、格式合规性
 * - 邮箱格式校验
 * - 密码与确认密码一致性校验
 * - 必填字段非空校验
 *
 * 提交后监听：
 * - 成功跳转 / 弹窗提示 / 字段报错 / 风控拦截（验证码、人机验证）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserRegisterTool {

    private final BrowserRegisterService browserRegisterService;

    @Getter
    private byte[] lastRegisterScreenshot;

    public void clearScreenshot() {
        this.lastRegisterScreenshot = null;
    }

    @Tool("模拟真人在网页上完成账号注册全流程。"
            + "依次填写注册字段→校验格式→勾选协议→核对停顿→提交按钮→监听结果。"
            + "当用户要求在网站上注册账号、帮我注册、填注册表单、创建账号时调用此工具。"
            + "提交前会自动校验手机号格式、邮箱格式、密码一致性、必填非空。"
            + "提交后会监听页面状态判断成功/字段报错/风控拦截。"
            + "captureAfterSubmit：true=注册提交后自动截取结果页，false=不截图，默认false。"
            + "\n字段规格格式（每行一个字段，用|分隔）：\n"
            + "  字段名|CSS选择器|填写值|类型(text/phone/email/password/checkbox)\n"
            + "示例：\n"
            + "  用户名|#username|zhangsan|text\n"
            + "  手机号|#phone|13800138000|phone\n"
            + "  密码|#password|Abc12345|password\n"
            + "  确认密码|#confirmPwd|Abc12345|password\n"
            + "  邮箱|#email|test@qq.com|email")
    public String simulateRegister(
            @P("注册页面地址，如：https://example.com/register") String url,
            @P("注册字段规格，每行一个字段：字段名|CSS选择器|填写值|类型(text/phone/email/password/checkbox)。"
                    + "至少包含用户名、密码、确认密码等必填字段。多字段用换行分隔。") String fields,
            @P("注册/提交按钮的CSS选择器或XPath，如：#registerBtn、button[type='submit']、//button[text()='注册']") String submitSelector,
            @P(value = "用户协议/隐私政策复选框的CSS选择器或XPath（可选），如：#agreeCheckbox、input[name='agree']。不传则不勾选。", required = false) String agreementSelector,
            @P(value = "注册提交后是否自动截图结果页，true截图，false不截图，默认false", required = false) String captureAfterSubmit) {

        log.info("📧 Agent 调用模拟注册工具，URL：{}", url);

        if (url == null || url.isBlank()) {
            return "❌ 注册操作失败：请提供注册页面地址（URL）。";
        }
        if (fields == null || fields.isBlank()) {
            return "❌ 注册操作失败：请提供注册表单字段信息。\n"
                    + "格式：每行一个字段，用竖线分隔 字段名|选择器|值|类型\n"
                    + "示例：\n"
                    + "  用户名|#username|zhangsan|text\n"
                    + "  手机号|#phone|13800138000|phone\n"
                    + "  密码|#password|Abc12345|password\n"
                    + "  确认密码|#confirmPwd|Abc12345|password";
        }
        if (submitSelector == null || submitSelector.isBlank()) {
            return "❌ 注册操作失败：请提供注册/提交按钮的CSS选择器（如：#registerBtn）。";
        }

        clearScreenshot();

        boolean needCapture = "true".equalsIgnoreCase(captureAfterSubmit);
        RegisterResult result = browserRegisterService.simulateRegister(
                url, fields, submitSelector,
                agreementSelector != null && !agreementSelector.isBlank() ? agreementSelector : null,
                needCapture);

        // 缓存截图
        if (result.screenshotBytes() != null && result.screenshotBytes().length > 0) {
            this.lastRegisterScreenshot = result.screenshotBytes();
            log.info("📸 注册截图已缓存，大小：{} KB", result.screenshotBytes().length / 1024);
        }

        log.info("📧 模拟注册完成，成功：{}，状态：{}", result.success(), result.submitStatus());

        StringBuilder response = new StringBuilder();
        response.append(buildStatusHeader(result));
        if (needCapture && lastRegisterScreenshot != null) {
            response.append("📸 已截取注册结果页图片，请查看\n\n");
        }
        response.append("\n");

        // 各字段填写结果
        Map<String, String> fieldResults = result.fieldResults();
        if (!fieldResults.isEmpty()) {
            response.append("📋 字段填写结果：\n");
            for (Map.Entry<String, String> entry : fieldResults.entrySet()) {
                String icon = "成功".equals(entry.getValue()) ? "✅" : "⚠️";
                response.append("  ").append(icon).append(" ").append(entry.getKey())
                        .append("：").append(entry.getValue()).append("\n");
            }
            response.append("\n");
        }

        // 操作日志摘要
        List<String> logs = result.logs();
        response.append("📋 操作日志摘要：\n");
        int start = Math.max(0, logs.size() - 10);
        for (int i = start; i < logs.size(); i++) {
            response.append("  ").append(logs.get(i)).append("\n");
        }
        if (logs.size() > 10) {
            response.append("  ... 共 ").append(logs.size()).append(" 条日志\n");
        }

        return response.toString().trim();
    }

    /**
     * 根据提交结果状态构建用户友好的状态头信息
     */
    private String buildStatusHeader(RegisterResult result) {
        return switch (result.submitStatus()) {
            case "success" -> "✅ 注册提交成功！\n目标页面已响应成功状态。";
            case "validation_error" -> "⚠️ 注册提交后页面提示字段校验错误\n"
                    + result.message() + "\n"
                    + "建议：检查字段格式是否正确，或调整字段内容后重试。";
            case "captcha_blocked" -> "🚫 注册页面触发了验证码/人机验证\n"
                    + "该网站检测到自动化操作并弹出了验证码，当前流程已中断。\n"
                    + "建议：等待一段时间后手动完成验证，或更换注册网站。";
            case "timeout" -> "⏰ 注册提交超时\n页面未在规定时间内响应，可能是网络波动或网站繁忙。";
            default -> "❓ 注册提交结果未明确\n"
                    + result.message() + "\n"
                    + "建议：手动检查页面确认注册是否成功。";
        };
    }
}
