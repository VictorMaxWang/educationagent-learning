package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.NetworkInterface;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;

/** 单人工作台的微信入口；配对只绑定一个发送者，不把所有来信共享为同一学习者。 */
@Service
public class ClassroomWechatBridge {
    private final ClassroomCourseService courses;
    private final ObjectMapper mapper;
    private final Path stateFile;
    private final Clock clock;
    private final String configuredBaseUrl;
    private final String configuredUrlError;
    private final Predicate<String> localAddress;
    private final SecureRandom random = new SecureRandom();
    private String sender = "";
    private String baseUrl = "";
    private String selectedCourse = "";
    private String pairingCode = "";
    private String pairingBaseUrl = "";
    private long pairingExpiresAt;

    @Autowired
    public ClassroomWechatBridge(ClassroomCourseService courses, ObjectMapper mapper,
            @Value("${classroom.data-dir:data/classroom}") String dataDir,
            @Value("${classroom.public-base-url:${CLASSROOM_PUBLIC_BASE_URL:}}") String publicBaseUrl) {
        this(courses, mapper, Path.of(dataDir), Clock.systemUTC(), publicBaseUrl, ClassroomWechatBridge::isLocalLanAddress);
    }

    ClassroomWechatBridge(ClassroomCourseService courses, ObjectMapper mapper, Path dataDir, Clock clock) {
        this(courses, mapper, dataDir, clock, "", ClassroomWechatBridge::isLocalLanAddress);
    }

    /** publicBaseUrl是本后端的部署入口声明；只能由服务器配置，不能由客户端将本机课程冒充已发布。 */
    ClassroomWechatBridge(ClassroomCourseService courses, ObjectMapper mapper, Path dataDir, Clock clock,
                          String publicBaseUrl, Predicate<String> localAddress) {
        this.courses = courses;
        this.mapper = mapper;
        this.stateFile = dataDir.resolve("wechat.json").toAbsolutePath().normalize();
        this.clock = clock;
        this.localAddress = localAddress;
        String configured = "", configurationError = "";
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            try { configured = validateBaseUrl(publicBaseUrl); }
            catch (IllegalArgumentException invalid) {
                // 配置失败只停用分享链接，不拖垮课堂启动；也不回退到旧绑定中的localhost或客户端地址。
                configurationError = "服务端课堂网址配置无效，请检查 CLASSROOM_PUBLIC_BASE_URL 后再连接微信。";
            }
        }
        this.configuredBaseUrl = configured;
        this.configuredUrlError = configurationError;
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
        Link link = effectiveLink();
        return Map.of("bound", !sender.isBlank(), "baseUrl", link.baseUrl(), "linkMode", link.mode(),
                "linkReady", !link.baseUrl().isBlank(), "linkNote", link.note());
    }

    /** 只有主动点击配对才签发短期代码；重新配对前，原绑定继续有效。 */
    public synchronized Map<String, Object> createPairing(String publicBaseUrl) {
        if (!configuredUrlError.isBlank()) throw new IllegalArgumentException(configuredUrlError);
        String proposed = configuredBaseUrl.isBlank() ? validateLocalEntry(publicBaseUrl) : configuredBaseUrl;
        // 生成配对码不改变已经绑定的发送者和入口。只有绑定文件成功落盘后才一起切换。
        pairingBaseUrl = proposed;
        byte[] bytes = new byte[5];
        random.nextBytes(bytes);
        pairingCode = HexFormat.of().formatHex(bytes).toUpperCase(java.util.Locale.ROOT);
        pairingExpiresAt = clock.millis() + 300_000;
        Link link = describe(proposed);
        return Map.of("code", pairingCode, "expiresInSeconds", 300, "baseUrl", proposed,
                "linkMode", link.mode(), "linkNote", configuredBaseUrl.isBlank() ? link.note()
                        : "微信链接使用服务端配置的课堂网址，浏览器的本机地址不会改变课程归属。" + link.note());
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
        String previousBaseUrl = baseUrl;
        sender = fromUser;
        baseUrl = pairingBaseUrl;
        try { save(); }
        catch (RuntimeException e) { sender = previousSender; baseUrl = previousBaseUrl; return "绑定未能保存，请检查服务端存储后重试。"; }
        pairingCode = "";
        pairingBaseUrl = "";
        pairingExpiresAt = 0;
        return "课堂已连接。发送「我的课堂」查看课程，或发送「继续学习」打开上次的课程。\n" + effectiveLink().note();
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
        Link link = effectiveLink();
        if (link.baseUrl().isBlank()) return "《" + title + "》\n" + link.note();
        return "《" + title + "》\n" + link.baseUrl() + "/#/classroom/" + course.path("id").asText()
                + "\n" + link.note();
    }

    private record Link(String baseUrl, String mode, String note) {}
    private record Entry(String normalized, String host, boolean lan) {}

    /** 服务端配置始终优先；旧文件只可恢复仍属于本机的LAN入口，不能恢复任意公网域名或回环地址。 */
    private Link effectiveLink() {
        if (!configuredUrlError.isBlank()) return new Link("", "unavailable", configuredUrlError);
        if (!configuredBaseUrl.isBlank()) return describe(configuredBaseUrl);
        if (baseUrl.isBlank()) return new Link("", "unavailable", "尚未配置手机可访问的课堂入口。公网部署请配置 CLASSROOM_PUBLIC_BASE_URL；本机课堂请用这台电脑的局域网IP重新配对。");
        try { return describe(validateLocalEntry(baseUrl)); }
        catch (IllegalArgumentException invalid) { return new Link("", "unavailable", invalid.getMessage()); }
    }

    private static Link describe(String value) {
        Entry entry = parseEntry(value);
        return entry.lan()
                ? new Link(entry.normalized(), "lan", "这是本机局域网课堂；手机与电脑需连接同一 Wi-Fi，电脑应保持课堂服务运行。")
                : new Link(entry.normalized(), "public", "点击链接即可在手机继续学习，无需与电脑连接同一 Wi-Fi。");
    }

    private String validateLocalEntry(String value) {
        Entry entry = parseEntry(value);
        if (!entry.lan()) throw new IllegalArgumentException("本后端尚未配置公网课堂入口，不能把本机课程仅换成公网域名分享。请在对应公网后端配置 CLASSROOM_PUBLIC_BASE_URL；本机使用请填写这台电脑的局域网IP。");
        if (!localAddress.test(entry.host())) throw new IllegalArgumentException("这个局域网IP不属于当前课堂服务器，不能据此分享本机课程。请使用这台电脑实际的局域网IP重新配对。");
        return entry.normalized();
    }

    static String validateBaseUrl(String value) {
        return parseEntry(value).normalized();
    }

    private static Entry parseEntry(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.equals("localhost") || host.endsWith(".localhost")) throw new UnreachableEntry("localhost只表示当前设备自身，手机无法打开电脑的localhost课堂。请用服务端公网入口，或这台电脑的实际局域网IP重新配对。");
            boolean lan;
            if (host.contains(":")) {
                if (!host.matches("[0-9a-f:.]+")) throw new IllegalArgumentException();
                InetAddress address = InetAddress.getByName(host); // 只传已验证的数字IPv6字面量，不进行DNS查询。
                if (address.getAddress().length == 4) lan = ipv4Lan(address.getAddress());
                else {
                    byte[] bytes = address.getAddress();
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isMulticastAddress() || address.isSiteLocalAddress()
                            || (bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 1 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                        throw unreachable();
                    lan = (bytes[0] & 0xfe) == 0xfc;
                    if (!lan && (bytes[0] & 0xe0) != 0x20) throw unreachable();
                }
            } else if (host.matches("[0-9.]+")) {
                String[] parts = host.split("\\.", -1);
                if (parts.length != 4) throw unreachable();
                byte[] bytes = new byte[4];
                for (int index = 0; index < 4; index++) {
                    if (!parts[index].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[index]) > 255) throw unreachable();
                    bytes[index] = (byte) Integer.parseInt(parts[index]);
                }
                lan = ipv4Lan(bytes);
            } else {
                if (!host.contains(".") || !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                        || List.of(".local", ".localdomain", ".lan", ".home", ".home.arpa", ".internal", ".test", ".invalid", ".example")
                                .stream().anyMatch(host::endsWith))
                    throw new UnreachableEntry("这个主机名不能确认是手机可访问的入口。公网部署请配置真实域名，本机课堂请使用实际局域网IP。");
                lan = false;
            }
            String authority = host.contains(":") ? "[" + host + "]" : host;
            if (uri.getPort() != -1) authority += ":" + uri.getPort();
            return new Entry(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority, host, lan);
        } catch (UnreachableEntry e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("请提供手机可访问的 HTTP/HTTPS 课堂入口，不含账号、查询参数或页面路径。");
        }
    }

    private static boolean ipv4Lan(byte[] bytes) {
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        if (a == 0 || a == 127 || a >= 224 || a == 169 && b == 254 || a == 100 && b >= 64 && b <= 127
                || a == 192 && b == 0 && (c == 0 || c == 2) || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113) throw unreachable();
        return a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168;
    }

    private static UnreachableEntry unreachable() {
        return new UnreachableEntry("这个回环、未指定或保留地址不能作为手机课堂链接。请使用服务端公网入口，或当前电脑实际的局域网IP。");
    }

    private static final class UnreachableEntry extends IllegalArgumentException {
        private UnreachableEntry(String message) { super(message); }
    }

    /** 只读取本机网卡，不解析任意域名，不探测其他设备，也不改变网络配置。 */
    private static boolean isLocalLanAddress(String host) {
        try {
            byte[] wanted = InetAddress.getByName(host).getAddress(); // 调用者只允许已解析的RFC1918/ULA数字地址。
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                var nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                var addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) if (Arrays.equals(wanted, addresses.nextElement().getAddress())) return true;
            }
            return false;
        } catch (IOException | SecurityException e) {
            return false;
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
