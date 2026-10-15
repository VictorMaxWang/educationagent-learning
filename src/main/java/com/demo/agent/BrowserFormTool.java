package com.demo.agent;

import com.demo.service.tools.browser.BrowserFormService;
import com.demo.service.tools.browser.BrowserFormService.FormResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LangChain4j Agent 工具 —— 模拟人工填写表单。
 * 在指定网页上模拟真人逐字段点击聚焦、逐字符输入、字段间自然切换的完整填表流程。
 * 【适用场景】：
 * - 用户要求在网页中填写表单
 * - 用户提供需要填写的字段信息和内容
 * - 自动化数据录入（反馈表、调查问卷、联系表单等）
 * 【字段规格格式（每行一个字段，用 | 分隔）】：
 *   字段名|CSS选择器|填写值|类型(text/phone/email/password/checkbox)
 * 【工具能力】：
 * 1. 接收目标URL与表单字段配置，调用浏览器自动化服务执行填表
 * 2. 支持填表完成后页面截图，缓存图片二进制数据供上层读取
 * 3. 返回整体执行状态 + 每个字段填写结果 + 执行操作日志摘要
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserFormTool {

    private final BrowserFormService browserFormService;

    @Getter
    private byte[] lastFormScreenshot;
    @Getter
    private String lastFormScreenshotMime;

    public void clearScreenshot() {
        this.lastFormScreenshot = null;
        this.lastFormScreenshotMime = null;
    }

    @Tool("在指定网页上模拟真人填写表单。"
            + "模拟真人依次点击各输入框、逐字符输入内容、字段间自然停顿的完整流程。"
            + "当用户要求在网页中填写表单、填表、录入信息、提交反馈时调用此工具。"
            + "需要提供目标网页URL和字段规格列表。"
            + "字段规格格式：每行一个字段，用竖线|分隔四项：字段名|CSS选择器|填写值|类型。"
            + "类型可选值：text（文本）、phone（手机号）、email（邮箱）、password（密码）、checkbox（复选框）。"
            + "captureAfterFill：true=填表完成自动截取当前网页截图（保留已填写内容），false=不截图，默认false；用户想要核对填写效果、查看页面时设置为true。"
            + "示例：\n"
            + "  姓名|#name|张三|text\n"
            + "  手机号|#phone|13800138000|phone\n"
            + "  邮箱|#email|test@qq.com|email")
    public String fillForm(
            @P("目标网页地址，如：https://example.com/feedback") String url,
            @P("表单字段规格，每行一个字段：字段名|CSS选择器|填写值|类型。"
                    + "多字段用换行分隔。类型：text/phone/email/password/checkbox") String fields,
            @P(value = "填表结束后是否自动截图，true截图，false不截图，默认false", required = false) String captureAfterFill) {

        log.info("📝 Agent 调用模拟填表工具，URL：{}，字段数：{}，填表后自动截图：{}",
                url, fields != null ? fields.split("\\n").length : 0, captureAfterFill);

        clearScreenshot();

        if (url == null || url.isBlank()) {
            return "❌ 填表操作失败：请提供目标网页地址（URL）。";
        }
        if (fields == null || fields.isBlank()) {
            return "❌ 填表操作失败：请提供要填写的表单字段信息。\n"
                    + "格式：每行一个字段，用竖线分隔 字段名|选择器|值|类型\n"
                    + "示例：\n"
                    + "  姓名|#name|张三|text\n"
                    + "  邮箱|#email|test@qq.com|email";
        }

        boolean needCapture = "true".equalsIgnoreCase(captureAfterFill);
        FormResult result = browserFormService.fillForm(url, fields, needCapture);

        // 缓存截图数据
        if (result.screenshotBytes() != null && result.screenshotBytes().length > 0) {
            this.lastFormScreenshot = result.screenshotBytes();
            this.lastFormScreenshotMime = "image/png";
            log.info("📸 填表截图已缓存，大小：{} KB", result.screenshotBytes().length / 1024);
        }

        log.info("📝 模拟填表完成，成功：{}", result.success());

        StringBuilder response = new StringBuilder();
        if (result.success()) {
            response.append("✅ 表单填写完成！\n");
            response.append("目标页面：").append(url).append("\n\n");
            if (needCapture && lastFormScreenshot != null) {
                response.append("📸 已截取当前页面图片，请查看表单填写效果\n\n");
            }
        } else {
            response.append("❌ 表单填写失败：").append(result.message()).append("\n\n");
        }

        // 各字段结果
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

        // 操作摘要
        List<String> logs = result.logs();
        response.append("📋 操作日志摘要：\n");
        int start = Math.max(0, logs.size() - 8);
        for (int i = start; i < logs.size(); i++) {
            response.append("  ").append(logs.get(i)).append("\n");
        }
        if (logs.size() > 8) {
            response.append("  ... 共 ").append(logs.size()).append(" 条日志\n");
        }

        return response.toString().trim();
    }
}
