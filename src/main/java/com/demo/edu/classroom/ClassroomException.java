package com.demo.edu.classroom;

/** 课堂边界错误：给用户稳定提示，避免把模型配置或进程日志暴露到 HTTP。 */
public class ClassroomException extends RuntimeException {
    private final int status;
    public ClassroomException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
