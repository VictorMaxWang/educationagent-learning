package com.demo.agent;

import com.demo.fileconvert.FileConvertOutputHolder;
import com.demo.service.tools.FileGenService;
import com.demo.service.tools.holder.UserFileHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 办公文档生成。
 * <p>
 * 生成 docx/xlsx/csv/txt 文件，保存到 D 盘固定目录，并把产物写入 {@link FileConvertOutputHolder}，
 * 由发送层自动发给用户（微信 client.sendFile / 桌面 {file} 事件）。产物不进 LLM 上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileGenTool {

    private final FileGenService fileGenService;
    private final FileConvertOutputHolder fileConvertOutputHolder;
    private final UserFileHolder userFileHolder;

    @Tool("办公文档生成。按用户要求生成本地文件：Word(.docx)、Excel(.xlsx)、CSV、TXT，并自动发送给用户。"
            + "用户说「生成Word」「写个文档」「生成Excel表格」「导出表格」「生成CSV」「生成txt文件」「把这串数据做成表格」时调用。"
            + "format 取值：word/docx=Word文档，excel/xlsx=Excel表格，csv=CSV，txt=纯文本。"
            + "有表格数据时，tableData 必须传 JSON，格式：{\"columns\":[\"列1\",\"列2\"],\"rows\":[[\"值1\",\"值2\"]]}。"
            + "openFolder：已不再弹出保存文件夹，文件静默保存到固定目录并返回完整路径（该参数仅兼容保留，忽略取值）。"
            + "注意：本工具生成的是本地文件直接发送；「转换已有文件」用 convertFile。")
    public String generateFile(
            @P(value = "文件格式：word或docx=Word文档，excel或xlsx=Excel表格，csv=CSV，txt=纯文本", required = true) String format,
            @P(value = "文档标题（可选，Word 用作标题，Excel 用作 sheet 名）", required = false) String title,
            @P(value = "正文内容，多段用换行分隔（可选，与表格数据可并存）", required = false) String content,
            @P(value = "表格数据 JSON（可选），格式：{\"columns\":[\"列1\",\"列2\"],\"rows\":[[\"值1\",\"值2\"]]}，用于 Excel/Word 内嵌表格", required = false) String tableData,
            @P(value = "文件名（可选，不含扩展名；缺省用标题或自动生成）", required = false) String fileName,
            @P(value = "生成后是否打开保存文件夹（已失效：不再弹出文件夹，文件静默保存并返回路径；参数保留兼容）", required = false) String openFolder) {

        String userId = com.demo.util.VoiceContext.getUserId();
        if (userId == null) userId = "default";
        log.info("📄 [{}] Agent 调用 generateFile: format={} title={}", userId, format, title);
        try {
            FileGenService.GenResult result = fileGenService.generate(format, title, content, tableData, fileName);
            // saved=true：文件已落盘固定目录，桌面端不再触发下载对话框（微信端仍会 client.sendFile 发送）
            fileConvertOutputHolder.set(result.bytes(), result.fileName(), true);
            // 生成的 xlsx 注册进 UserFileHolder（用户文件缓存，支持连发多条消息场景），保持原逻辑不动
            if (result.fileName().toLowerCase().endsWith(".xlsx")) {
                userFileHolder.put(userId, result.bytes(), result.fileName());
                log.info("📊 [{}] 生成的 xlsx 已注册到 UserFileHolder，可接 generateChart", userId);
            }
            // 静默保存：不再弹出本地文件夹，直接返回文件存放位置
            return "✅ 文件已生成：" + result.fileName() + "，已保存到 " + result.absolutePath();
        } catch (IllegalArgumentException e) {
            return "⛔ " + e.getMessage();
        } catch (Exception e) {
            log.error("❌ [{}] 文件生成失败", userId, e);
            return "❌ 文件生成失败：" + e.getMessage();
        }
    }
}
