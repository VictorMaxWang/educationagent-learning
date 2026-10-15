package com.demo.agent;

import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.McpClientManager;
import com.demo.service.tools.ImageGenService;
import com.demo.service.tools.holder.UserImageHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小红书一键发布工具。
 * <p>
 * 解决核心问题：xiaohongshu-mcp 的 {@code xhs_publish_content} 不接收内存二进制流 /
 * 微信临时文件流，只认本地文件路径。因此本工具把用户图片字节统一写入本地临时文件，
 * 再把路径串（逗号分隔）传给 MCP 工具。
 *
 * <pre>
 * 流程：检查登录 → 收集图片（用户最近发的多张，最多等 10s 收齐）→ 写临时文件 → 调 xhs_publish_content
 * </pre>
 */
@Slf4j
@Component
public class XhsPublishMcpTool {

    /** 临时图片目录 */
    private static final String PUBLISH_DIR = "data/xhs_publish";

    /** 小红书图文单篇图片上限 */
    private static final int MAX_IMAGES = 9;

    /** 等待收集图片的最长时间（毫秒） */
    private static final long WAIT_MAX_MS = 10_000;

    /** 连续无新图即发布（毫秒） */
    private static final long WAIT_QUIET_MS = 3_000;

    private static final String TOOL_PROFILE = "xhs_get_my_profile";
    private static final String TOOL_PUBLISH = "xhs_publish_content";
    private static final String TOOL_DELETE_COOKIES = "xhs_delete_cookies";
    private static final String TOOL_GET_QRCODE = "xhs_get_login_qrcode";

    /** 登录探测结果状态 */
    public enum LoginProbe {
        /** 已登录（get_my_profile 返回了账号主页） */
        LOGGED_IN,
        /** 明确未登录（返回里含未登录字样） */
        NOT_LOGGED_IN,
        /** 探测失败/超时/返回不可识别（不能下结论） */
        UNKNOWN
    }

    /** 登录探测结果：状态 + 原始返回 + 已登录时的昵称 */
    public record LoginProbeResult(LoginProbe state, String raw, String nickname) {}

    @Resource
    private McpClientManager mcpClientManager;

    @Resource
    private UserImageHolder userImageHolder;

    @Resource
    private ImageGenService imageGenService;

    /**
     * 一键发布小红书图文笔记。
     *
     * @param title  标题（LLM 生成，可带 emoji）
     * @param desc   正文（LLM 生成）
     * @param tags   话题标签，多个用空格分隔（如 "#旅行 #美食"）
     * @param images 图片路径，多个用英文逗号分隔；一般留空，自动使用用户最近发送的图片
     * @return 发布结果
     */
    @Tool("一键发布小红书图文笔记。当用户要求发小红书、发布笔记、发帖到小红书时调用。"
            + "自动检查登录、收集用户最近发送的图片（支持多张）、写入临时文件并发布。")
    public String publishXhsNote(
            @P("小红书笔记标题，简洁有吸引力，可带 emoji") String title,
            @P("小红书笔记正文，一段话即可，语气轻松真实") String desc,
            @P("话题标签，多个用空格分隔，如 '#旅行 #美食'") String tags,
            @P("图片路径，多个用英文逗号分隔；一般留空，自动使用用户最近发送的图片") String images) {

        try {
            // 1. 检查登录状态（改走 get_my_profile 探测；check_login_status 在服务端固定 ~30s 超时）
            LoginProbeResult loginProbe = probeLogin();
            if (loginProbe.state() == LoginProbe.NOT_LOGGED_IN) {
                return "❌ 小红书未登录，请先重新登录并更新 Cookie。\n登录检查返回：" + loginProbe.raw();
            }

            // 2. 收集图片路径（优先传入路径 → 用户最近图片 → 自动生成配图）
            List<String> imagePaths = resolveImages(images, desc);
            if (imagePaths.isEmpty()) {
                return "❌ 没有可用图片。请先发送要发布的图片（支持多张），或稍后重试（自动配图可能生成中失败）。";
            }
            if (imagePaths.size() > MAX_IMAGES) {
                return "❌ 图片数量 " + imagePaths.size() + " 超过小红书单篇上限 " + MAX_IMAGES + " 张。";
            }

            // 3. 调发布工具（xhs_publish_content 要求原生数组：images: string[], tags: string[]）
            Map<String, Object> args = new HashMap<>();
            args.put("images", imagePaths);                     // List<String> → JSON 数组
            args.put("title", title != null ? title : "");
            args.put("content", desc != null ? desc : "");      // 笔记正文字段名是 content
            args.put("tags", parseTags(tags));                  // List<String>
            log.info("📕 调 xhs_publish_content：{} 张图，title={}，tags={}", imagePaths.size(), title, args.get("tags"));

            String result = mcpClientManager.callTool(TOOL_PUBLISH, args);

            // 4. 发布成功 → 清理已用图片，避免重复发布
            if (isPublishSuccess(result)) {
                userImageHolder.clear();
            }
            return "📕 发布结果：\n" + result;
        } catch (Exception e) {
            log.error("发布小红书异常", e);
            return "🚫 发布小红书失败：" + e.getMessage();
        }
    }

    /**
     * 查询小红书登录状态（桌面端「生活企划 → 小红书」用）。
     *
     * @return {loggedIn: boolean, result: 描述}
     */
    public Map<String, Object> loginStatus() {
        Map<String, Object> out = new HashMap<>();
        LoginProbeResult probe = probeLogin();
        switch (probe.state()) {
            case LOGGED_IN -> {
                String nick = probe.nickname();
                out.put("loggedIn", true);
                out.put("result", "已登录" + (nick.isEmpty() ? "" : "（" + nick + "）"));
            }
            case NOT_LOGGED_IN -> {
                out.put("loggedIn", false);
                out.put("result", "未登录，请先重新登录并更新 Cookie");
            }
            default -> {
                out.put("loggedIn", false);
                out.put("result", "登录检查失败：" + (probe.raw().isBlank() ? "无返回" : probe.raw()));
            }
        }
        return out;
    }

    /**
     * 清除 MCP 服务端缓存的登录 Cookie（换账号时需先清掉旧登录态）。
     *
     * @return MCP 原始返回
     */
    public String deleteCookies() {
        try {
            return mcpClientManager.callTool(TOOL_DELETE_COOKIES, new HashMap<>());
        } catch (Exception e) {
            log.error("清除小红书 Cookie 异常", e);
            return "🚫 清除失败：" + e.getMessage();
        }
    }

    /**
     * 获取小红书登录二维码（Base64 图片），用于清 cookie 后扫码重新登录。
     *
     * @return MCP 原始返回（含 Base64 二维码 + 超时时间）
     */
    public String getLoginQrcode() {
        try {
            return mcpClientManager.callTool(TOOL_GET_QRCODE, new HashMap<>());
        } catch (Exception e) {
            log.error("获取小红书登录二维码异常", e);
            return "🚫 获取失败：" + e.getMessage();
        }
    }

    /**
     * 将桌面端上传的 base64 图片写入临时目录（浏览器 dev 兜底，MCP 只认本地路径）。
     *
     * @param base64List 图片 base64（可带 data: 前缀）
     * @return 已写入的图片绝对路径列表；失败条目跳过
     */
    public List<String> saveBase64Images(List<String> base64List) {
        List<String> paths = new ArrayList<>();
        if (base64List == null || base64List.isEmpty()) return paths;
        try {
            Path dir = Path.of(PUBLISH_DIR);
            Files.createDirectories(dir);
            int i = 1;
            for (String b64 : base64List) {
                if (b64 == null || b64.isBlank()) continue;
                String clean = b64.trim();
                int comma = clean.indexOf(',');
                if (clean.startsWith("data:") && comma > 0) clean = clean.substring(comma + 1);
                try {
                    byte[] bytes = Base64.getDecoder().decode(clean);
                    if (bytes.length == 0) continue;
                    // 按魔数写真实扩展名，避免 PNG/WebP 内容被冠以 .jpg 导致 MCP 拒收
                    Path file = dir.resolve("xhs_desktop_" + System.currentTimeMillis() + "_" + (i++) + guessImageExt(bytes));
                    Files.write(file, bytes);
                    paths.add(toServerPath(file.toAbsolutePath().toString()));
                } catch (IllegalArgumentException e) {
                    log.warn("📕 base64 解码失败，跳过一张桌面端图片：{}", e.getMessage());
                }
            }
            log.info("📕 桌面端暂存 {} 张图片到 {}", paths.size(), dir.toAbsolutePath());
        } catch (Exception e) {
            log.error("📕 写入桌面端图片临时文件失败", e);
        }
        return paths;
    }

    /** 统一转正斜杠：MCP 服务器只认 POSIX 格式绝对路径（Windows 下反斜杠会被拒收） */
    private String toServerPath(String path) {
        return path.replace('\\', '/');
    }

    /** 按文件魔数推断图片扩展名（.png/.jpg/.gif/.webp，未知默认 .jpg） */
    private String guessImageExt(byte[] bytes) {
        if (bytes.length >= 4) {
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return ".png";
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return ".jpg";
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return ".gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return ".webp";
        return ".jpg";
    }

    // ======================== 内部方法 ========================

    /** 解析图片：优先用传入路径；否则从 UserImageHolder 取多张；都没有则通义万相自动生成 3 张 */
    private List<String> resolveImages(String images, String desc) {
        List<String> paths = new ArrayList<>();
        if (images != null && !images.isBlank()) {
            for (String p : images.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) paths.add(t);
            }
            if (!paths.isEmpty()) {
                log.info("📕 使用 LLM 传入图片路径：{}", paths);
                return paths;
            }
        }

        // 等待最多 10s 收齐用户最近发送的图片（3s 无新图即发布）
        List<byte[]> pics = waitForImages();
        if (pics.isEmpty()) {
            // 优先级 ③：通义万相自动生成 3 张配图
            pics = generateFallbackImages(desc);
            if (pics.isEmpty()) return paths;
        }

        try {
            Path dir = Path.of(PUBLISH_DIR);
            Files.createDirectories(dir);
            int i = 1;
            for (byte[] bytes : pics) {
                Path file = dir.resolve("xhs_" + System.currentTimeMillis() + "_" + (i++) + ".jpg");
                Files.write(file, bytes);
                paths.add(file.toAbsolutePath().toString());
            }
            log.info("📕 已写入 {} 张图片到临时目录 {}", paths.size(), dir.toAbsolutePath());
        } catch (Exception e) {
            log.error("写入图片临时文件失败", e);
        }
        return paths;
    }

    /** 解析话题标签：LLM 传入的字符串（"旅行 美食" / "#旅行,美食"）→ 去 # 的字符串数组 */
    private List<String> parseTags(String tags) {
        List<String> out = new ArrayList<>();
        if (tags == null || tags.isBlank()) return out;
        for (String t : tags.split("[\\s,，、]+")) {
            String clean = t.trim();
            if (clean.isEmpty() || "#".equals(clean)) continue;
            out.add(clean.startsWith("#") ? clean.substring(1) : clean);
        }
        return out;
    }

    /** 通义万相自动生成 3 张配图（无用户图片时的兜底，单张约 60s） */
    private List<byte[]> generateFallbackImages(String desc) {
        List<byte[]> imgs = new ArrayList<>();
        String topic = (desc != null && !desc.isBlank())
                ? desc.substring(0, Math.min(desc.length(), 30)).trim()
                : "旅行风景";
        String[] styles = { "高清写实摄影", "治愈系插画风格", "ins 风氛围海报" };
        for (String style : styles) {
            if (imgs.size() >= MAX_IMAGES) break;
            try {
                byte[] img = imageGenService.generate("小红书笔记配图：" + topic + "，" + style + "，竖构图");
                if (img != null && img.length > 0) {
                    imgs.add(img);
                    log.info("📕 自动配图第 {} 张生成成功", imgs.size());
                }
            } catch (Exception e) {
                log.warn("📕 自动配图失败（第 {} 张）：{}", imgs.size() + 1, e.getMessage());
            }
        }
        return imgs;
    }

    /** 轮询等待图片到齐：最多 10s，连续 3s 无新图返回 */
    private List<byte[]> waitForImages() {
        long start = System.currentTimeMillis();
        long lastSize = -1;
        long lastChange = System.currentTimeMillis();
        List<byte[]> last = List.of();
        while (System.currentTimeMillis() - start < WAIT_MAX_MS) {
            List<byte[]> cur = userImageHolder.getAllImages();
            if (cur.size() != lastSize) {
                lastSize = cur.size();
                lastChange = System.currentTimeMillis();
                last = cur;
            }
            if (lastSize > 0 && System.currentTimeMillis() - lastChange >= WAIT_QUIET_MS) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last.isEmpty()) last = userImageHolder.getAllImages();
        log.info("📕 图片收集完成：{} 张", last.size());
        return last;
    }

    /**
     * 探测小红书登录状态（改走 get_my_profile）。
     * <p>
     * 原因：xhs_check_login_status 在服务端内部固定 ~30s 超时报「context deadline exceeded」
     * （服务端是编译好的二进制，无源码无法直接修复）；get_my_profile 实测可正常返回当前账号
     * 主页 → 以此作为登录探测，并顺带拿到昵称。
     */
    public LoginProbeResult probeLogin() {
        try {
            String raw = mcpClientManager.callTool(TOOL_PROFILE, new HashMap<>());
            LoginProbe state = parseLogin(raw);
            String nickname = state == LoginProbe.LOGGED_IN ? extractNickname(raw) : "";
            return new LoginProbeResult(state, raw != null ? raw : "", nickname);
        } catch (Exception e) {
            log.warn("📕 登录探测异常，视为 UNKNOWN: {}", e.getMessage());
            return new LoginProbeResult(LoginProbe.UNKNOWN, "检查异常：" + e.getMessage(), "");
        }
    }

    /** 解析 get_my_profile 返回：含账号主页 JSON → 已登录；明确未登录字样 → 未登录；其余（内部错误/超时）→ 未知 */
    private LoginProbe parseLogin(String raw) {
        if (raw == null || raw.isBlank()) return LoginProbe.UNKNOWN;
        String r = raw.toLowerCase();
        // 成功返回账号主页 JSON
        if (r.contains("userbasicinfo") || r.contains("\"nickname\"") || r.contains("redid")) {
            return LoginProbe.LOGGED_IN;
        }
        // 明确未登录
        if (r.contains("未登录") || r.contains("未登") || r.contains("not_log")
                || r.contains("not-log") || r.contains("login required") || r.contains("请先登录")) {
            return LoginProbe.NOT_LOGGED_IN;
        }
        return LoginProbe.UNKNOWN;
    }

    /** 从 get_my_profile 返回中提取昵称（失败返回空串） */
    private String extractNickname(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            JSONObject root = JSONObject.parseObject(raw);
            JSONObject basic = root != null ? root.getJSONObject("userBasicInfo") : null;
            String nick = basic != null ? basic.getString("nickname") : null;
            return nick != null && !nick.isBlank() ? nick : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 判断发布结果是否成功 */
    private boolean isPublishSuccess(String result) {
        if (result == null) return false;
        return result.contains("成功") || result.contains("发布完成")
                || result.contains("success") || result.contains("已发布");
    }
}
