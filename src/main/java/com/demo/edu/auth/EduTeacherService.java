package com.demo.edu.auth;

import com.demo.edu.mapper.EduTeacherMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 教师登录与 token 管理。
 *
 * <h3>token 放内存，为什么不做 JWT</h3>
 * JWT 需要一套签名密钥的生成/轮换/配置管理，而这里的需求只是"登录后可调用若干教师接口"。
 * 内存 token（随机 128 位 + TTL）够用且没有密钥泄露面：<b>进程重启后所有教师重新登录</b>，
 * 这对本地部署的教学原型是合理代价，也顺带免掉了"token 被偷后长期有效"的问题。
 *
 * <h3>登录与鉴权的分工</h3>
 * 本类只回答两件事：① 用户名密码对不对（{@link #login}）；
 * ② 这个 token 现在能看哪个班（{@link #classIdOfToken}）。
 * "跨班访问"在这一层就不可能发生——token 与班级是绑定的，调用方连班级参数都拿不到。
 */
@Slf4j
@Service
public class EduTeacherService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EduTeacherMapper teacherMapper;
    private final EduAuthProperties authProperties;

    /** token → 会话信息 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 时间源。生产环境就是 {@code System.currentTimeMillis}；
     * 测试里注入一个可控时钟来验证"过期 token 失效"——比反射改 record 字段正当得多
     * （record 字段是 final，反射会被 Java 17+ 拒绝）。
     */
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** 仅测试用：注入时间源。 */
    void setClockForTest(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    public EduTeacherService(EduTeacherMapper teacherMapper, EduAuthProperties authProperties) {
        this.teacherMapper = teacherMapper;
        this.authProperties = authProperties;
    }

    /** 登录结果。失败时 {@code token} 为 null，{@code error} 给用户看。 */
    public record LoginResult(boolean success, String token, String username, String classId,
                              String className, long expiresAt, String error) {
    }

    /** 一次会话（只在内存里）。 */
    private record Session(String username, String classId, long expiresAt) {
    }

    /**
     * 教师登录。
     *
     * <p>失败一律返回同一句"账号或密码不正确"——不区分"用户不存在"与"密码错误"，
     * 避免把账号枚举出来。
     */
    public LoginResult login(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            return new LoginResult(false, null, null, null, null, 0, "账号与密码不能为空");
        }
        Map<String, Object> row;
        try {
            row = teacherMapper.selectByUsername(username.trim());
        } catch (Exception e) {
            log.warn("教师登录查询失败：{}", e.toString());
            return new LoginResult(false, null, null, null, null, 0, "登录服务暂不可用，请稍后重试");
        }
        if (row == null) {
            log.warn("教师登录失败：账号不存在（username={}）", username);
            return new LoginResult(false, null, null, null, null, 0, "账号或密码不正确");
        }
        String stored = String.valueOf(row.get("passwordHash"));
        if (!PasswordHasher.verify(rawPassword, stored)) {
            log.warn("教师登录失败：密码不匹配（username={}）", username);
            return new LoginResult(false, null, null, null, null, 0, "账号或密码不正确");
        }

        String classId = String.valueOf(row.get("classId"));
        String token = newToken();
        long ttlMs = Math.max(5, authProperties.getTokenTtlMinutes()) * 60_000L;
        long expiresAt = clock.getAsLong() + ttlMs;
        sessions.put(token, new Session(String.valueOf(row.get("username")), classId, expiresAt));

        String className = null;
        try {
            className = teacherMapper.selectClassName(classId);
        } catch (Exception ignore) {
            // 班级名只是展示用，取不到不影响登录
        }
        log.info("👩🏫 教师登录成功：{} → 班级 {}", row.get("username"), classId);
        return new LoginResult(true, token, String.valueOf(row.get("username")), classId, className, expiresAt, null);
    }

    /**
     * token → 教师账号。无效或过期返回 {@code null}。
     *
     * <p>与 {@link #classIdOfToken} 同源：两者都从同一份内存会话里取，
     * 所以"教师是谁"与"他能看哪个班"永远一致。
     */
    public String usernameOfToken(String token) {
        if (token == null || token.isBlank()) return null;
        Session session = sessions.get(token.trim());
        if (session == null) return null;
        if (session.expiresAt() < clock.getAsLong()) {
            sessions.remove(token.trim());
            return null;
        }
        return session.username();
    }

    /**
     * token → 班级。无效或过期返回 {@code null}（调用方据此 401）。
     * 过期的会话顺手清掉，避免内存无限增长。
     */
    public String classIdOfToken(String token) {
        if (token == null || token.isBlank()) return null;
        Session session = sessions.get(token.trim());
        if (session == null) return null;
        if (session.expiresAt() < clock.getAsLong()) {
            sessions.remove(token.trim());
            return null;
        }
        return session.classId();
    }

    /** 已登录的教师数量（供 /api/edu/health 展示，不含任何身份信息）。 */
    public int activeSessionCount() {
        long now = clock.getAsLong();
        sessions.values().removeIf(s -> s.expiresAt() < now);
        return sessions.size();
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token.trim());
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "et_" + HexFormat.of().formatHex(bytes);
    }

    /**
     * 演示账号自动创建：仅当表里一个教师都没有、且配置开启时执行一次。
     *
     * <p>为什么不做成 SQL 种子：密码必须以哈希形式落库，
     * 而 {@code schema.sql} 里没法算 PBKDF2（也不该在那里放明文口令）。
     */
    @PostConstruct
    public void seedDemoTeacher() {
        EduAuthProperties.DemoTeacher demo = authProperties.getDemoTeacher();
        if (demo == null || !demo.isEnabled()) return;
        try {
            if (teacherMapper.countAll() > 0) return;
            String username = demo.getUsername() == null || demo.getUsername().isBlank()
                    ? "teacher1" : demo.getUsername().trim();
            String password = demo.getPassword() == null || demo.getPassword().isBlank()
                    ? "teacher123" : demo.getPassword();
            teacherMapper.insert(username, PasswordHasher.hash(password),
                    demo.getClassId() == null || demo.getClassId().isBlank() ? "class_demo_1" : demo.getClassId());
            log.warn("""

                    ══════════════ 【教师端】演示账号已创建 ══════════════
                     账号：{}
                     密码：{}  ← 演示用，正式部署请删除该账号并把
                               edu.auth.demo-teacher.enabled 置为 false
                     绑定班级：{}
                    ═══════════════════════════════════════════════════""",
                    username, password, demo.getClassId());
        } catch (Exception e) {
            log.warn("演示教师账号创建失败（不影响其它功能）：{}", e.toString());
        }
    }
}
