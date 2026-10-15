package com.demo.agent;

import com.demo.fileconvert.FileConvertFacade;
import com.demo.fileconvert.FileConvertOutputHolder;
import com.demo.fileconvert.exception.FileFormatNotSupportException;
import com.demo.fileconvert.exception.ForbiddenExecFileException;
import com.demo.service.tools.holder.UserFileHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j Agent 工具 —— 文件格式转换。
 * <p>
 * 双通道：
 * <ol>
 *   <li><b>Agent 工具</b>：{@link #convertFile(String)} 被 LLM 调用，兜底边缘表达</li>
 *   <li><b>预检</b>：{@link #tryPrecheckConvert(String, String)} 被 CmdService 调用，关键词命中即绕过 LLM（零幻觉零费用）</li>
 * </ol>
 * 源文件复用 {@link UserFileHolder} 中缓存的用户最近上传文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileConvertTool {

    /** 转换关键词前缀（中英文全覆盖） */
    private static final String PREFIX_RE = "(?:转|转成|转为|转换|转化成|转换为|转化为|化成|化为|convert\\s+to|convert)\\s*";

    /** 直接扩展名目标：转pdf / 转成docx / convert to pdf */
    private static final Pattern EXT_PATTERN = Pattern.compile(
            "(?:转|转成|转为|转换|转化成|转换为|转化为|化成|化为|convert\\s+to\\s+|convert\\s+)"
                    + "\\s*(pdf|docx|txt|md|rtf|html|jpg|jpeg|png|webp)");

    private final UserFileHolder userFileHolder;
    private final FileConvertFacade fileConvertFacade;
    private final FileConvertOutputHolder outputHolder;

    // ======================== Agent 工具通道 ========================

    @Tool("文件格式转换。将用户最近上传的文件转换为指定格式。"
            + "文档类（doc/docx/xls/xlsx/ppt/pptx/pdf/txt/md/rtf）支持互转 pdf/docx/txt/md/rtf/html，"
            + "图片类（jpg/jpeg/png/gif/webp/heic/tiff/bmp）支持互转 jpg/png/webp。"
            + "用户说「转为pdf」「转成docx」「转成图片」「convert to pdf」时调用，参数传目标格式扩展名。")
    public String convertFile(
            @P(value = "目标格式扩展名，如 pdf、docx、txt、md、rtf、html、jpg、png、webp", required = true) String targetFormat) {
        String userId = com.demo.util.VoiceContext.getUserId();
        if (userId == null) userId = "default";
        log.info("🔄 [{}] Agent 调用 convertFile: target={}", userId, targetFormat);
        return doConvert(userId, targetFormat);
    }

    // ======================== 预检通道（CmdService） ========================

    /**
     * 转换预检：命中转换关键词 + 有缓存文件 → 直接执行转换并返回结果文本。
     * 返回 null 表示「交给 LLM 兜底」：
     *   <li>无转换意图（无关键词）</li>
     *   <li>含疑问词（"能转成什么"）→ 咨询问题</li>
     *   <li>有意图但用户尚未上传文件 → 由 Agent 引导用户先发文件</li>
     */
    public String tryPrecheckConvert(String userId, String userText) {
        String target = extractTargetFormat(userText);
        if (target == null) return null;
        if (!userFileHolder.hasPending(userId)) return null;
        log.info("⚡ [{}] 转换预检命中: 「{}」 → {}", userId, userText.trim(), target);
        return doConvert(userId, target);
    }

    /**
     * 从用户文本中提取转换目标格式；无转换意图返回 null。
     * 静态方法便于单元测试。
     */
    public static String extractTargetFormat(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return null;

        // 疑问词过滤：咨询"能转成什么格式" → 交给 LLM 回答
        if (t.contains("什么") || t.contains("哪") || t.contains("怎么") || t.contains("如何")) return null;

        // 中文映射：转图片→png、转网页→html、转纯文本→txt、转文档/word→pdf
        if (t.matches(".*" + PREFIX_RE + "(图片|图像|照片).*")) return "png";
        if (t.matches(".*" + PREFIX_RE + "(网页|页面).*")) return "html";
        if (t.matches(".*" + PREFIX_RE + "(纯文本|文本).*")) return "txt";
        if (t.matches(".*" + PREFIX_RE + "(文档|word).*")) return "pdf";

        // 直接扩展名目标
        Matcher m = EXT_PATTERN.matcher(t);
        if (m.find()) {
            String ext = m.group(1);
            return "jpeg".equals(ext) ? "jpg" : ext;
        }
        return null;
    }

    // ======================== 内部实现 ========================

    private String doConvert(String userId, String targetFormat) {
        UserFileHolder.FileEntry entry = userFileHolder.get(userId);
        if (entry == null) {
            return "⚠️ 没有找到你最近上传的文件，请先发送一个文件，再告诉我转成什么格式。";
        }
        try {
            FileConvertFacade.ConvertResult result = fileConvertFacade.convert(entry.bytes(), entry.fileName(), targetFormat);
            outputHolder.set(result.bytes(), result.fileName());
            return "✅ 转换成功：" + entry.fileName() + " → " + result.fileName() + "，正在发送文件...";
        } catch (ForbiddenExecFileException | FileFormatNotSupportException e) {
            return "⛔ " + e.getMessage();
        } catch (Exception e) {
            log.error("❌ [{}] 文件转换失败", userId, e);
            return "❌ 转换失败：" + e.getMessage();
        }
    }
}
