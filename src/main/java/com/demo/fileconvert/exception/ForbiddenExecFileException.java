package com.demo.fileconvert.exception;

/**
 * 可执行文件/脚本/恶意内容拦截（HTTP 403）。
 * 触发场景：双重后缀伪装、可执行黑名单、MZ 魔数、恶意关键词扫描。
 */
public class ForbiddenExecFileException extends RuntimeException {

    public ForbiddenExecFileException(String message) {
        super(message);
    }

    public ForbiddenExecFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
