package com.demo.edu.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教师端鉴权配置，键前缀 {@code edu.auth.*}。
 *
 * <h3>两条鉴权路径（并存，可分别关闭）</h3>
 * <ol>
 *   <li><b>教师账号登录</b>（主路径）：{@code POST /api/edu/teacher/login} 校验
 *       {@code edu_teacher} 表里的用户名 + PBKDF2 密码，签发一次性 token（内存，带 TTL）；</li>
 *   <li><b>共享口令</b>（演示后门，{@link #legacyTokenEnabled}）：直接把 {@link #teacherClasses}
 *       里的 key 当 token 用。它没有身份、没有有效期，只适合本地演示/联调，
 *       正式部署应当关掉（{@code edu.auth.legacy-token-enabled=false}）。</li>
 * </ol>
 *
 * <p>两条路径最终都只产出一个东西：<b>本次请求能看哪个班</b>。
 * 控制器不接收 classId 参数，班级只能来自这里——这是"改 URL 也看不到别的班"的结构性保证。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edu.auth")
public class EduAuthProperties {

    /** 总开关。false → 教师端全部接口一律 401（<b>fail-closed</b>，不是放开）。 */
    private boolean enabled = true;

    /** 是否允许用共享口令当 token（演示后门）。正式部署建议 false。 */
    private boolean legacyTokenEnabled = true;

    /** 登录签发的 token 有效期（分钟）。过期后需重新登录。 */
    private int tokenTtlMinutes = 480;

    /**
     * 共享口令 → 班级 的映射，形如 {@code edu.auth.teacher-classes.t_demo_1=class_demo_1}。
     * 一个口令只能看一个班；要看多个班就配多个口令。
     */
    private Map<String, String> teacherClasses = new LinkedHashMap<>();

    /** 演示教师账号：启动时若表为空则自动创建，避免演示前手写 SQL。 */
    private final DemoTeacher demoTeacher = new DemoTeacher();

    @Getter
    @Setter
    public static class DemoTeacher {
        /** 是否自动创建演示账号。正式部署应关掉。 */
        private boolean enabled = true;
        private String username = "teacher1";
        private String password = "teacher123";
        /** 绑定的班级 id。 */
        private String classId = "class_demo_1";
    }
}
