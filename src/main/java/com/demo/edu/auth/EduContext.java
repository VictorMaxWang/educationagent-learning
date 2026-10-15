package com.demo.edu.auth;

/**
 * 本次请求所属的班级（由 {@link EduAuthInterceptor} 在校验口令后写入）。
 *
 * <p>存在的意义是<b>让"班级"只能来自服务端校验结果</b>：控制器不接收 classId 参数，
 * 因此前端无法通过改 URL / 改 body 去查别的班——这比"接收参数后做权限判断"少一整类漏洞。
 *
 * <p>用 {@code ThreadLocal}：Spring MVC 一次请求一个线程；
 * 拦截器的 {@code afterCompletion} 负责清理（<b>必须清</b>，线程池会复用线程，
 * 不清就会把上一个请求的班级泄漏给下一个请求）。
 */
public final class EduContext {

    private static final ThreadLocal<String> CLASS_ID = new ThreadLocal<>();

    /**
     * 教师标识（登录账号；共享口令路径下是常量占位）。
     *
     * <p>用途：记"这道题是谁布置的"（{@code edu_exercise.creator}），
     * 从而让"教师只能看自己创建的题"可判定。
     * <b>不要把共享口令本身写进这里</b>——那等于把口令落到数据库列里。
     */
    private static final ThreadLocal<String> TEACHER = new ThreadLocal<>();

    private EduContext() {
    }

    public static void setClassId(String classId) {
        CLASS_ID.set(classId);
    }

    public static String getClassId() {
        return CLASS_ID.get();
    }

    public static void setTeacher(String teacher) {
        TEACHER.set(teacher);
    }

    public static String getTeacher() {
        return TEACHER.get();
    }

    public static void clear() {
        CLASS_ID.remove();
        TEACHER.remove();
    }
}
