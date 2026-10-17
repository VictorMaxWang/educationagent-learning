package com.demo.edu.classroom;

/** 课堂边界错误：给用户稳定提示，避免把模型配置或进程日志暴露到 HTTP。 */
public class ClassroomException extends RuntimeException {
    private final int status;
    private final String code;
    public ClassroomException(int status, String message) { this(status,message,null); }
    public ClassroomException(int status,String message,String code) { super(message);this.status=status;this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
}
