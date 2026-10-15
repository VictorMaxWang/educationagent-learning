package com.demo.service.tools;

import com.demo.service.preprocessing.FileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * D 盘文件操作服务。
 * 职责：路径校验、验证码管理、真实文件 I/O。
 */
@Slf4j
@Service
public class FileSystemService {

    @Value("${file.max-content-length:2000}")
    private int maxContentLength;

    @Resource
    private FileService fileService;

    private static final Set<String> BLOCKED_EXTS = Set.of(
            ".exe", ".bat", ".cmd", ".vbs", ".ps1", ".psm1", ".dll",
            ".sys", ".com", ".scr", ".msi", ".vbe", ".jse", ".wsf", ".wsh");

    private static final long EXPIRY_MS = 2 * 60 * 1000L;

    // 验证码 → 待执行的操作
    private final ConcurrentHashMap<String, PendingOp> ops = new ConcurrentHashMap<>();

    // 路径校验
    public String normalizeAndValidate(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) throw new IllegalArgumentException("路径不能为空");

        String raw = pathStr.trim();
        if ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))
            raw = raw.substring(1, raw.length() - 1);

        String norm = raw.replace('/', '\\');
        if (norm.matches("^[a-zA-Z]:[^\\\\].*$"))
            norm = norm.charAt(0) + ":\\" + norm.substring(2);

        Path path = Path.of(norm).toAbsolutePath().normalize();
        String abs = path.toString();
        if (!abs.toUpperCase().startsWith("D:\\"))
            throw new IllegalArgumentException("路径必须在 D 盘：" + abs);
        return abs;
    }

    // 校验文件后缀，拦截禁止操作的可执行类文件
    private void checkExt(String path) {
        String lower = path.toLowerCase();
        for (String ext : BLOCKED_EXTS)
            if (lower.endsWith(ext))
                throw new IllegalArgumentException("禁止操作可执行文件：" + ext);
    }

    // 生成验证码
    public String generateVerificationCode(String userId, String opType, String path, String content) {
        evictOld();
        String code;
        do { code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000)); }
        while (ops.containsKey(code));

        ops.put(code, new PendingOp(userId, opType, path, content, System.currentTimeMillis()));

        String label = switch (opType) {
            case "read" -> "读取文件"; case "write" -> "写入文件"; case "delete" -> "删除文件";
            case "list" -> "列出目录"; case "mkdir" -> "创建目录"; default -> opType;
        };

        System.out.println("\n" + "═".repeat(55));
        System.out.println("  🔐 文件操作待确认");
        System.out.println("     操作用户：" + userId);
        System.out.println("     操作类型：" + label);
        System.out.println("     操作路径：" + path);
        System.out.println("     验证码  ：" + code);
        System.out.println("     有效期  ：2 分钟");
        System.out.println("═".repeat(55) + "\n");
        log.info("验证码已生成 [{}] {} {} → {}", userId, opType, path, code);
        return code;
    }

    // 判断验证码是否有效
    public String executePendingOp(String code, String userId) {
        if (code == null || code.isBlank()) return "验证码不能为空。";

        PendingOp op = ops.get(code);
        if (op == null) return "验证码无效或已过期。";
        if (!op.userId.equals(userId)) return "验证码不属于当前用户。";
        if (System.currentTimeMillis() - op.createdAt > EXPIRY_MS) {
            ops.remove(code);
            return "验证码已过期（2 分钟有效）。";
        }

        ops.remove(code);
        log.info("验证码通过 [{}] {} {}", userId, op.opType, op.path);

        return switch (op.opType) {
            case "read"   -> doRead(op.path);
            case "write"  -> doWrite(op.path, op.content);
            case "delete" -> doDelete(op.path);
            case "list"   -> doList(op.path);
            case "mkdir"  -> doMkdir(op.path);
            default       -> "未知操作：" + op.opType;
        };
    }

    // 清理超时失效的文件操作验证码记录
    private void evictOld() {
        long now = System.currentTimeMillis();
        ops.values().removeIf(o -> now - o.createdAt > EXPIRY_MS);
    }

    // 读文件，解析并提取文件内容
    private String doRead(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p))   return "文件不存在：" + path;
        if (!Files.isRegularFile(p)) return "不是文件：" + path;
        try {
            if (Files.size(p) > 10 * 1024 * 1024) return "文件超过 10MB，无法读取。";
            // 读取磁盘文件全部二进制数据
            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length == 0) return "文件为空：" + path;

            String fmt = fileService.analyze(bytes);
            fmt = (fmt != null) ? fmt : "未知格式";
            // 提取文件里面文字内容
            String txt = fileService.extractContent(bytes);
            if (txt != null) {
                if (txt.length() > maxContentLength)
                    txt = txt.substring(0, maxContentLength) + "\n...（截断）";
                return "✅ " + fmt + "（" + path + "）\n\n" + txt;
            }
            return "✅ " + fmt + "（" + path + "），" + bytes.length + " 字节\n（不支持内容预览）";
        } catch (IOException e) {
            log.error("读取失败 {}", path, e);
            return "读取失败：" + e.getMessage();
        }
    }

    // 创建文件:执行创建/写入文件逻辑
    private String doWrite(String path, String content) {
        checkExt(path);
        Path p = Path.of(path);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent))
            return "父目录不存在：" + parent;
        try {
            Files.writeString(p, content != null ? content : "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            long size = Files.size(p);
            return "✅ 文件已" + (content != null && !content.isEmpty() ? "写入" : "创建") + "：" + path + "（" + size + " 字节）";
        } catch (IOException e) {
            return "写入失败：" + e.getMessage();
        }
    }

    // 删除文件
    private String doDelete(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p)) return "路径不存在：" + path;
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> list = Files.list(p)) {
                    if (list.findAny().isPresent()) return "目录不为空，无法删除。";
                }
                Files.delete(p);
                return "✅ 空目录已删除：" + path;
            } else {
                checkExt(path);
                Files.delete(p);
                return "✅ 文件已删除：" + path;
            }
        } catch (IOException e) {
            return "删除失败：" + e.getMessage();
        }
    }

    // 查看文件夹:遍历并列出目录下所有文件与子文件夹
    private String doList(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p)) return "路径不存在：" + path;
        if (!Files.isDirectory(p)) return "不是目录：" + path;
        try (Stream<Path> entries = Files.list(p)) {
            var list = entries.sorted().toList();
            if (list.isEmpty()) return "📁 空目录：" + path;
            StringBuilder sb = new StringBuilder("📁 ").append(path).append("（").append(list.size()).append(" 项）：\n");
            for (Path e : list) {
                String name = e.getFileName().toString();
                try {
                    BasicFileAttributes a = Files.readAttributes(e, BasicFileAttributes.class);
                    if (a.isDirectory()) sb.append("  📂 ").append(name).append("/\n");
                    else {
                        long sz = a.size();
                        String s = sz < 1024 ? sz + " B" : sz < 1048576 ? (sz / 1024) + " KB" : String.format("%.1f MB", sz / 1048576.0);
                        sb.append("  📄 ").append(name).append(" (").append(s).append(")\n");
                    }
                } catch (IOException ignored) {
                    sb.append("  ❓ ").append(name).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "列出目录失败：" + e.getMessage();
        }
    }

    // 创建文件夹：创建多级文件夹目录
    private String doMkdir(String path) {
        Path p = Path.of(path);
        if (Files.exists(p)) return "路径已存在：" + path;
        try {
            Files.createDirectories(p);
            log.info("目录已创建：{}", path);
            return "✅ 目录已创建：" + path;
        } catch (IOException e) {
            log.error("创建目录失败 {}", path, e);
            return "创建目录失败：" + e.getMessage();
        }
    }

    private record PendingOp(String userId, String opType, String path, String content, long createdAt) {}
}
