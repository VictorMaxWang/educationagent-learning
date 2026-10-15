package com.demo.agent;

import com.demo.service.tools.FileSystemService;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D 盘文件操作工具。
 * 两步流程（同一个工具调两次）：
 * ① 不传 verificationCode → 路径校验 + 生成验证码（控制台打印）
 * ② 传入 verificationCode → 验证通过后真正执行 IO
 */
@Slf4j
@Component
public class FileSystemTool {

    private final FileSystemService fileSystemService;

    public FileSystemTool(FileSystemService fileSystemService) {
        this.fileSystemService = fileSystemService;
    }

    private String userId() {
        String uid = VoiceContext.getUserId();
        if (uid == null) {
            log.warn("VoiceContext 未设置 userId");
        }
        return uid;
    }

    @Tool("列出 D 盘目录内容。两步流程：不传验证码→生成验证码；传验证码→真正执行")
    public String listDirectory(
            @P("目录路径，例如 D:\\ 或 D:\\我的文件") String path,
            @P(value = "验证码，不传则自动生成", required = false) String code) {
        log.info("📁 listDirectory path={}", path);
        if (isEmpty(code)) return genCode("list", path, null);
        return execCode(code);
    }

    @Tool("读取 D 盘文件内容（支持 TXT/PDF/Word/Excel/PPT/代码等格式）。两步流程：不传验证码→生成验证码；传验证码→真正执行")
    public String readFile(
            @P("文件路径，例如 D:\\report.txt") String path,
            @P(value = "验证码，不传则自动生成", required = false) String code) {
        log.info("📖 readFile path={}", path);
        if (isEmpty(code)) return genCode("read", path, null);
        return execCode(code);
    }

    @Tool("在 D 盘创建文件或覆盖写入文件。两步流程：不传验证码→生成验证码；传验证码→真正执行")
    public String writeFile(
            @P("文件路径，例如 D:\\test.txt") String path,
            @P("文件内容") String content,
            @P(value = "验证码，不传则自动生成", required = false) String code) {
        log.info("✏️ writeFile path={}", path);
        if (isEmpty(code)) return genCode("write", path, content);
        return execCode(code);
    }

    @Tool("在 D 盘创建目录。两步流程：不传验证码→生成验证码；传验证码→真正创建")
    public String createDirectory(
            @P("目录路径，例如 D:\\newFolder") String path,
            @P(value = "验证码，不传则自动生成", required = false) String code) {
        log.info("📂 createDirectory path={}", path);
        if (isEmpty(code)) return genCode("mkdir", path, null);
        return execCode(code);
    }

    @Tool("删除 D 盘文件或空目录。两步流程：不传验证码→生成验证码；传验证码→真正删除")
    public String deleteFile(
            @P("文件或目录路径，例如 D:\\old.txt") String path,
            @P(value = "验证码，不传则自动生成", required = false) String code) {
        log.info("🗑️ deleteFile path={}", path);
        if (isEmpty(code)) return genCode("delete", path, null);
        return execCode(code);
    }


    private boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    // 第 1 步：校验路径 + 生成验证码
    private String genCode(String op, String path, String content) {
        String uid = userId();
        if (uid == null) return "操作失败，请重试。";
        try {
            String norm = fileSystemService.normalizeAndValidate(path);
            fileSystemService.generateVerificationCode(uid, op, norm, content);
            return "请在控制台查看验证码，然后发送验证码给我执行操作。";
        } catch (IllegalArgumentException e) {
            return "路径错误：" + e.getMessage();
        }
    }

    // 第 2 步：验证码确认后执行真正的 IO
    private String execCode(String code) {
        String uid = userId();
        if (uid == null) return "验证失败。";
        return fileSystemService.executePendingOp(code, uid);
    }
}
