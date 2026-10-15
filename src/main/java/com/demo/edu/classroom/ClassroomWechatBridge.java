package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 单人工作台的微信入口；配对只绑定一个发送者，不把所有来信共享为同一学习者。 */
@Service
public class ClassroomWechatBridge {
    private final ClassroomCourseService courses;
    private final ObjectMapper mapper;
    private final Path stateFile;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private String sender = "";
    private String baseUrl = "";
    private String selectedCourse = "";
    private String pairingCode = "";
    private long pairingExpiresAt;

    @Autowired
    public ClassroomWechatBridge(ClassroomCourseService courses, ObjectMapper mapper,
            @Value("${classroom.data-dir:data/classroom}") String dataDir) {
        this(courses, mapper, Path.of(dataDir), Clock.systemUTC());
    }

    ClassroomWechatBridge(ClassroomCourseService courses, ObjectMapper mapper, Path dataDir, Clock clock) {
        this.courses = courses;
        this.mapper = mapper;
        this.stateFile = dataDir.resolve("wechat.json").toAbsolutePath().normalize();
        this.clock = clock;
        if (Files.exists(stateFile)) {
            try {
                var saved = mapper.readTree(Files.readString(stateFile));
                sender = saved.path("sender").asText("");
                baseUrl = saved.path("baseUrl").asText("");
                selectedCourse = saved.path("selectedCourse").asText("");
            } catch (IOException e) {
                throw new IllegalStateException("课堂微信绑定文件无法读取，请检查 data/classroom/wechat.json", e);
            }
        }
    }

    public synchronized Map<String, Object> status() {
        return Map.of("bound", !sender.isBlank(), "baseUrl", baseUrl);
    }

    /** 只有主动点击配对才签发短期代码；重新配对前，原绑定继续有效。 */
    public synchronized Map<String, Object> createPairing(String publicBaseUrl) {
        baseUrl = validateBaseUrl(publicBaseUrl);
        byte[] bytes = new byte[5];
        random.nextBytes(bytes);
        pairingCode = HexFormat.of().formatHex(bytes).toUpperCase(java.util.Locale.ROOT);
        pairingExpiresAt = clock.millis() + 300_000;
        return Map.of("code", pairingCode, "expiresInSeconds", 300);
    }

    public static boolean isClassroomCommand(String text) {
        if (text == null) return false;
        String value = text.trim();
        return value.equals("课堂帮助") || value.equals("我的课堂") || value.equals("继续学习")
                || value.startsWith("绑定课堂 ") || value.startsWith("课堂提问 ")
                || value.startsWith("学习课程 ");
    }

    /** 未命中课堂命令返回 empty，由原 CmdService 继续处理，避免接管普通聊天。 */
    public Optional<String> handle(String input, String fromUser) {
        if (!isClassroomCommand(input)) return Optional.empty();
        String text = input.trim();
        if (text.equals("课堂帮助")) {
            return Optional.of("在网页课堂点击「连接微信」，再向机器人发送「绑定课堂 配对码」。\n"
                    + "配对后可用：我的课堂、继续学习、学习课程 课程ID、课堂提问 你的问题。");
        }
        if (text.startsWith("绑定课堂 ")) return Optional.of(bind(text.substring(5).trim(), fromUser));
        synchronized (this) {
            if (fromUser == null || sender.isBlank() || !sender.equals(fromUser)) {
                return Optional.of("请先在网页课堂点击「连接微信」，使用配对码绑定后再查看课程。");
            }
        }
        try {
            if (text.equals("我的课堂")) return Optional.of(listCourses());
            if (text.startsWith("学习课程 ")) {
                String id = text.substring(5).trim();
                ObjectNode course = courses.get(id);
                synchronized (this) { selectedCourse = id; save(); }
                return Optional.of(courseLink(course));
            }
            ObjectNode course = currentCourse();
            if (course == null) return Optional.of("还没有课程，请先在网页课堂输入主题或上传材料，生成第一节课。");
            if (text.equals("继续学习")) return Optional.of(courseLink(course));
            String question = text.substring("课堂提问 ".length()).trim();
            if (question.isBlank() || question.length() > 2000) return Optional.of("请在「课堂提问」后填写 1–2000 字的问题。");
            int sceneIndex = course.path("progress").path("sceneIndex").asInt(0);
            var scenes = course.path("scenes");
            if (!scenes.isArray() || scenes.isEmpty()) return Optional.of("课程内容还未生成，请先在网页确认大纲并生成课程。");
            sceneIndex = Math.max(0, Math.min(sceneIndex, scenes.size() - 1));
            String sceneId = scenes.get(sceneIndex).path("id").asText();
            var reply = courses.chat(course.path("id").asText(),
                    new ClassroomCourseService.ChatRequest(sceneId, question, "teacher"));
            return Optional.of(reply.path("text").asText());
        } catch (IllegalArgumentException e) {
            return Optional.of("没有找到这节课程，请发送「我的课堂」重新选择。");
        } catch (RuntimeException e) {
            // 不把供应商响应或数据库异常回传到微信；网页保留具体任务状态。
            return Optional.of("课堂暂时无法处理这次请求，请在网页查看课程状态后重试。");
        }
    }

    private synchronized String bind(String code, String fromUser) {
        if (fromUser == null || fromUser.isBlank()) return "无法识别发送者，请从微信发送配对命令。";
        boolean matches = !pairingCode.isBlank() && clock.millis() < pairingExpiresAt
                && MessageDigest.isEqual(pairingCode.getBytes(StandardCharsets.UTF_8),
                code.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        if (!matches) return "配对码无效或已过期，请回网页重新生成。";
        String previousSender = sender;
        sender = fromUser;
        try { save(); }
        catch (RuntimeException e) { sender = previousSender; return "绑定未能保存，请检查服务端存储后重试。"; }
        pairingCode = "";
        pairingExpiresAt = 0;
        return "课堂已连接。发送「我的课堂」查看课程，或发送「继续学习」打开上次的课程。";
    }

    private String listCourses() {
        List<ObjectNode> list = courses.list();
        if (list.isEmpty()) return "还没有课程，请先在网页课堂创建第一节课。";
        StringBuilder result = new StringBuilder("最近的课堂：\n");
        for (ObjectNode course : list.stream().limit(5).toList()) {
            result.append(courseLink(course)).append("\n课程ID：").append(course.path("id").asText()).append("\n\n");
        }
        return result.toString().trim();
    }

    private ObjectNode currentCourse() {
        String selected;
        synchronized (this) { selected = selectedCourse; }
        if (!selected.isBlank()) {
            try { return courses.get(selected); }
            catch (ClassroomException missing) { if (missing.status() != 404) throw missing; }
            catch (IllegalArgumentException ignored) { /* 已删课程回到最近列表。 */ }
        }
        List<ObjectNode> list = courses.list();
        return list.isEmpty() ? null : courses.get(list.getFirst().path("id").asText());
    }

    private synchronized String courseLink(ObjectNode course) {
        String title = course.path("title").asText("学习课程");
        if (baseUrl.isBlank()) return "《" + title + "》\n请在网页课堂打开。";
        return "《" + title + "》\n" + baseUrl + "/#/classroom/" + course.path("id").asText()
                + "\n手机与电脑连接同一 Wi-Fi 后打开。";
    }

    static String validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            return uri.toString().replaceAll("/$", "");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("请从有效的 HTTP/HTTPS 网页入口生成配对码");
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(stateFile.getParent());
            Path pending = Files.createTempFile(stateFile.getParent(), "wechat-", ".tmp");
            try {
                Files.writeString(pending, mapper.writeValueAsString(Map.of("sender", sender,
                        "baseUrl", baseUrl, "selectedCourse", selectedCourse)));
                try { Files.move(pending, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(pending, stateFile, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(pending); }
        } catch (IOException e) {
            throw new IllegalStateException("课堂微信绑定保存失败", e);
        }
    }
}
