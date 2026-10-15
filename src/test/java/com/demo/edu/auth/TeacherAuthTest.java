package com.demo.edu.auth;

import com.demo.edu.mapper.EduTeacherMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 教师登录鉴权与越权防护测试（不依赖 Spring 上下文 / 数据库）。
 *
 * <h3>这一组守的是"教师绝对看不到别的班"</h3>
 * 隐私承诺不能靠"我们没提供那个功能"，得靠两件事，两条都在这里测：
 * <ol>
 *   <li><b>token 与班级绑定</b>——一个 token 只能换出一个班级；</li>
 *   <li><b>班级不进请求参数</b>——前端就算手改 URL/body 塞 classId，拦截器也完全不看它，
 *       仍按 token 决定的班级走。这是"篡改请求也拿不到别人数据"的可验证证据。</li>
 * </ol>
 */
class TeacherAuthTest {

    /** 假 Mapper：两名教师，各自绑不同班级。 */
    private static EduTeacherMapper fakeMapper() {
        Map<String, String> classes = new LinkedHashMap<>();
        classes.put("teacher_a", "class_a");
        classes.put("teacher_b", "class_b");
        String hash = PasswordHasher.hash("secret123");
        return new EduTeacherMapper() {
            @Override
            public Map<String, Object> selectByUsername(String username) {
                if (!classes.containsKey(username)) return null;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", 1L);
                row.put("username", username);
                row.put("passwordHash", hash);
                row.put("classId", classes.get(username));
                return row;
            }

            @Override
            public int insert(String username, String passwordHash, String classId) {
                classes.put(username, classId);
                return 1;
            }

            @Override
            public String selectClassName(String classId) {
                return "演示班-" + classId;
            }

            @Override
            public int countAll() {
                return classes.size();
            }
        };
    }

    private static EduAuthProperties props(int ttlMinutes) {
        EduAuthProperties p = new EduAuthProperties();
        p.setTokenTtlMinutes(ttlMinutes);
        p.getDemoTeacher().setEnabled(false); // 测试里不自动建演示账号
        return p;
    }

    @AfterEach
    void clearContext() {
        EduContext.clear();
    }

    @Test
    @DisplayName("密码正确 → 签发 token，且 token 绑定该教师的班级")
    void loginSucceedsAndBindsClass() {
        EduTeacherService service = new EduTeacherService(fakeMapper(), props(60));
        EduTeacherService.LoginResult r = service.login("teacher_a", "secret123");
        assertTrue(r.success(), "应当登录成功");
        assertNotNull(r.token());
        assertEquals("class_a", r.classId());
        assertEquals("class_a", service.classIdOfToken(r.token()));
    }

    @Test
    @DisplayName("密码错误与账号不存在返回同一句话（不泄露账号是否存在）")
    void doesNotEnumerateAccounts() {
        EduTeacherService service = new EduTeacherService(fakeMapper(), props(60));
        String wrongPassword = service.login("teacher_a", "bad-password").error();
        String noSuchUser = service.login("teacher_x", "secret123").error();
        assertEquals(wrongPassword, noSuchUser, "两种失败的提示必须完全一致，否则可枚举账号");
        assertEquals("账号或密码不正确", wrongPassword);
    }

    @Test
    @DisplayName("token → 班级是单向绑定：A 的 token 换不出 B 的班级")
    void tokenIsBoundToOneClass() {
        EduTeacherService service = new EduTeacherService(fakeMapper(), props(60));
        String tokenA = service.login("teacher_a", "secret123").token();
        String tokenB = service.login("teacher_b", "secret123").token();
        assertEquals("class_a", service.classIdOfToken(tokenA));
        assertEquals("class_b", service.classIdOfToken(tokenB));
        assertNotEquals(service.classIdOfToken(tokenA), service.classIdOfToken(tokenB));
        assertNull(service.classIdOfToken("et_不存在的token"));
        assertNull(service.classIdOfToken(null));
    }

    @Test
    @DisplayName("过期 token 失效，且过期会话被顺手清掉")
    void expiredTokenRejected() {
        EduTeacherService service = new EduTeacherService(fakeMapper(), props(60));
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        service.setClockForTest(now::get);

        String token = service.login("teacher_a", "secret123").token();
        assertNotNull(service.classIdOfToken(token), "刚签发的 token 应有效");

        now.addAndGet(61 * 60_000L); // 走过 60 分钟 TTL
        assertNull(service.classIdOfToken(token), "过期 token 必须失效");
        assertEquals(0, service.activeSessionCount(), "过期会话应被清掉，避免内存无限增长");
    }

    @Test
    @DisplayName("拦截器：无 token 一律 401")
    void interceptorRejectsMissingToken() throws Exception {
        EduAuthProperties p = props(60);
        EduAuthInterceptor interceptor = new EduAuthInterceptor(p,
                new EduTeacherService(fakeMapper(), p));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/edu/teacher/crisis-summary");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("拦截器：前端篡改 classId 参数无效——班级只由 token 决定")
    void interceptorIgnoresClassIdParam() throws Exception {
        EduAuthProperties p = props(60);
        EduTeacherService service = new EduTeacherService(fakeMapper(), p);
        String tokenA = service.login("teacher_a", "secret123").token();
        EduAuthInterceptor interceptor = new EduAuthInterceptor(p, service);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/edu/teacher/crisis-summary");
        req.addHeader("X-Edu-Token", tokenA);
        req.setParameter("classId", "class_b");   // ← 试图越权
        req.addHeader("X-Edu-Class", "class_b");  // ← 换个头再试

        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        assertEquals("class_a", EduContext.getClassId(),
                "班级只能来自 token；请求里塞的任何 classId 都必须被无视");
    }

    @Test
    @DisplayName("共享口令后门可关闭：关掉之后旧口令不再能进")
    void legacyTokenCanBeDisabled() throws Exception {
        EduAuthProperties p = props(60);
        p.getTeacherClasses().put("t_demo_1", "class_demo_1");
        EduTeacherService service = new EduTeacherService(fakeMapper(), p);
        EduAuthInterceptor interceptor = new EduAuthInterceptor(p, service);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/edu/teacher/crisis-summary");
        req.addHeader("X-Edu-Token", "t_demo_1");
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()),
                "默认允许共享口令（本地演示用）");
        EduContext.clear();

        p.setLegacyTokenEnabled(false);
        assertFalse(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()),
                "关掉后门后共享口令必须失效");
    }

    @Test
    @DisplayName("总开关关闭 = 一律 401（fail-closed，而不是人人可见）")
    void disabledMeansDenyNotAllow() throws Exception {
        EduAuthProperties p = props(60);
        p.setEnabled(false);
        EduAuthInterceptor interceptor = new EduAuthInterceptor(p,
                new EduTeacherService(fakeMapper(), p));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/edu/teacher/crisis-summary");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
    }
}
