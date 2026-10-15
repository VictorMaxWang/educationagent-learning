package com.demo.fileconvert.exception;

/**
 * 转换进程异常退出或转换引擎不可用（HTTP 500）。
 * 触发场景：LibreOffice/ImageMagick 未安装、exitCode != 0、找不到输出产物。
 */
public class ConvertProcessException extends RuntimeException {

    public ConvertProcessException(String message) {
        super(message);
    }

    public ConvertProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
