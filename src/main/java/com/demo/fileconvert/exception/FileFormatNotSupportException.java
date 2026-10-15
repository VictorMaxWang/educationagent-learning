package com.demo.fileconvert.exception;

/**
 * 不支持的输入/输出文件格式（HTTP 400）。
 * 触发场景：非白名单扩展名、目标格式不属于该分类的输出白名单。
 */
public class FileFormatNotSupportException extends RuntimeException {

    public FileFormatNotSupportException(String message) {
        super(message);
    }

    public FileFormatNotSupportException(String message, Throwable cause) {
        super(message, cause);
    }
}
